package com.billetterie.pricing.domain;

import java.util.Objects;

/**
 * Catégorie de places du pool dynamique.
 *
 * <p>L'ordre de désirabilité est <b>publié à l'avance</b> et matérialisé par {@code rank} :
 * 1 = meilleure catégorie (VIP), puis 2, 3, ... Cet ordre est une donnée du match, jamais
 * une inférence faite au moment du calcul : un supporter doit pouvoir vérifier la règle
 * d'affectation avant de soumettre sa déclaration.
 *
 * <p>{@code capacity} est la capacité <b>du pool dynamique</b> pour cette catégorie, c'est-à-dire
 * après retrait de la zone abonnés et après réintégration des places abonnés non confirmées
 * à l'échéance de confirmation (voir {@code SubscriberZoneReconciler}).
 */
public record Category(String code, int rank, int capacity) implements Comparable<Category> {

    public Category {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code de catégorie vide");
        }
        if (rank < 1) {
            throw new IllegalArgumentException("rank doit être >= 1 (1 = meilleure catégorie)");
        }
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity doit être >= 0");
        }
    }

    /** Vrai si cette catégorie est strictement meilleure que {@code other}. */
    public boolean isBetterThan(Category other) {
        return this.rank < other.rank;
    }

    @Override
    public int compareTo(Category other) {
        return Integer.compare(this.rank, other.rank);
    }
}
