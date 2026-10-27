package com.billetterie.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Accès au journal d'engagements.
 *
 * <p>Toutes les requêtes de classement sont servies par l'index
 * {@code ix_bid_ranking (match_id, revealed_amount DESC, commit_seq ASC)}. Le tri n'est jamais
 * effectué en mémoire applicative : le moteur parcourt un index déjà ordonné.
 */
@Repository
public interface BidCommitmentRepository extends JpaRepository<BidCommitmentEntity, Long> {

    /**
     * Statistique d'ordre : la {@code (offset+1)}-ième déclaration la plus haute.
     *
     * <p>C'est la correction centrale de la faille de calcul. La version initiale chargeait
     * l'intégralité des déclarations en mémoire JVM — 200 000 entités Hibernate suivies par le
     * contexte de persistance, soit plusieurs centaines de mégaoctets plus le coût du
     * {@code dirty checking}, pour un pod limité à 512 Mo. Ici une seule valeur est transférée.
     *
     * <p>Appelée avec {@code offset = cumul(k)} pour obtenir le prix de déplacement de la
     * catégorie {@code k}. Avec une seule catégorie et {@code offset = N}, elle rend exactement
     * {@code b(N+1)}.
     *
     * @return la valeur, ou {@code null} si moins de {@code offset+1} déclarations valides
     */
    @Query(
            value =
                    """
                    SELECT b.revealed_amount
                    FROM bid_commitment b
                    WHERE b.match_id = :matchId
                      AND b.superseded_by IS NULL
                      AND b.reveal_status = 'VALID'
                    ORDER BY b.revealed_amount DESC, b.commit_seq ASC
                    LIMIT 1 OFFSET :offset
                    """,
            nativeQuery = true)
    BigDecimal findOrderStatistic(@Param("matchId") UUID matchId, @Param("offset") long offset);

    /**
     * Fenêtre bornée des déclarations les mieux classées.
     *
     * <p>Le moteur d'allocation a besoin des gagnants et de la zone frontière, jamais de la
     * totalité. {@code limit} vaut {@code N + marge}. Sous acceptation complète, le supporter
     * évincé d'une catégorie {@code k} se situe toujours au rang {@code cumul(k)+1 <= N+1},
     * donc une marge de 1 suffirait ; la marge est portée à quelques milliers pour couvrir les
     * ensembles d'acceptation dissymétriques.
     */
    @Query(
            value =
                    """
                    SELECT b.*
                    FROM bid_commitment b
                    WHERE b.match_id = :matchId
                      AND b.superseded_by IS NULL
                      AND b.reveal_status = 'VALID'
                    ORDER BY b.revealed_amount DESC, b.commit_seq ASC
                    LIMIT :limit
                    """,
            nativeQuery = true)
    List<BidCommitmentEntity> findTopRanked(
            @Param("matchId") UUID matchId, @Param("limit") int limit);

    /** Feuilles de l'arbre de Merkle, dans l'ordre normatif de séquence d'engagement. */
    @Query(
            value =
                    """
                    SELECT b.commitment
                    FROM bid_commitment b
                    WHERE b.match_id = :matchId
                    ORDER BY b.commit_seq ASC
                    """,
            nativeQuery = true)
    List<byte[]> findCommitmentsInSequenceOrder(@Param("matchId") UUID matchId);

    @Query(
            value =
                    """
                    SELECT count(*)
                    FROM bid_commitment b
                    WHERE b.match_id = :matchId
                      AND b.superseded_by IS NULL
                      AND b.reveal_status = 'VALID'
                    """,
            nativeQuery = true)
    long countValidReveals(@Param("matchId") UUID matchId);

    /**
     * Affectation par lots, sans charger aucun gagnant en mémoire.
     *
     * <p>Marque gagnantes toutes les déclarations strictement supérieures au prix de clôture.
     * Les ex æquo exactement au prix sont traités séparément par
     * {@link #markTiedWinnersByPriority}, parce que leur nombre peut dépasser les places
     * restantes et que la règle de priorité temporelle doit alors s'appliquer.
     */
    @Modifying
    @Query(
            value =
                    """
                    UPDATE bid_commitment
                    SET allocation_status = 'WINNER', allocated_category_id = :categoryId
                    WHERE match_id = :matchId
                      AND superseded_by IS NULL
                      AND reveal_status = 'VALID'
                      AND revealed_amount > :clearingPrice
                      AND allocation_status = 'PENDING'
                    """,
            nativeQuery = true)
    int markStrictWinners(
            @Param("matchId") UUID matchId,
            @Param("categoryId") UUID categoryId,
            @Param("clearingPrice") BigDecimal clearingPrice);

    /**
     * Départage des ex æquo exactement au prix de clôture.
     *
     * <p>Applique la règle retenue : priorité aux premiers ayant posé leur demande, une
     * modification valant nouvelle demande puisqu'elle a reçu une {@code commit_seq} plus
     * élevée. Le tri est total, donc le résultat est reproductible à l'audit.
     */
    @Modifying
    @Query(
            value =
                    """
                    UPDATE bid_commitment
                    SET allocation_status = 'WINNER', allocated_category_id = :categoryId
                    WHERE id IN (
                        SELECT b.id
                        FROM bid_commitment b
                        WHERE b.match_id = :matchId
                          AND b.superseded_by IS NULL
                          AND b.reveal_status = 'VALID'
                          AND b.revealed_amount = :clearingPrice
                          AND b.allocation_status = 'PENDING'
                        ORDER BY b.commit_seq ASC
                        LIMIT :remainingSeats
                    )
                    """,
            nativeQuery = true)
    int markTiedWinnersByPriority(
            @Param("matchId") UUID matchId,
            @Param("categoryId") UUID categoryId,
            @Param("clearingPrice") BigDecimal clearingPrice,
            @Param("remainingSeats") int remainingSeats);

    @Modifying
    @Query(
            value =
                    """
                    UPDATE bid_commitment
                    SET allocation_status = 'OUTBID'
                    WHERE match_id = :matchId
                      AND superseded_by IS NULL
                      AND allocation_status = 'PENDING'
                    """,
            nativeQuery = true)
    int markRemainingAsOutbid(@Param("matchId") UUID matchId);

    /** Gagnants d'un lot, pour la capture de paiement. Jamais plus de {@code Limit} à la fois. */
    @Query(
            value =
                    """
                    SELECT b.*
                    FROM bid_commitment b
                    WHERE b.match_id = :matchId
                      AND b.allocation_status = 'WINNER'
                      AND b.payment_captured = FALSE
                    ORDER BY b.commit_seq ASC
                    LIMIT :batchSize
                    """,
            nativeQuery = true)
    List<BidCommitmentEntity> findNextCaptureBatch(
            @Param("matchId") UUID matchId, @Param("batchSize") int batchSize);
}
