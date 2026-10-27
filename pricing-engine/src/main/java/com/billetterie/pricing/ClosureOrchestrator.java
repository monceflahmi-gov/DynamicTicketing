package com.billetterie.pricing;

import com.billetterie.pricing.domain.AllocationEngine;
import com.billetterie.pricing.domain.AllocationResult;
import com.billetterie.pricing.domain.Category;
import com.billetterie.pricing.domain.RankedBid;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration de la clôture, découpée en transactions courtes.
 *
 * <p>Corrige la faille du verrou longue durée. Dans la version initiale, le verrou pessimiste
 * sur {@code Match} était détenu pendant le calcul, l'allocation et les paiements, soit
 * plusieurs minutes. Une transaction longue en PostgreSQL bloque le {@code VACUUM}, fait gonfler
 * les tables et retient les connexions du pool au moment précis où le reste du système en a le
 * plus besoin. Ici le verrou ne couvre que le basculement de statut, qui dure des millisecondes.
 *
 * <h2>La clôture est automatique</h2>
 *
 * Elle est déclenchée par l'échéance publiée à l'ouverture, jamais par un geste d'administrateur.
 * Un administrateur qui voit la courbe de demande en temps réel et peut clôturer à l'instant qui
 * l'arrange peut optimiser la recette, ou la déprimer au profit de complices. La clôture manuelle
 * n'existe qu'en mode incident, sous double validation, et notifiée à tous les participants.
 */
