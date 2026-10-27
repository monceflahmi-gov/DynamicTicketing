package com.billetterie.pricing.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * Déclaration révélée, prête à être classée.
 *
 * <p><b>commitSequence</b> implémente la règle de priorité temporelle retenue : priorité au
 * premier ayant posé sa demande, et en cas de modification c'est la <b>dernière</b> modification
 * qui compte. C'est un entier strictement monotone attribué par la base ({@code BIGSERIAL}) au
 * moment de l'<b>engagement</b>, pas au moment de la révélation. Deux propriétés en découlent :
 *
 * <ul>
 *   <li>Il ne dépend d'aucune horloge applicative, donc il est immunisé contre la dérive NTP
 *       et contre tout recul d'horloge : il n'existe jamais deux déclarations de même rang.</li>
 *   <li>Une modification supprime l'engagement et en réinsère un nouveau, qui reçoit donc une
 *       séquence plus élevée. Cela <b>ferme la faille du sniping</b> : sans cette règle, chacun
 *       déposerait un engagement bidon à l'ouverture pour réserver sa priorité, puis le
 *       corrigerait à la dernière seconde en conservant son rang.</li>
 * </ul>
 *
 * <p><b>tieBreakHash</b> ne sert que de départage ultime et déterministe si deux déclarations
 * avaient le même montant ET la même séquence — impossible avec un BIGSERIAL, mais la garde
 * rend le tri total et donc le calcul reproductible à l'audit en toutes circonstances.
 *
 * <p><b>acceptableCategories</b> résout le problème de l'hétérogénéité descendante : un supporter
 * qui veut le virage avec les ultras et refuse la tribune VIP peut l'exprimer. Par défaut
 * l'ensemble vaut « toutes les catégories », ce qui correspond exactement au comportement
 * d'affectation assortative décrit dans la spécification.
 */
public record RankedBid(
        String bidderId,
        BigDecimal amount,
        long commitSequence,
        String tieBreakHash,
        Set<String> acceptableCategories) {

    public RankedBid {
        Objects.requireNonNull(bidderId, "bidderId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(tieBreakHash, "tieBreakHash");
        Objects.requireNonNull(acceptableCategories, "acceptableCategories");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("montant négatif pour " + bidderId);
        }
        if (commitSequence < 0) {
            throw new IllegalArgumentException("commitSequence négative pour " + bidderId);
        }
        acceptableCategories = Set.copyOf(acceptableCategories);
    }

    /** Déclaration acceptant toutes les catégories (cas par défaut). */
    public static RankedBid acceptingAll(
            String bidderId, BigDecimal amount, long commitSequence, String tieBreakHash) {
        return new RankedBid(bidderId, amount, commitSequence, tieBreakHash, Set.of());
    }

    /** Un ensemble vide signifie « aucune restriction », pas « rien n'est acceptable ». */
    public boolean accepts(Category category) {
        return acceptableCategories.isEmpty() || acceptableCategories.contains(category.code());
    }
}
