package com.billetterie.pricing.merkle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Arbre de Merkle conforme à la construction RFC 6962 (Certificate Transparency).
 *
 * <p>Deux détails de construction sont des choix de sécurité, non des détails d'implémentation :
 *
 * <ul>
 *   <li><b>Séparation de domaine.</b> Les feuilles sont préfixées par {@code 0x00} et les nœuds
 *       internes par {@code 0x01}. Sans cette séparation, un attaquant peut présenter un nœud
 *       interne comme s'il était une feuille et fabriquer une preuve d'inclusion valide pour une
 *       donnée qui n'a jamais été soumise. C'est une attaque classique par seconde préimage sur
 *       les arbres de Merkle.</li>
 *   <li><b>Pas de duplication du nœud orphelin.</b> Quand le nombre de feuilles est impair, la
 *       construction naïve duplique la dernière feuille ; deux ensembles de feuilles distincts
 *       produisent alors la même racine (CVE-2012-2459 dans Bitcoin). RFC 6962 découpe à la plus
 *       grande puissance de deux strictement inférieure à {@code n}, ce qui rend la racine
 *       injective pour un nombre de feuilles donné.</li>
 * </ul>
 *
 * <p>La racine est publiée et horodatée par un tiers <b>pendant</b> la fenêtre de déclaration.
 * C'est ce qui prive l'exploitant de la possibilité d'insérer une enchère fantôme après coup :
 * le problème phare de l'enchère de Vickrey.
 */
public final class MerkleTree {

    private static final byte[] LEAF_PREFIX = {0x00};
    private static final byte[] NODE_PREFIX = {0x01};

    private final List<byte[]> leaves;
    private final Map<Long, byte[]> subtreeCache = new HashMap<>();
    private final byte[] root;

    private MerkleTree(List<byte[]> leaves) {
        this.leaves = leaves;
        this.root = (leaves.isEmpty()) ? sha256(new byte[0]) : subtreeHash(0, leaves.size());
    }

    /**
     * Construit l'arbre sur les engagements, <b>dans l'ordre de séquence d'engagement</b>.
     *
     * <p>L'ordre est normatif : il doit être celui du {@code BIGSERIAL} attribué au dépôt, afin
     * que l'indice de la feuille d'un supporter soit stable et que sa preuve reste vérifiable.
     */
    public static MerkleTree of(List<byte[]> commitments) {
        Objects.requireNonNull(commitments, "commitments");
        List<byte[]> copy = new ArrayList<>(commitments.size());
        for (byte[] c : commitments) {
            copy.add(c.clone());
        }
        return new MerkleTree(copy);
    }

    public byte[] root() {
        return root.clone();
    }

    public String rootHex() {
        return HexFormat.of().formatHex(root);
    }

    public int size() {
        return leaves.size();
    }

    /** Chemin d'authentification de la feuille {@code index} : log2(n) condensés. */
    public InclusionProof proofFor(int index) {
        if (index < 0 || index >= leaves.size()) {
            throw new IndexOutOfBoundsException("indice de feuille hors bornes : " + index);
        }
        List<InclusionProof.Step> steps = new ArrayList<>();
        collectPath(index, 0, leaves.size(), steps);
        return new InclusionProof(index, leaves.size(), List.copyOf(steps));
    }

    private void collectPath(int target, int start, int end, List<InclusionProof.Step> steps) {
        if (end - start <= 1) {
            return;
        }
        int split = start + largestPowerOfTwoBelow(end - start);
        if (target < split) {
            collectPath(target, start, split, steps);
            steps.add(new InclusionProof.Step(subtreeHash(split, end), true));
        } else {
            collectPath(target, split, end, steps);
            steps.add(new InclusionProof.Step(subtreeHash(start, split), false));
        }
    }

    /**
     * Vérifie une preuve d'inclusion sans aucun accès au serveur.
     *
     * <p>C'est l'opération que le supporter exécute lui-même : il recalcule la racine depuis son
     * propre engagement et son chemin, puis la compare à celle qui a été publiée et horodatée.
     * On passe ainsi d'une confiance institutionnelle à une vérifiabilité individuelle.
     */
    public static boolean verify(byte[] commitment, InclusionProof proof, byte[] expectedRoot) {
        Objects.requireNonNull(commitment, "commitment");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(expectedRoot, "expectedRoot");

        byte[] running = hashLeaf(commitment);
        for (InclusionProof.Step step : proof.steps()) {
            running =
                    step.siblingIsRight()
                            ? hashNode(running, step.siblingHash())
                            : hashNode(step.siblingHash(), running);
        }
        return MessageDigest.isEqual(running, expectedRoot);
    }

    private byte[] subtreeHash(int start, int end) {
        long key = ((long) start << 32) | (end & 0xFFFFFFFFL);
        byte[] cached = subtreeCache.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] result;
        if (end - start == 1) {
            result = hashLeaf(leaves.get(start));
        } else {
            int split = start + largestPowerOfTwoBelow(end - start);
            result = hashNode(subtreeHash(start, split), subtreeHash(split, end));
        }
        subtreeCache.put(key, result);
        return result;
    }

    private static int largestPowerOfTwoBelow(int n) {
        if (n < 2) {
            throw new IllegalArgumentException("n doit être >= 2, reçu " + n);
        }
        return Integer.highestOneBit(n - 1);
    }

    private static byte[] hashLeaf(byte[] data) {
        return sha256(concat(LEAF_PREFIX, data));
    }

    private static byte[] hashNode(byte[] left, byte[] right) {
        return sha256(concat(concat(NODE_PREFIX, left), right));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /** Sérialisation compacte de la racine, destinée à la publication horodatée. */
    public String publishableStatement(String matchId, String isoInstant) {
        String payload = "MERKLE_ROOT|" + matchId + "|" + isoInstant + "|" + size() + "|" + rootHex();
        return payload
                + "|"
                + HexFormat.of().formatHex(sha256(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
