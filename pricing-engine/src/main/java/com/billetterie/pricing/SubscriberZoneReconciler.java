package com.billetterie.pricing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Réconciliation de la zone abonnés avant la clôture.
 *
 * <p>Les supporters fidèles porteurs d'une carte de saison disposent d'un nombre de places
 * <b>fixe</b>, dans une zone dédiée, à prix fixe et stable, entièrement hors du mécanisme
 * d'enchère. C'est le dispositif de fidélisation : leur tarif ne bouge pas, quelle que soit
 * l'affluence attendue pour le match.
 *
 * <p>Le problème que ce composant résout est celui des places réservées non consommées. Si la
 * zone compte 5 000 places et que 3 800 abonnés seulement confirment, 1 200 sièges restent vides
 * alors que des supporters ont été évincés au prix de clôture. On perd à la fois de la recette et
 * du garnissage, et le reproche est visible à la télévision.
 *
 * <p>La correction tient en une échéance : les abonnés confirment leur venue avant
 * {@code confirm_deadline}, strictement antérieure à la clôture de la fenêtre. À cette échéance,
 * les places non confirmées sont réintégrées au pool dynamique de leur propre catégorie. La
 * capacité {@code N_k} est donc définitive au moment du calcul du prix — condition nécessaire
 * pour que le prix de clôture soit calculable et défendable.
 *
 * <p>Ordre des opérations, non négociable :
 *
 * <pre>
 *   ouverture ──► confirmations abonnés ──► réconciliation ──► clôture ──► calcul de p*
 *                                          (N_k figé ici)
 * </pre>
 */
@Service
public class SubscriberZoneReconciler {

    private static final Logger log = LoggerFactory.getLogger(SubscriberZoneReconciler.class);

    private final SubscriberZoneRepository zones;
    private final DynamicPoolRepository pools;
    private final MatchRepository matches;

    public SubscriberZoneReconciler(
            SubscriberZoneRepository zones,
            DynamicPoolRepository pools,
            MatchRepository matches) {
        this.zones = zones;
        this.pools = pools;
        this.matches = matches;
    }

    /**
     * Balayage périodique des échéances de confirmation échues.
     *
     * <p>Déclenché par l'échéance publiée, jamais par un geste d'administrateur : un acteur
     * capable de choisir le moment de la réconciliation choisirait de fait la capacité du pool,
     * donc le prix de clôture.
     */
    @Scheduled(fixedDelayString = "PT1M")
    public void reconcileDueZones() {
        for (UUID matchId : zones.findMatchesWithDueUnreconciledZones(Instant.now())) {
            try {
                reconcile(matchId);
            } catch (RuntimeException e) {
                // Un échec sur un match ne doit pas bloquer les autres : l'échéance de chacun
                // est indépendante et la clôture arrive de toute façon.
                log.error("Réconciliation échouée pour le match {}", matchId, e);
            }
        }
    }

    /**
     * Fige la capacité du pool dynamique pour un match.
     *
     * <p>Idempotente : un second appel après réconciliation ne modifie rien. C'est indispensable,
     * le balayage pouvant se superposer à un déclenchement manuel en mode incident.
     */
    @Transactional
    public ReconciliationReport reconcile(UUID matchId) {
        MatchEntity match = matches.lockForUpdate(matchId).orElseThrow();
        if (match.getStatus() != MatchStatus.BIDDING_OPEN) {
            throw new IllegalStateException(
                    "réconciliation impossible : la fenêtre du match " + matchId + " n'est plus ouverte");
        }

        List<SubscriberZoneEntity> zoneList = zones.findByMatchId(matchId);
        int totalReleased = 0;
        int totalConfirmed = 0;

        for (SubscriberZoneEntity zone : zoneList) {
            if (zone.getReconciledAt() != null) {
                continue; // déjà figée
            }
            if (Instant.now().isBefore(zone.getConfirmDeadline())) {
                continue; // échéance non échue
            }

            int confirmed = zones.countConfirmedSubscribers(matchId, zone.getCategoryId());
            int released = zone.getReservedSeats() - confirmed;
            if (released < 0) {
                // Survente de la zone abonnés : anomalie de données, pas une situation nominale.
                throw new IllegalStateException(
                        "places abonnés confirmées ("
                                + confirmed
                                + ") supérieures aux places réservées ("
                                + zone.getReservedSeats()
                                + ") pour la catégorie "
                                + zone.getCategoryId());
            }

            zone.setConfirmedSeats(confirmed);
            zone.setReconciledAt(Instant.now());
            zones.save(zone);

            // N_k = capacité physique de la catégorie − places abonnés effectivement confirmées.
            int physical = pools.physicalCapacityOf(zone.getCategoryId());
            pools.setDynamicCapacity(matchId, zone.getCategoryId(), physical - confirmed);

            totalConfirmed += confirmed;
            totalReleased += released;
            log.info(
                    "Catégorie {} : {} abonnés confirmés, {} places réintégrées, N_k = {}",
                    zone.getCategoryId(),
                    confirmed,
                    released,
                    physical - confirmed);
        }

        // Les catégories sans zone abonnés gardent leur capacité physique entière.
        pools.initialiseUntouchedCategories(matchId);

        return new ReconciliationReport(matchId, totalConfirmed, totalReleased);
    }

    public record ReconciliationReport(UUID matchId, int confirmedSeats, int releasedSeats) {}
}
