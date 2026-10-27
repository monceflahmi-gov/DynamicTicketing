package com.billetterie.security;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pseudonymisation déterministe de la CIN.
 *
 * <p>Corrige la faille d'anonymisation. Un hash n'est pas un chiffrement : il ne protège que si
 * l'espace des entrées est trop vaste pour être parcouru. Le format de la CIN marocaine — une à
 * deux lettres puis cinq à six chiffres — donne un espace de l'ordre de 10^8 à 10^9 candidats,
 * soit environ 2^30, quand un mot de passe fort vit à 2^80. Un GPU calcule plusieurs milliards
 * de SHA-256 par seconde : l'espace entier se parcourt en quelques secondes.
 *
 * <p>Le sel ne sauve pas ce cas, et c'est le point contre-intuitif. Un sel par enregistrement
 * empêcherait les tables précalculées, mais le pseudonyme doit rester <b>déterministe</b> pour
 * garantir « une CIN, une déclaration ». Le sel est donc nécessairement global, et un sel global
 * vit dans le code ou la configuration : il tombe avec le serveur.
 *
 * <p>La sortie de l'impasse est conceptuelle : remplacer un secret <i>connu du système</i> par un
 * secret <i>inaccessible au système</i>. La clé HMAC réside dans un KMS ou un HSM. Le service
 * peut demander le calcul, jamais extraire la clé, donc jamais énumérer hors ligne. Le KMS limite
 * en outre le débit, ce qui rend l'énumération en ligne impraticable.
 */
public final class CinPseudonymizer {

    /** Format attendu : une à deux lettres suivies de cinq à six chiffres. */
    private static final Pattern CIN_FORMAT = Pattern.compile("^[A-Z]{1,2}\\d{5,6}$");

    private final KeyedMacProvider macProvider;
    private final String keyVersion;

    public CinPseudonymizer(KeyedMacProvider macProvider, String keyVersion) {
        this.macProvider = Objects.requireNonNull(macProvider, "macProvider");
        this.keyVersion = Objects.requireNonNull(keyVersion, "keyVersion");
    }

    /**
     * Calcule le pseudonyme. La CIN en clair ne doit jamais être écrite en base ni journalisée.
     *
     * <p>La version de clé est incluse dans la charge et stockée à côté du pseudonyme : une
     * rotation de clé reste possible sans perdre la capacité à réindexer, et deux pseudonymes
     * issus de versions différentes ne peuvent pas être confondus.
     */
    public Pseudonym pseudonymize(String rawCin) {
        Objects.requireNonNull(rawCin, "rawCin");
        String normalized = rawCin.trim().toUpperCase();
        if (!CIN_FORMAT.matcher(normalized).matches()) {
            // Le message ne reproduit jamais la valeur fournie : un journal d'erreurs ne doit pas
            // devenir un gisement de CIN en clair.
            throw new IllegalArgumentException("format de CIN invalide");
        }
        String payload = "CINv1|" + keyVersion + '|' + normalized;
        byte[] digest = macProvider.mac(payload.getBytes(StandardCharsets.UTF_8));
        return new Pseudonym(digest, keyVersion);
    }

    /** Pseudonyme et version de clé qui l'a produit. */
    public record Pseudonym(byte[] value, String keyVersion) {
        public Pseudonym {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(keyVersion, "keyVersion");
            if (value.length != 32) {
                throw new IllegalArgumentException("pseudonyme attendu sur 32 octets");
            }
            value = value.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }

        public String toHex() {
            return HexFormat.of().formatHex(value);
        }

        /** Évite qu'un pseudonyme ne fuite par journalisation accidentelle. */
        @Override
        public String toString() {
            return "Pseudonym[keyVersion=" + keyVersion + ", value=<masqué>]";
        }
    }

    /**
     * Abstraction du calculateur HMAC.
     *
     * <p>En production, l'implémentation délègue à AWS KMS, GCP KMS ou un HSM : la clé ne
     * traverse jamais le processus applicatif. L'implémentation locale n'existe que pour les
     * tests et doit être refusée par la configuration en production.
     */
    public interface KeyedMacProvider {
        byte[] mac(byte[] payload);
    }
}
