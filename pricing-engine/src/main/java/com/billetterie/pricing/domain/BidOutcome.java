package com.billetterie.pricing.domain;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Sort d'une déclaration : rang obtenu, catégorie affectée et prix réellement dû.
 *
 * <p>{@code pricePaid} est le prix de clôture <b>de la catégorie affectée</b>, jamais le montant
 * déclaré par l'intéressé. C'est la séparation de Vickrey : la déclaration décide si l'on est
 * servi et dans quelle catégorie, elle ne décide jamais du montant payé.
 */
public record BidOutcome(
        String bidderId,
        int rank,
        BigDecimal declaredAmount,
        Category assignedCategory,
        BigDecimal pricePaid,
        String reasonIfRejected) {

    public boolean isWinner() {
        return assignedCategory != null;
    }

    public Optional<Category> category() {
        return Optional.ofNullable(assignedCategory);
    }

    public static BidOutcome winner(
            String bidderId, int rank, BigDecimal declared, Category category, BigDecimal price) {
        return new BidOutcome(bidderId, rank, declared, category, price, null);
    }

    public static BidOutcome rejected(
            String bidderId, int rank, BigDecimal declared, String reason) {
        return new BidOutcome(bidderId, rank, declared, null, BigDecimal.ZERO, reason);
    }
}