@Service
public class ClosureOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ClosureOrchestrator.class);

    /** Marge au-delà de N pour couvrir les ensembles d'acceptation dissymétriques. */
    private static final int WINDOW_MARGIN = 5_000;

    /** Taille des lots de capture. Chaque lot est une transaction courte et idempotente. */
    private static final int CAPTURE_BATCH_SIZE = 2_000;

    private final BidCommitmentRepository bids;
    private final DynamicPoolRepository pools;
    private final MatchRepository matches;
    private final RevealService reveals;
    private final MerkleAnchorService anchors;
    private final PaymentCaptureService captures;
    private final AllocationEngine engine = new AllocationEngine();

    public ClosureOrchestrator(
            BidCommitmentRepository bids,
            DynamicPoolRepository pools,
            MatchRepository matches,
            RevealService reveals,
            MerkleAnchorService anchors,
            PaymentCaptureService captures) {
        this.bids = bids;
        this.pools = pools;
        this.matches = matches;
        this.reveals = reveals;
        this.anchors = anchors;
        this.captures = captures;
    }

    /**
     * Étape 1 — scellement de la fenêtre. Transaction courte, verrou minimal.
     *
     * <p>Le verrou exclusif sur la ligne {@code match} ne protège que le basculement de statut.
     * Toute écriture de déclaration lit ce statut sous verrou partagé dans sa propre transaction,
     * de sorte que l'autorité temporelle soit unique et que l'ordre soit total : il n'existe pas
     * d'horloge globale dans un système réparti, seule l'horloge de la base peut faire foi.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sealWindow(UUID matchId) {
        MatchEntity match = matches.lockForUpdate(matchId).orElseThrow();
        if (match.getStatus() != MatchStatus.BIDDING_OPEN) {
            log.info("Fenêtre déjà scellée pour {} — opération idempotente", matchId);
            return;
        }
        match.setStatus(MatchStatus.BIDDING_CLOSED);
        match.setSealedAt(java.time.Instant.now());
        matches.save(match);
        log.info("Fenêtre scellée pour le match {}", matchId);
    }

    /**
     * Étape 2 — calcul. Aucune transaction longue, aucun verrou.
     *
     * <p>Fonction pure des déclarations révélées. Rejouable à volonté : c'est la propriété qui
     * permet de défendre le résultat devant une contestation.
     */
    public AllocationResult computeAllocation(UUID matchId) {
        anchors.anchorFinalRoot(matchId);
        reveals.processRevealWindow(matchId);

        List<Category> categories = pools.loadCategories(matchId);
        int n = categories.stream().mapToInt(Category::capacity).sum();
        BigDecimal reserve = pools.lowestReservePrice(matchId);

        List<RankedBid> window = loadWindow(matchId, n);
        AllocationResult result = engine.allocate(categories, window, reserve);

        crossCheckAgainstSqlOrderStatistics(matchId, categories, result, reserve);
        log.info(
                "Calcul terminé pour {} : {} gagnants, prix {}",
                matchId,
                result.winnerCount(),
                result.clearingPricesSorted());
        return result;
    }

    private List<RankedBid> loadWindow(UUID matchId, int n) {
        long valid = bids.countValidReveals(matchId);
        int limit = (int) Math.min(valid, (long) n + WINDOW_MARGIN);
        List<BidCommitmentEntity> rows = bids.findTopRanked(matchId, limit);
        List<RankedBid> out = new ArrayList<>(rows.size());
        for (BidCommitmentEntity row : rows) {
            out.add(row.toRankedBid());
        }
        return out;
    }

    /**
     * Vérification croisée entre le moteur et la base.
     *
     * <p>Sous acceptation complète — le cas par défaut — le prix de la catégorie {@code k} doit
     * être exactement la déclaration de rang {@code cumul(k)+1}, que PostgreSQL rend en une seule
     * valeur. Un écart signale soit un ensemble d'acceptation dissymétrique, soit un défaut : dans
     * les deux cas on veut le savoir avant d'écrire quoi que ce soit.
     */
    private void crossCheckAgainstSqlOrderStatistics(
            UUID matchId, List<Category> categories, AllocationResult result, BigDecimal reserve) {
        long cumulative = 0;
        for (Category c : categories.stream().sorted().toList()) {
            cumulative += c.capacity();
            BigDecimal sqlPrice = bids.findOrderStatistic(matchId, cumulative);
            BigDecimal expected =
                    (sqlPrice == null || sqlPrice.compareTo(reserve) < 0) ? reserve : sqlPrice;
            BigDecimal enginePrice = result.priceOf(c.code());
            if (enginePrice.compareTo(expected) != 0) {
                log.warn(
                        "Écart moteur/SQL sur {} : moteur={} SQL={}. "
                                + "Attendu si des ensembles d'acceptation restreints sont en jeu.",
                        c.code(),
                        enginePrice,
                        expected);
            }
        }
    }

    /**
     * Étape 3 — persistance et affectation par lots. Transaction courte.
     *
     * <p>Aucun gagnant n'est chargé en mémoire : l'affectation se fait par {@code UPDATE}
     * ensembliste, d'abord les déclarations strictement supérieures au prix, puis les ex æquo
     * exactement au prix dans l'ordre de priorité temporelle.
     */
    @Transactional
    public void persistAllocation(UUID matchId, AllocationResult result) {
        List<Category> categories = pools.loadCategories(matchId).stream().sorted().toList();

        for (Category c : categories) {
            BigDecimal price = result.priceOf(c.code());
            UUID categoryId = pools.categoryIdOf(matchId, c.code());

            int strict = bids.markStrictWinners(matchId, categoryId, price);
            int remaining = c.capacity() - strict;
            int tied = 0;
            if (remaining > 0) {
                tied = bids.markTiedWinnersByPriority(matchId, categoryId, price, remaining);
            }
            pools.writeClearingPrice(matchId, categoryId, price);
            log.info(
                    "Catégorie {} : prix {}, {} gagnants stricts, {} ex æquo départagés",
                    c.code(),
                    price,
                    strict,
                    tied);
        }
        bids.markRemainingAsOutbid(matchId);
        matches.updateStatus(matchId, MatchStatus.ALLOCATED);
    }

    /**
     * Étape 4 — captures de paiement par lots, chaque lot dans sa propre transaction.
     *
     * <p>Chaque capture porte une clé d'idempotence persistée en base dans la même transaction
     * que son effet, et transmise au PSP. Un rejeu Kafka viole la contrainte d'unicité et reste
     * donc sans effet : c'est ainsi, et non par une garantie de transport, qu'on obtient
     * l'exactement-une-fois.
     */
    public CaptureSummary captureAllWinners(UUID matchId) {
        int totalCaptured = 0;
        int totalFailed = 0;
        List<BidCommitmentEntity> batch;
        while (!(batch = bids.findNextCaptureBatch(matchId, CAPTURE_BATCH_SIZE)).isEmpty()) {
            CaptureSummary s = captures.captureBatch(matchId, batch);
            totalCaptured += s.captured();
            totalFailed += s.failed();
            if (s.captured() == 0 && s.failed() == 0) {
                log.error("Lot sans progression pour {} — arrêt pour éviter une boucle", matchId);
                break;
            }
        }
        // Une capture refusée réattribue la place au rang suivant, selon la même règle de
        // priorité. Sans cette cascade, des sièges attribués restent impayés et vides.
        if (totalFailed > 0) {
            log.warn("{} captures refusées pour {} — réattribution en cascade", totalFailed, matchId);
            captures.cascadeReallocation(matchId, totalFailed);
        }
        return new CaptureSummary(totalCaptured, totalFailed);
    }

    /** Résumé agrégé, exposé au rapport post-match. */
    public record CaptureSummary(int captured, int failed) {}

    /** Vue de supervision : prix par catégorie, pour le rapport post-match. */
    public Map<String, BigDecimal> clearingPrices(UUID matchId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : pools.loadCategories(matchId).stream().sorted().toList()) {
            out.put(c.code(), pools.clearingPriceOf(matchId, c.code()));
        }
        return out;
    }
}
