package com.billetterie.pricing.merkle;

import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Preuve d'inclusion remise au supporter au moment de son engagement.
 *
 * <p>Taille : {@code log2(M)} condensés. Pour 200 000 déclarations, 18 condensés, soit 576 octets.
 * Chaque supporter peut donc vérifier seul que sa déclaration figure dans le décompte final,
 * sans voir aucune autre déclaration et sans faire confiance à l'exploitant.
 */
public record InclusionProof(int leafIndex, int treeSize, List<Step> steps) {

    public InclusionProof {
        if (leafIndex < 0) {
            throw new IllegalArgumentException("leafIndex négatif");
        }
        if (treeSize <= leafIndex) {
            throw new IllegalArgumentException("treeSize incohérent avec leafIndex");
        }
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
    }

    /**
     * Un cran de remontée dans l'arbre.
     *
     * @param siblingHash condensé du nœud frère
     * @param siblingIsRight vrai si le frère est à droite, donc si le condensé courant est à gauche
     */
    public record Step(byte[] siblingHash, boolean siblingIsRight) {
        public Step {
            Objects.requireNonNull(siblingHash, "siblingHash");
            siblingHash = siblingHash.clone();
        }

        @Override
        public byte[] siblingHash() {
            return siblingHash.clone();
        }

        public String siblingHex() {
            return HexFormat.of().formatHex(siblingHash);
        }
    }

    /** Forme compacte transmissible au client, pour vérification hors ligne. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(leafIndex).append(':').append(treeSize);
        for (Step s : steps) {
            sb.append('|').append(s.siblingIsRight() ? 'R' : 'L').append(s.siblingHex());
        }
        return sb.toString();
    }
}
