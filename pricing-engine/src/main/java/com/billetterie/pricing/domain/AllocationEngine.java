package com.billetterie.pricing.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Moteur d'allocation et de tarification.
 *
 * <p>Fonction <b>pure</b> : aucun accès base, aucune horloge, aucun aléa non fourni en entrée.
 * Mêmes entrées, même sortie, quel que soit le nombre d'instances ou le moment d'exécution.
 * C'est la propriété qui rend la clôture rejouable et donc auditable.
 *
 * <h2>Règle 1 — Prix de clôture par déplacement</h2>
 *
 * Le prix d'une catégorie {@code k} est la plus haute déclaration parmi les supporters qui
 * <b>acceptaient {@code k} et n'ont obtenu ni {@code k} ni mieux</b>. C'est le coût d'opportunité
 * de la dernière place de cette catégorie : la valeur dont on prive le premier évincé.
 *
 * <p>Avec une seule catégorie, cette règle se réduit <b>exactement</b> à
 * {@code p* = b(N+1)} — la (N+1)-ième déclaration, soit la plus haute perdante. C'est la
 * correction de la faille conceptuelle majeure : la règle {@code b(N)} faisait payer au gagnant
 * marginal sa propre déclaration, annulant son surplus et détruisant la dominance de la véracité.
 * Ici, aucun gagnant ne contribue jamais au prix qu'il paie.
 *
 * <p>Avec plusieurs catégories, elle produit l'emboîtement attendu
 * {@code p(VIP) >= p(Tribune) >= ...} : une meilleure catégorie coûte plus cher parce que la
 * concurrence pour y entrer était plus forte. La monotonie est vérifiée et imposée en sortie.
 *
 * <h2>Règle 2 — Affectation assortative</h2>
 *
 * Les déclarations sont classées une seule fois, puis parcourues dans l'ordre : chacun reçoit
 * la meilleure catégorie encore disponible parmi celles qu'il accepte. Exemple de référence —
 * 6 places (1 VIP, 5 tribune) et des déclarations 600, 500, 500, 500, 500, 300 : la première
 * obtient le VIP, les cinq autres la tribune.
 *
 * <p>Hypothèse assumée : différenciation <b>verticale</b>, l'ordre de désirabilité est le même
 * pour tous et publié à l'avance. {@code acceptableCategories} est la soupape pour le supporter
 * dont les préférences ne suivent pas cet ordre.
 *
 * <h2>Règle 3 — Départage</h2>
 *
 * À montant égal, priorité au plus petit {@code commitSequence}, c'est-à-dire au premier ayant
 * posé sa demande, une modification valant nouvelle demande. Le tri est <b>total</b> : aucune
 * exécution ne peut produire deux classements différents sur les mêmes entrées.
 */
public final class AllocationEngine {

    public static final String REASON_BELOW_RESERVE = "BELOW_RESERVE";
    public static final String REASON_OUTBID = "OUTBID";
    public static final String REASON_NO_ACCEPTABLE_CATEGORY = "NO_ACCEPTABLE_CATEGORY_AVAILABLE";

    /**
     * Ordre total sur les déclarations.
     *
     * <p>Montant décroissant, puis séquence d'engagement croissante (priorité au premier arrivé),
     * puis empreinte de départage croissante comme garde-fou déterministe.
     */
    public static final Comparator<RankedBid> RANKING =
            Comparator.comparing(RankedBid::amount, Comparator.reverseOrder())
                    .thenComparingLong(RankedBid::commitSequence)
                    .thenComparing(RankedBid::tieBreakHash);

