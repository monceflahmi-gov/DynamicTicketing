package com.billetterie.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Garde d'idempotence pour les opérations de paiement.
 *
 * <p>Corrige la faille du double débit. Kafka garantit une livraison « au moins une fois » : si
 * l'accusé de réception est perdu après que le PSP a exécuté le débit, le message est rejoué et
 * le supporter est débité deux fois. La garantie « exactement une fois » n'existe pas au niveau
 * du transport ; elle s'obtient en rendant le <b>traitement</b> idempotent.
 *
 * <p>Deux exigences de conception, souvent manquées :
 *
 * <ul>
 *   <li><b>La clé est persistée en base, pas dans Redis.</b> Redis est volatil par défaut : un
 *       redémarrage ou une éviction perd la clé et rouvre la fenêtre de double débit.</li>
 *   <li><b>L'insertion de la clé et l'effet métier partagent la même transaction.</b> Insérer la
 *       clé avant ou après, dans une transaction distincte, laisse une fenêtre où l'un des deux
 *       existe sans l'autre, et la garantie devient illusoire.</li>
 * </ul>
 *
 * <p>La même clé est transmise au PSP, de sorte que la protection vaille aussi pour un appel
 * sortant rejoué par la couche réseau.
 */
@Service
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final IdempotencyRecordRepository records;

    public IdempotencyGuard(IdempotencyRecordRepository records) {
        this.records = records;
    }

    /**
     * Exécute {@code action} au plus une fois pour une clé donnée.
     *
     * <p>{@link Propagation#REQUIRED} est délibéré : l'appel doit s'inscrire dans la transaction
     * de l'appelant, pour que l'enregistrement de la clé et l'effet métier soient validés ou
     * annulés ensemble.
     *
     * @param key clé stable dérivée de l'opération métier, jamais un UUID aléatoire par tentative
     * @param requestPayload charge de la requête, dont on conserve un condensé
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> IdempotentOutcome<T> runOnce(
            String key, Operation operation, String requestPayload, Supplier<T> action) {

        byte[] digest = sha256(requestPayload);

        Optional<IdempotencyRecordEntity> existing = records.findById(key);
        if (existing.isPresent()) {
            IdempotencyRecordEntity record = existing.get();
            // Même clé, charge différente : ce n'est pas un rejeu, c'est un défaut appelant.
            // Le masquer produirait une opération silencieusement ignorée.
            if (!MessageDigest.isEqual(record.getRequestDigest(), digest)) {
                throw new IdempotencyConflictException(
                        "clé d'idempotence " + key + " déjà utilisée avec une charge différente");
            }
            log.info("Rejeu détecté sur la clé {} — aucune action réexécutée", key);
            return IdempotentOutcome.replayed(record.getResponsePayload());
        }

        try {
            // L'insertion précède l'effet dans la même transaction : deux exécutions concurrentes
            // se neutralisent sur la contrainte de clé primaire, et non sur une relecture.
            records.insertNew(key, operation.name(), digest);
            T result = action.get();
            records.attachResponse(key, String.valueOf(result));
            return IdempotentOutcome.executed(result);
        } catch (DataIntegrityViolationException e) {
            // Course perdue : une transaction concurrente a inséré la clé entre-temps.
            // L'effet a été ou sera appliqué exactement une fois par cette autre transaction.
            log.info("Course sur la clé {} — l'autre transaction fait foi", key);
            return IdempotentOutcome.replayed(null);
        }
    }

    /**
     * Construit une clé stable pour la capture d'un gagnant.
     *
     * <p>La clé est dérivée du match, de l'engagement et du prix de clôture. Elle ne contient
     * aucun aléa et aucun horodatage : deux tentatives de la même capture doivent produire la
     * même clé, sinon la garde ne sert à rien.
     */
    public static String captureKey(String matchId, long commitSeq, String clearingPrice) {
        return "CAPTURE|" + matchId + '|' + commitSeq + '|' + clearingPrice;
    }

    public static String releaseKey(String matchId, long commitSeq) {
        return "RELEASE|" + matchId + '|' + commitSeq;
    }

    public static String ticketKey(String matchId, long commitSeq) {
        return "TICKET|" + matchId + '|' + commitSeq;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    public enum Operation {
        CAPTURE_PAYMENT,
        RELEASE_AUTH,
        ISSUE_TICKET,
        REFUND
    }

    /** Résultat, en distinguant une exécution réelle d'un rejeu neutralisé. */
    public record IdempotentOutcome<T>(T value, boolean wasReplay, String storedResponse) {
        static <T> IdempotentOutcome<T> executed(T value) {
            return new IdempotentOutcome<>(value, false, null);
        }

        static <T> IdempotentOutcome<T> replayed(String storedResponse) {
            return new IdempotentOutcome<>(null, true, storedResponse);
        }
    }

    /** Même clé, charge différente : défaut appelant, jamais silencieux. */
    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String message) {
            super(message);
        }
    }
}
