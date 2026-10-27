package com.billetterie.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Code de billet rotatif, de type TOTP.
 *
 * <p>Corrige la faille de rejeu du QR code. Le principe violé par un QR signé statique est celui
 * de la <b>fraîcheur</b> : une signature prouve l'authenticité et l'intégrité, elle ne prouve ni
 * que le message est récent, ni qu'il n'a pas déjà servi. Un tel QR est un jeton au porteur, et
 * la copie est indiscernable de l'original : le premier arrivé entre, les autres sont refusés à
 * la porte, ce qui transforme une fraude en conflit physique au tourniquet.
 *
 * <p>Deux remèdes s'additionnent et sont tous deux nécessaires :
 *
 * <ul>
 *   <li><b>Fraîcheur</b> — ici : le code est dérivé du temps par pas de 30 secondes, régénéré
 *       dans l'application après déverrouillage biométrique. Une capture d'écran périme.</li>
 *   <li><b>Usage unique</b> — côté serveur : le billet est marqué consommé atomiquement au
 *       premier scan accepté (contrainte et transition d'état en base).</li>
 * </ul>
 *
 * <p>Une tolérance d'un pas de part et d'autre absorbe la dérive d'horloge du téléphone et le
 * délai de lecture, sans ouvrir une fenêtre de rejeu significative.
 */
public final class RotatingTicketCode {

    private static final String HMAC = "HmacSHA256";
    private static final long STEP_SECONDS = 30L;
    private static final int DIGITS = 9;
    private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

    /** Nombre de pas acceptés avant et après le pas courant. */
    private static final int DRIFT_TOLERANCE_STEPS = 1;

    private RotatingTicketCode() {}

    /** Génère le code valide à l'instant {@code epochSeconds}. Exécuté sur l'appareil. */
    public static String generate(byte[] totpSecret, String ticketId, long epochSeconds) {
        return compute(totpSecret, ticketId, epochSeconds / STEP_SECONDS);
    }

    /**
     * Vérifie un code présenté au tourniquet.
     *
     * <p>Comparaison à temps constant sur chaque pas candidat, pour ne pas exposer de canal
     * auxiliaire temporel. La vérification de fraîcheur ne remplace pas la consommation à usage
     * unique : les deux doivent être appliquées.
     */
    public static boolean verify(
            byte[] totpSecret, String ticketId, String presentedCode, long epochSeconds) {
        Objects.requireNonNull(presentedCode, "presentedCode");
        long currentStep = epochSeconds / STEP_SECONDS;
        boolean match = false;
        for (int delta = -DRIFT_TOLERANCE_STEPS; delta <= DRIFT_TOLERANCE_STEPS; delta++) {
            String candidate = compute(totpSecret, ticketId, currentStep + delta);
            // Pas de court-circuit : toutes les branches sont évaluées.
            if (MessageDigest.isEqual(
                    candidate.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    presentedCode.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                match = true;
            }
        }
        return match;
    }

    private static String compute(byte[] totpSecret, String ticketId, long step) {
        Objects.requireNonNull(totpSecret, "totpSecret");
        Objects.requireNonNull(ticketId, "ticketId");
        if (totpSecret.length < 20) {
            throw new IllegalArgumentException("graine trop courte : 20 octets minimum");
        }
        // L'identifiant du billet entre dans la charge : séparation de domaine, pour qu'un code
        // valide pour un billet ne puisse jamais être présenté pour un autre.
        byte[] payload =
                ByteBuffer.allocate(8 + ticketId.length())
                        .putLong(step)
                        .put(ticketId.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .array();
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(totpSecret, HMAC));
            byte[] h = mac.doFinal(payload);

            // Troncature dynamique, selon le principe de la RFC 4226.
            int offset = h[h.length - 1] & 0x0F;
            int binary =
                    ((h[offset] & 0x7F) << 24)
                            | ((h[offset + 1] & 0xFF) << 16)
                            | ((h[offset + 2] & 0xFF) << 8)
                            | (h[offset + 3] & 0xFF);
            int code = binary % POW10[DIGITS - 1];
            return String.format("%0" + DIGITS + "d", code);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("calcul du code de billet impossible", e);
        }
    }

    public static long stepSeconds() {
        return STEP_SECONDS;
    }
}