    public AllocationResult allocate(
            List<Category> categories, List<RankedBid> revealedBids, BigDecimal reservePrice) {

        Objects.requireNonNull(categories, "categories");
        Objects.requireNonNull(revealedBids, "revealedBids");
        Objects.requireNonNull(reservePrice, "reservePrice");
        validateCategories(categories);

        List<Category> byDesirability = categories.stream().sorted().toList();

        // Les déclarations sous le prix de réserve ne participent pas au classement.
        // Ce filtre est indispensable ici et non à la soumission : sous engagement scellé,
        // le serveur ignore le montant au moment du dépôt et ne peut donc pas le valider alors.
        List<RankedBid> eligible = new ArrayList<>();
        List<BidOutcome> outcomes = new ArrayList<>();
        for (RankedBid bid : revealedBids) {
            if (bid.amount().compareTo(reservePrice) < 0) {
                outcomes.add(BidOutcome.rejected(bid.bidderId(), -1, bid.amount(), REASON_BELOW_RESERVE));
            } else {
                eligible.add(bid);
            }
        }
        eligible.sort(RANKING);

        // --- Affectation assortative -------------------------------------------------
        Map<String, Integer> remaining = new HashMap<>();
        for (Category c : byDesirability) {
            remaining.put(c.code(), c.capacity());
        }
        int totalRemaining = byDesirability.stream().mapToInt(Category::capacity).sum();

        Map<String, Category> assignment = new LinkedHashMap<>();
        Map<String, Integer> rankOf = new HashMap<>();

        for (int i = 0; i < eligible.size(); i++) {
            RankedBid bid = eligible.get(i);
            int rank = i + 1;
            rankOf.put(bid.bidderId(), rank);

            Category granted = null;
            for (Category c : byDesirability) {
                if (remaining.get(c.code()) > 0 && bid.accepts(c)) {
                    granted = c;
                    break;
                }
            }
            if (granted != null) {
                remaining.merge(granted.code(), -1, Integer::sum);
                totalRemaining--;
                assignment.put(bid.bidderId(), granted);
            } else {
                assignment.put(bid.bidderId(), null);
            }
        }

        // --- Prix de clôture par déplacement -----------------------------------------
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (Category c : byDesirability) {
            BigDecimal displaced = null;
            for (RankedBid bid : eligible) {
                if (!bid.accepts(c)) {
                    continue;
                }
                Category got = assignment.get(bid.bidderId());
                boolean strictlyWorseOrNothing = (got == null) || (got.rank() > c.rank());
                if (strictlyWorseOrNothing
                        && (displaced == null || bid.amount().compareTo(displaced) > 0)) {
                    displaced = bid.amount();
                }
            }
            BigDecimal price =
                    (displaced == null || displaced.compareTo(reservePrice) < 0)
                            ? reservePrice
                            : displaced;
            prices.put(c.code(), price);
        }
        enforceMonotonicity(byDesirability, prices);

        // --- Construction du résultat ------------------------------------------------
        BigDecimal revenue = BigDecimal.ZERO;
        int winners = 0;
        for (RankedBid bid : eligible) {
            int rank = rankOf.get(bid.bidderId());
            Category got = assignment.get(bid.bidderId());
            if (got == null) {
                String reason = (totalRemaining == 0) ? REASON_OUTBID : REASON_NO_ACCEPTABLE_CATEGORY;
                outcomes.add(BidOutcome.rejected(bid.bidderId(), rank, bid.amount(), reason));
            } else {
                BigDecimal price = prices.get(got.code());
                outcomes.add(BidOutcome.winner(bid.bidderId(), rank, bid.amount(), got, price));
                revenue = revenue.add(price);
                winners++;
            }
        }
        outcomes.sort(
                Comparator.comparingInt((BidOutcome o) -> o.rank() < 0 ? Integer.MAX_VALUE : o.rank())
                        .thenComparing(BidOutcome::bidderId));

        return new AllocationResult(prices, outcomes, revenue, winners, revealedBids.size());
    }

    /**
     * Garantit {@code p(meilleure) >= p(moins bonne)}.
     *
     * <p>Sous acceptation complète — le cas par défaut — la règle de déplacement produit cette
     * monotonie d'elle-même, puisque le prix de la catégorie {@code k} est la déclaration de rang
     * {@code cumul(k)+1} et que ce rang croît avec {@code k}. Des ensembles d'acceptation très
     * dissymétriques peuvent toutefois l'enfreindre, et une grille où la tribune coûterait plus
     * cher que le VIP serait indéfendable publiquement. On remonte donc les prix depuis la
     * catégorie la moins bonne vers la meilleure.
     */
    private void enforceMonotonicity(List<Category> byDesirability, Map<String, BigDecimal> prices) {
        for (int i = byDesirability.size() - 2; i >= 0; i--) {
            String better = byDesirability.get(i).code();
            String worse = byDesirability.get(i + 1).code();
            if (prices.get(better).compareTo(prices.get(worse)) < 0) {
                prices.put(better, prices.get(worse));
            }
        }
    }

    private void validateCategories(List<Category> categories) {
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("au moins une catégorie est requise");
        }
        long distinctRanks = categories.stream().map(Category::rank).distinct().count();
        if (distinctRanks != categories.size()) {
            throw new IllegalArgumentException(
                    "deux catégories partagent le même rang : l'ordre de désirabilité doit être total");
        }
        long distinctCodes = categories.stream().map(Category::code).distinct().count();
        if (distinctCodes != categories.size()) {
            throw new IllegalArgumentException("codes de catégorie dupliqués");
        }
    }
}
