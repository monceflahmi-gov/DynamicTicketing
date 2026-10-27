package com.billetterie.pricing.commit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Schéma d'engagement et de révélation (commit-reveal) pour une déclaration scellée.
 *
 * <p>Corrige la faille de confidentialité majeure : dans l'architecture initiale, les montants
 * étaient en clair dans PostgreSQL. Une enchère « sous pli scellé » lisible par le DBA n'est pas
 * scellée, et l'hypothèse qui fait tenir la preuve de Vickrey est violée pour tout l'intérieur du
 * système. TLS protège le transport, pas le repos.
 *
 * <h2>Phase 1 — Engagement, pendant la fenêtre</h2>
 *
 * Le client tire un aléa {@code nonce} de 256 bits et calcule
 * {@code c = HMAC-SHA256(nonce, charge canonique)}. Seul {@code c} est transmis et stocké.
 *
 * <ul>
 *   <li><b>Dissimulation</b> — {@code c} ne révèle rien du montant : l'entropie du nonce rend
 *       l'espace des préimages impossible à énumérer. Sans nonce, {@code H(450)} serait
 *       trivialement inversible, les montants plausibles en MAD ne comptant que quelques
 *       milliers de valeurs.</li>
 *   <li><b>Liaison</b> — le déclarant ne peut plus modifier son montant après coup sans exhiber
 *       une collision sur HMAC-SHA256.</li>
 * </ul>
 *
 * <h2>Phase 2 — Révélation, après la clôture</h2>
 *
 * Le client publie {@code (montant, nonce)} ; le serveur recalcule et compare. Une révélation qui
 * ne correspond pas à l'engagement est rejetée et la caution est saisie, sinon l'abstention de
 * révélation deviendrait un levier de manipulation du prix de clôture.
 *
 * <h2>Pourquoi matchId et bidderId sont dans la charge</h2>
 *
 * Séparation de domaine. Sans eux, un engagement valide pour un match pourrait être rejoué sur un
 * autre match ou revendiqué par un autre compte. Les catégories acceptées y figurent également :
 * sinon un déclarant pourrait modifier son ensemble d'acceptation au moment de la révélation pour
 * se repositionner dans l'affectation, une fois la concurrence connue.
 */
public final class BidCommitment {

    private static final String HMAC = "HmacSHA256";
    private static final int NONCE_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BidCommitment() {}

    /** Charge canonique : toute ambiguïté de sérialisation ferait échouer des révélations licites. */
    public static String canonicalPayload(
            String matchId, String bidderId, BigDecimal amount, Set<String> acceptableCategories) {
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(bidderId, "bidderId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(acceptableCategories, "acceptableCategories");

        // Échelle fixée à 2 décimales et mode UNNECESSARY : 500 et 500.00 produisent la même
        // chaîne, tandis qu'un montant plus précis que le centime est rejeté à la source.
        String canonicalAmount = amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        String canonicalCategories = String.join(",", new TreeSet<>(acceptableCategories));

        return "BIDv1|" + matchId + '|' + bidderId + '|' + canonicalAmount + '|' + canonicalCategories;
    }

    public static byte[] newNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    /** Calcule l'engagement. Exécuté côté client ; le serveur ne le reproduit qu'à la révélation. */
    public static byte[] commit(
            byte[] nonce,
            String matchId,
            String bidderId,
            BigDecimal amount,
            Set<String> acceptableCategories) {
        Objects.requireNonNull(nonce, "nonce");
        if (nonce.length < NONCE_BYTES) {
            throw new IllegalArgumentException(
                    "nonce trop court : " + nonce.length + " octets, " + NONCE_BYTES + " requis");
        }
        String payload = canonicalPayload(matchId, bidderId, amount, acceptableCategories);
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(nonce, HMAC));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("calcul de l'engagement impossible", e);
        }
    }

    /**
     * Vérifie une révélation contre l'engagement stocké.
     *
     * <p>La comparaison passe par {@link MessageDigest#isEqual} : à temps constant, donc sans
     * canal auxiliaire temporel exploitable.
     */
    public static boolean verifyReveal(
            byte[] storedCommitment,
            byte[] revealedNonce,
            String matchId,
            String bidderId,
            BigDecimal revealedAmount,
            Set<String> revealedCategories) {
        Objects.requireNonNull(storedCommitment, "storedCommitment");
        byte[] recomputed =
                commit(revealedNonce, matchId, bidderId, revealedAmount, revealedCategories);
        return MessageDigest.isEqual(storedCommitment, recomputed);
    }

    public static String toHex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    public static byte[] fromHex(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
