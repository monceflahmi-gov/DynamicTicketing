package com.billetterie.pricing.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Résultat complet et reproductible d'une clôture.
 *
 * <p>L'ensemble est une fonction pure des déclarations révélées, de la grille des catégories et
 * du prix de réserve. Rejouer le calcul sur les mêmes entrées redonne rigoureusement le même
 * résultat, ce qui est la condition pour pouvoir le défendre devant une contestation.
 */
public record AllocationResult(
        Map<String, BigDecimal> clearingPrices,
        List<BidOutcome> outcomes,
        BigDecimal totalRevenue,
        int winnerCount,
        int bidCount) {

    public AllocationResult {
        clearingPrices = Map.copyOf(clearingPrices);
        outcomes = List.copyOf(outcomes);
    }

    public BigDecimal priceOf(String categoryCode) {
        BigDecimal p = clearingPrices.get(categoryCode);
        if (p == null) {
            throw new IllegalArgumentException("catégorie inconnue : " + categoryCode);
        }
        return p;
    }

    public List<BidOutcome> winners() {
        return outcomes.stream().filter(BidOutcome::isWinner).toList();
    }

    public List<BidOutcome> losers() {
        return outcomes.stream().filter(o -> !o.isWinner()).toList();
    }

    public Map<String, BigDecimal> clearingPricesSorted() {
        return Collections.unmodifiableMap(new java.util.TreeMap<>(clearingPrices));
    }
}
