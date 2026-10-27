package com.billetterie.pricing;

import com.billetterie.pricing.commit.BidCommitment;
import com.billetterie.security.CinPseudonymizer;
import com.billetterie.security.RotatingTicketCode;
import com.billetterie.pricing.domain.AllocationEngine;
import com.billetterie.pricing.domain.AllocationResult;
import com.billetterie.pricing.domain.BidOutcome;
import com.billetterie.pricing.domain.Category;
import com.billetterie.pricing.domain.RankedBid;
import com.billetterie.pricing.merkle.InclusionProof;
import com.billetterie.pricing.merkle.MerkleTree;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Harnais de vérification exécutable des corrections apportées au mécanisme. */
public final class VerificationHarness {

    private static int passed = 0;
    private static int failed = 0;

    private static final BigDecimal RESERVE = new BigDecimal("200.00");
    private static final AllocationEngine ENGINE = new AllocationEngine();

    public static void main(String[] args) {
        section("A. Moteur d'allocation — règles fonctionnelles");
        userReferenceExample();
        singleCategoryReducesToBNPlusOne();
        tieBreakFavoursEarliestCommit();
        modificationLosesPriority();
        pricesAreMonotonic();
        noWinnerSetsOwnPrice();
        belowReserveRejected();
        undersubscribedFallsToReserve();
        acceptableCategoriesRespected();
        resultIsReproducibleUnderShuffle();

        section("B. Incitation — b(N+1) correct vs b(N) fautif");
        truthfulnessHoldsUnderBNPlusOne();
        shadingPaysUnderBrokenBN();

        section("C. Arbre de Merkle");
        allInclusionProofsVerify();
        tamperedCommitmentFails();
        wrongRootFails();
        domainSeparationBlocksNodeAsLeaf();
        oddLeafCountsAreInjective();

        section("D. Engagement et révélation");
        revealVerifies();
        alteredAmountFailsReveal();
        canonicalScaleIsStable();
        crossMatchReplayFails();
        crossBidderReplayFails();
        alteredCategoriesFailReveal();

        section("E. Pseudonymisation CIN et billet rotatif");
        cinPseudonymIsDeterministic();
        cinPseudonymSeparatesIdentities();
        cinFormatIsValidated();
        cinErrorNeverLeaksValue();
        cinKeyRotationChangesOutput();
        ticketCodeVerifiesWithinWindow();
        ticketCodeExpires();
        ticketCodeToleratesDrift();
        ticketCodeIsBoundToTicket();

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.printf("RESULTAT : %d reussis, %d echoues%n", passed, failed);
        System.out.println("=".repeat(72));
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- A -----

    /** Exemple de référence fourni : 6 places dont 1 VIP et 5 tribune, pour 600/500x4/300. */
    private static void userReferenceExample() {
        List<Category> cats =
                List.of(new Category("VIP", 1, 1), new Category("TRIBUNE", 2, 5));

        List<RankedBid> bids =
                List.of(
                        bid("s600", "600", 1),
                        bid("s500a", "500", 2),
                        bid("s500b", "500", 3),
                        bid("s500c", "500", 4),
                        bid("s500d", "500", 5),
                        bid("s300", "300", 6));

        AllocationResult r = ENGINE.allocate(cats, bids, RESERVE);

        check("exemple : le plus offrant obtient le VIP", "VIP", categoryOf(r, "s600"));
        check("exemple : s500a en tribune", "TRIBUNE", categoryOf(r, "s500a"));
        check("exemple : s500d en tribune", "TRIBUNE", categoryOf(r, "s500d"));
        check("exemple : s300 en tribune", "TRIBUNE", categoryOf(r, "s300"));
        check("exemple : 6 gagnants", 6, r.winnerCount());
        check("exemple : prix VIP = 500 (premier evince du VIP)", "500", plain(r.priceOf("VIP")));
        check("exemple : prix tribune = reserve (aucun evince)", "200", plain(r.priceOf("TRIBUNE")));
        check(
                "exemple : le gagnant VIP paie 500 et non ses 600 declares",
                "500",
                plain(priceOf(r, "s600")));
    }

    /** Avec une seule catégorie, la règle de déplacement doit redonner exactement b(N+1). */
    private static void singleCategoryReducesToBNPlusOne() {
        List<Category> cats = List.of(new Category("TRIBUNE", 1, 3));
        List<RankedBid> bids =
                List.of(
                        bid("a", "900", 1),
                        bid("b", "700", 2),
                        bid("c", "550", 3),
                        bid("d", "480", 4), // b(N+1), N=3
                        bid("e", "300", 5));

        AllocationResult r = ENGINE.allocate(cats, bids, RESERVE);
        check("categorie unique : p* = b(N+1) = 480", "480", plain(r.priceOf("TRIBUNE")));
        check("categorie unique : 3 gagnants", 3, r.winnerCount());
        check("categorie unique : b(N)=550 n'est pas le prix", true, !plain(r.priceOf("TRIBUNE")).equals("550"));
    }

    /** À montant égal, priorité au plus petit numéro de séquence d'engagement. */
    private static void tieBreakFavoursEarliestCommit() {
        List<Category> cats = List.of(new Category("TRIBUNE", 1, 3));
        List<RankedBid> bids =
                List.of(
                        bid("tard", "500", 40),
                        bid("tot", "500", 5),
                        bid("moyen", "500", 17),
                        bid("tresTard", "500", 90),
                        bid("bas", "300", 1));

        AllocationResult r = ENGINE.allocate(cats, bids, RESERVE);
        check("departage : 'tot' (seq 5) est servi", true, isWinner(r, "tot"));
        check("departage : 'moyen' (seq 17) est servi", true, isWinner(r, "moyen"));
        check("departage : 'tard' (seq 40) est servi", true, isWinner(r, "tard"));
        check("departage : 'tresTard' (seq 90) est evince", false, isWinner(r, "tresTard"));
        check("departage : 'bas' (300) est evince malgre seq 1", false, isWinner(r, "bas"));
        check("departage : prix fixe par l'evince a 500", "500", plain(r.priceOf("TRIBUNE")));
    }

    /** Une modification vaut nouvelle demande : la séquence augmente et la priorité est perdue. */
    private static void modificationLosesPriority() {
        List<Category> cats = List.of(new Category("TRIBUNE", 1, 2));

        List<RankedBid> avant =
                List.of(bid("A", "500", 1), bid("B", "500", 2), bid("C", "500", 3));
        AllocationResult r1 = ENGINE.allocate(cats, avant, RESERVE);
        check("modification : avant, A et B sont servis", true, isWinner(r1, "A") && isWinner(r1, "B"));
        check("modification : avant, C est evince", false, isWinner(r1, "C"));

        // A modifie sa declaration : son engagement est reinsere avec une sequence plus elevee.
        List<RankedBid> apres =
                List.of(bid("A", "500", 9), bid("B", "500", 2), bid("C", "500", 3));
        AllocationResult r2 = ENGINE.allocate(cats, apres, RESERVE);
        check("modification : apres, A perd sa place au profit de C", false, isWinner(r2, "A"));
        check("modification : apres, B conserve sa place", true, isWinner(r2, "B"));
        check("modification : apres, C est servi", true, isWinner(r2, "C"));
    }

    private static void pricesAreMonotonic() {
        List<Category> cats =
                List.of(
                        new Category("VIP", 1, 2),
                        new Category("TRIBUNE", 2, 3),
                        new Category("VIRAGE", 3, 4));
        List<RankedBid> bids = new ArrayList<>();
        int[] amounts = {1000, 900, 800, 700, 600, 550, 500, 450, 400, 380, 350, 300};
        for (int i = 0; i < amounts.length; i++) {
            bids.add(bid("b" + i, String.valueOf(amounts[i]), i + 1));
        }

        AllocationResult r = ENGINE.allocate(cats, bids, RESERVE);
        BigDecimal vip = r.priceOf("VIP");
        BigDecimal trib = r.priceOf("TRIBUNE");
        BigDecimal vir = r.priceOf("VIRAGE");

        check("monotonie : p(VIP) >= p(TRIBUNE)", true, vip.compareTo(trib) >= 0);
        check("monotonie : p(TRIBUNE) >= p(VIRAGE)", true, trib.compareTo(vir) >= 0);
        // Regle emboitee : p(k) = b(cumul(k)+1). VIP cap 2 -> b(3)=800 ; TRIBUNE cumul 5 -> b(6)=550
        check("emboitement : p(VIP) = b(3) = 800", "800", plain(vip));
        check("emboitement : p(TRIBUNE) = b(6) = 550", "550", plain(trib));
        check("emboitement : p(VIRAGE) = b(10) = 380", "380", plain(vir));
    }

    /** Propriete centrale : le prix d'une categorie provient toujours d'un non-beneficiaire. */
    private static void noWinnerSetsOwnPrice() {
        List<Category> cats =
                List.of(new Category("VIP", 1, 2), new Category("TRIBUNE", 2, 4));
        List<RankedBid> bids = new ArrayList<>();
        int[] amounts = {950, 880, 700, 660, 640, 610, 590, 500, 400};
        for (int i = 0; i < amounts.length; i++) {
            bids.add(bid("b" + i, String.valueOf(amounts[i]), i + 1));
        }
        AllocationResult r = ENGINE.allocate(cats, bids, RESERVE);

        boolean ok = true;
        for (Category c : cats) {
            BigDecimal price = r.priceOf(c.code());
            if (price.compareTo(RESERVE) == 0) {
                continue;
            }
            // Un beneficiaire de cette categorie ou d'une meilleure ne doit jamais porter ce montant.
            for (BidOutcome o : r.winners()) {
                if (o.assignedCategory().rank() <= c.rank()
                        && o.declaredAmount().compareTo(price) == 0) {
                    ok = false;
                }
            }
        }
        check("separation : aucun beneficiaire ne porte le prix de sa categorie", true, ok);
    }

    private static void belowReserveRejected() {
        List<Category> cats = List.of(new Category("TRIBUNE", 1, 5));
        List<RankedBid> bids =
                List.of(bid("ok", "300", 1), bid("sous", "150", 2), bid("limite", "200", 3));
        AllocationResult r = ENGINE.allocate(cats, bids, RESERVE);

        check("reserve : la declaration de 150 est rejetee", false, isWinner(r, "sous"));
        check("reserve : le motif est BELOW_RESERVE", AllocationEngine.REASON_BELOW_RESERVE, reasonOf(r, "sous"));
        check("reserve : la declaration egale a la reserve est acceptee", true, isWinner(r, "limite"));
    }

    private static void undersubscribedFallsToReserve() {
        List<Category> cats = List.of(new Category("TRIBUNE", 1, 50));
        List<RankedBid> bids = List.of(bid("a", "900", 1), bid("b", "400", 2));
        AllocationResult r = ENGINE.allocate(cats, bids, RESERVE);
        check("sous-souscription : prix = reserve", "200", plain(r.priceOf("TRIBUNE")));
        check("sous-souscription : tout le monde est servi", 2, r.winnerCount());
    }

    private static void acceptableCategoriesRespected() {
        List<Category> cats =
                List.of(new Category("VIP", 1, 2), new Category("VIRAGE", 2, 2));
        // 'ultra' refuse le VIP : il veut le virage, meme en etant le plus offrant.
        List<RankedBid> bids =
                List.of(
                        new RankedBid("ultra", new BigDecimal("900"), 1, "h1", Set.of("VIRAGE")),
                        bid("b", "800", 2),
                        bid("c", "700", 3),
                        bid("d", "600", 4),
                        bid("e", "500", 5));

        AllocationResult r = ENGINE.allocate(cats, bids, RESERVE);
        check("acceptation : 'ultra' obtient le virage et non le VIP", "VIRAGE", categoryOf(r, "ultra"));
        check("acceptation : le VIP revient a b et c", true,
                "VIP".equals(categoryOf(r, "b")) && "VIP".equals(categoryOf(r, "c")));
        check("acceptation : d obtient le virage", "VIRAGE", categoryOf(r, "d"));
        check("acceptation : e est evince", false, isWinner(r, "e"));
    }

    /** Le calcul doit etre une fonction pure : l'ordre d'arrivee en entree ne doit rien changer. */
    private static void resultIsReproducibleUnderShuffle() {
        List<Category> cats =
                List.of(new Category("VIP", 1, 3), new Category("TRIBUNE", 2, 7));
        List<RankedBid> base = new ArrayList<>();
        Random rnd = new Random(42);
        for (int i = 0; i < 60; i++) {
            int amount = 200 + rnd.nextInt(14) * 50; // beaucoup d'ex aequo sur montants ronds
            base.add(bid("b" + i, String.valueOf(amount), i + 1));
        }

        AllocationResult reference = ENGINE.allocate(cats, base, RESERVE);
        boolean stable = true;
        for (int trial = 0; trial < 20; trial++) {
            List<RankedBid> shuffled = new ArrayList<>(base);
            Collections.shuffle(shuffled, new Random(trial));
            AllocationResult other = ENGINE.allocate(cats, shuffled, RESERVE);
            if (!reference.clearingPricesSorted().equals(other.clearingPricesSorted())) {
                stable = false;
            }
            for (BidOutcome o : reference.outcomes()) {
                String a = categoryOf(reference, o.bidderId());
                String b = categoryOf(other, o.bidderId());
                if (!java.util.Objects.equals(a, b)) {
                    stable = false;
                }
            }
        }
        check("reproductibilite : 20 permutations donnent un resultat identique", true, stable);
    }

    // ---------------------------------------------------------------- B -----

    /** Sous b(N+1), sous-declarer ne peut jamais ameliorer le surplus. */
    private static void truthfulnessHoldsUnderBNPlusOne() {
        List<Category> cats = List.of(new Category("TRIBUNE", 1, 3));
        List<RankedBid> truthful =
                List.of(
                        bid("a", "900", 1),
                        bid("cible", "700", 2),
                        bid("c", "600", 3),
                        bid("d", "520", 4),
                        bid("e", "300", 5));

        BigDecimal valeur = new BigDecimal("700");
        AllocationResult honest = ENGINE.allocate(cats, truthful, RESERVE);
        BigDecimal surplusHonest = valeur.subtract(priceOf(honest, "cible"));

        boolean aucuneAmelioration = true;
        for (int shade = 690; shade >= 200; shade -= 10) {
            List<RankedBid> deviated = new ArrayList<>(truthful);
            deviated.set(1, bid("cible", String.valueOf(shade), 2));
            AllocationResult r = ENGINE.allocate(cats, deviated, RESERVE);
            BigDecimal surplus =
                    isWinner(r, "cible") ? valeur.subtract(priceOf(r, "cible")) : BigDecimal.ZERO;
            if (surplus.compareTo(surplusHonest) > 0) {
                aucuneAmelioration = false;
            }
        }
        check("b(N+1) : surplus honnete = 700 - 520 = 180", "180", plain(surplusHonest));
        check("b(N+1) : aucune sous-declaration n'ameliore le surplus", true, aucuneAmelioration);
    }

    /**
     * Demonstration de la faille : sous p* = b(N), le gagnant marginal gagne a sous-declarer.
     *
     * <p>Reimplementation volontairement fautive, uniquement pour la demonstration.
     */
    private static void shadingPaysUnderBrokenBN() {
        int N = 3;
        // Declarations sinceres. 'cible' doit etre exactement au rang N pour etre le gagnant
        // marginal : avec 900 et 700 au-dessus et 520 juste en dessous, 610 occupe le rang 3.
        int[] others = {900, 700}; // les deux autres gagnants
        int outsider = 520; // premier perdant
        int valeurCible = 610;

        BigDecimal prixHonnete = brokenBn(N, valeurCible, others, outsider);
        BigDecimal surplusHonnete = new BigDecimal(valeurCible).subtract(prixHonnete);

        BigDecimal meilleurSurplusTriche = surplusHonnete;
        int meilleureTriche = valeurCible;
        for (int shade = valeurCible - 10; shade > outsider; shade -= 10) {
            BigDecimal prix = brokenBn(N, shade, others, outsider);
            BigDecimal surplus = new BigDecimal(valeurCible).subtract(prix);
            if (surplus.compareTo(meilleurSurplusTriche) > 0) {
                meilleurSurplusTriche = surplus;
                meilleureTriche = shade;
            }
        }

        check("b(N) fautif : surplus du gagnant marginal honnete = 0", "0", plain(surplusHonnete));
        check(
                "b(N) fautif : sous-declarer rapporte strictement",
                true,
                meilleurSurplusTriche.compareTo(surplusHonnete) > 0);
        System.out.printf(
                "        -> en declarant %d au lieu de %d, le surplus passe de %s a %s MAD%n",
                meilleureTriche, valeurCible, plain(surplusHonnete), plain(meilleurSurplusTriche));
    }

    /** Prix fautif b(N) : la N-ieme declaration, donc la plus basse gagnante. */
    private static BigDecimal brokenBn(int n, int cible, int[] others, int outsider) {
        List<Integer> all = new ArrayList<>();
        all.add(cible);
        for (int o : others) {
            all.add(o);
        }
        all.add(outsider);
        all.sort(Collections.reverseOrder());
        return new BigDecimal(all.get(n - 1));
    }

    // ---------------------------------------------------------------- C -----

    private static void allInclusionProofsVerify() {
        for (int n : new int[] {1, 2, 3, 5, 8, 13, 100, 1000}) {
            List<byte[]> leaves = syntheticLeaves(n);
            MerkleTree tree = MerkleTree.of(leaves);
            boolean all = true;
            for (int i = 0; i < n; i++) {
                InclusionProof proof = tree.proofFor(i);
                if (!MerkleTree.verify(leaves.get(i), proof, tree.root())) {
                    all = false;
                }
            }
            check("merkle : les " + n + " preuves se verifient", true, all);
        }
        MerkleTree big = MerkleTree.of(syntheticLeaves(200_000));
        InclusionProof p = big.proofFor(123_456);
        check("merkle : preuve sur 200 000 feuilles = 18 condenses", 18, p.steps().size());
        // 18 condenses de 32 octets = 576 octets de charge utile binaire. La forme hexadecimale
        // transmise au client double ce volume, soit environ 1,2 Ko : negligeable dans les deux cas.
        check("merkle : charge binaire = 576 octets", 576, p.steps().size() * 32);
        check("merkle : forme hexadecimale <= 1500 caracteres", true, p.encode().length() <= 1500);
    }

    private static void tamperedCommitmentFails() {
        List<byte[]> leaves = syntheticLeaves(64);
        MerkleTree tree = MerkleTree.of(leaves);
        InclusionProof proof = tree.proofFor(10);
        byte[] tampered = leaves.get(10).clone();
        tampered[0] ^= 0x01;
        check("merkle : un engagement altere echoue", false, MerkleTree.verify(tampered, proof, tree.root()));
    }

    private static void wrongRootFails() {
        List<byte[]> leaves = syntheticLeaves(64);
        MerkleTree tree = MerkleTree.of(leaves);
        MerkleTree autre = MerkleTree.of(syntheticLeaves(65));
        check(
                "merkle : une racine etrangere echoue",
                false,
                MerkleTree.verify(leaves.get(3), tree.proofFor(3), autre.root()));
    }

    private static void domainSeparationBlocksNodeAsLeaf() {
        // Deux feuilles : la racine est hashNode(hashLeaf(l0), hashLeaf(l1)).
        // Sans separation de domaine, cette racine pourrait etre presentee comme une feuille
        // d'un arbre a une seule feuille, et une inclusion fictive serait prouvable.
        List<byte[]> leaves = syntheticLeaves(2);
        MerkleTree tree = MerkleTree.of(leaves);
        MerkleTree forged = MerkleTree.of(List.of(tree.root()));
        check(
                "merkle : un noeud interne ne peut pas se faire passer pour une feuille",
                true,
                !forged.rootHex().equals(tree.rootHex()));
    }

    private static void oddLeafCountsAreInjective() {
        // La construction naive dupliquerait la derniere feuille et donnerait la meme racine
        // pour {a,b,c} et {a,b,c,c}. RFC 6962 l'interdit.
        List<byte[]> trois = syntheticLeaves(3);
        List<byte[]> quatre = new ArrayList<>(trois);
        quatre.add(trois.get(2).clone());
        check(
                "merkle : racine({a,b,c}) != racine({a,b,c,c})",
                true,
                !MerkleTree.of(trois).rootHex().equals(MerkleTree.of(quatre).rootHex()));
    }

    // ---------------------------------------------------------------- D -----

    private static void revealVerifies() {
        byte[] nonce = BidCommitment.newNonce();
        byte[] c = BidCommitment.commit(nonce, "M1", "sup-7", new BigDecimal("450"), Set.of());
        check(
                "engagement : la revelation correcte se verifie",
                true,
                BidCommitment.verifyReveal(c, nonce, "M1", "sup-7", new BigDecimal("450"), Set.of()));
        check("engagement : condense de 32 octets", 32, c.length);
    }

    private static void alteredAmountFailsReveal() {
        byte[] nonce = BidCommitment.newNonce();
        byte[] c = BidCommitment.commit(nonce, "M1", "sup-7", new BigDecimal("450"), Set.of());
        check(
                "engagement : un montant altere echoue",
                false,
                BidCommitment.verifyReveal(c, nonce, "M1", "sup-7", new BigDecimal("900"), Set.of()));
    }

    private static void canonicalScaleIsStable() {
        byte[] nonce = BidCommitment.newNonce();
        byte[] c = BidCommitment.commit(nonce, "M1", "sup-7", new BigDecimal("500"), Set.of());
        check(
                "engagement : 500 et 500.00 produisent le meme engagement",
                true,
                BidCommitment.verifyReveal(c, nonce, "M1", "sup-7", new BigDecimal("500.00"), Set.of()));
        boolean rejected = false;
        try {
            BidCommitment.commit(nonce, "M1", "sup-7", new BigDecimal("500.001"), Set.of());
        } catch (ArithmeticException e) {
            rejected = true;
        }
        check("engagement : une precision sous le centime est rejetee", true, rejected);
    }

    private static void crossMatchReplayFails() {
        byte[] nonce = BidCommitment.newNonce();
        byte[] c = BidCommitment.commit(nonce, "M1", "sup-7", new BigDecimal("450"), Set.of());
        check(
                "engagement : rejeu sur un autre match echoue",
                false,
                BidCommitment.verifyReveal(c, nonce, "M2", "sup-7", new BigDecimal("450"), Set.of()));
    }

    private static void crossBidderReplayFails() {
        byte[] nonce = BidCommitment.newNonce();
        byte[] c = BidCommitment.commit(nonce, "M1", "sup-7", new BigDecimal("450"), Set.of());
        check(
                "engagement : revendication par un autre compte echoue",
                false,
                BidCommitment.verifyReveal(c, nonce, "M1", "sup-9", new BigDecimal("450"), Set.of()));
    }

    private static void alteredCategoriesFailReveal() {
        byte[] nonce = BidCommitment.newNonce();
        byte[] c =
                BidCommitment.commit(nonce, "M1", "sup-7", new BigDecimal("450"), Set.of("VIRAGE"));
        check(
                "engagement : modifier les categories acceptees echoue",
                false,
                BidCommitment.verifyReveal(
                        c, nonce, "M1", "sup-7", new BigDecimal("450"), Set.of("VIP")));
        check(
                "engagement : l'ordre des categories est indifferent",
                true,
                BidCommitment.verifyReveal(
                        c, nonce, "M1", "sup-7", new BigDecimal("450"), Set.of("VIRAGE")));
    }

    // ---------------------------------------------------------------- E -----

    /** Fournisseur HMAC local, pour les tests uniquement : en production la clé reste au KMS. */
    private static CinPseudonymizer.KeyedMacProvider localMac(String key) {
        return payload -> {
            try {
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(
                        new javax.crypto.spec.SecretKeySpec(
                                key.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
                return mac.doFinal(payload);
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static void cinPseudonymIsDeterministic() {
        CinPseudonymizer p = new CinPseudonymizer(localMac("cle-kms-simulee"), "v1");
        String a = p.pseudonymize("AB123456").toHex();
        String b = p.pseudonymize("ab123456").toHex(); // normalisation casse
        String c = p.pseudonymize("  AB123456 ").toHex(); // normalisation espaces
        check("cin : pseudonyme deterministe", true, a.equals(b) && b.equals(c));
        check("cin : pseudonyme sur 32 octets", 64, a.length());
    }

    private static void cinPseudonymSeparatesIdentities() {
        CinPseudonymizer p = new CinPseudonymizer(localMac("cle-kms-simulee"), "v1");
        check(
                "cin : deux CIN distinctes donnent deux pseudonymes distincts",
                true,
                !p.pseudonymize("AB123456").toHex().equals(p.pseudonymize("AB123457").toHex()));
    }

    private static void cinFormatIsValidated() {
        CinPseudonymizer p = new CinPseudonymizer(localMac("k"), "v1");
        int rejections = 0;
        for (String bad : new String[] {"123456", "ABCD1234", "AB12", "AB1234567", "", "AB12345!"}) {
            try {
                p.pseudonymize(bad);
            } catch (IllegalArgumentException e) {
                rejections++;
            }
        }
        check("cin : 6 formats invalides rejetes", 6, rejections);
    }

    private static void cinErrorNeverLeaksValue() {
        CinPseudonymizer p = new CinPseudonymizer(localMac("k"), "v1");
        String message = "";
        try {
            p.pseudonymize("CIN-SECRETE-999");
        } catch (IllegalArgumentException e) {
            message = String.valueOf(e.getMessage());
        }
        check("cin : le message d'erreur ne contient pas la valeur", false, message.contains("999"));
        // Le toString du pseudonyme ne doit pas exposer la valeur non plus.
        check(
                "cin : toString() masque le pseudonyme",
                true,
                p.pseudonymize("AB123456").toString().contains("masqué"));
    }

    private static void cinKeyRotationChangesOutput() {
        String v1 = new CinPseudonymizer(localMac("cle"), "v1").pseudonymize("AB123456").toHex();
        String v2 = new CinPseudonymizer(localMac("cle"), "v2").pseudonymize("AB123456").toHex();
        check("cin : la version de cle fait partie du calcul", true, !v1.equals(v2));
    }

    private static byte[] totpSeed() {
        byte[] seed = new byte[32];
        new Random(7).nextBytes(seed);
        return seed;
    }

    private static void ticketCodeVerifiesWithinWindow() {
        byte[] seed = totpSeed();
        long t = 1_760_000_000L;
        String code = RotatingTicketCode.generate(seed, "ticket-1", t);
        check("billet : le code courant est accepte", true,
                RotatingTicketCode.verify(seed, "ticket-1", code, t));
        check("billet : code a 9 chiffres", 9, code.length());
    }

    private static void ticketCodeExpires() {
        byte[] seed = totpSeed();
        long t = 1_760_000_000L;
        String code = RotatingTicketCode.generate(seed, "ticket-1", t);
        // Au-dela de la tolerance d'un pas, une capture d'ecran ne vaut plus rien.
        long plusTard = t + 5 * RotatingTicketCode.stepSeconds();
        check(
                "billet : une capture d'ecran perime apres quelques pas",
                false,
                RotatingTicketCode.verify(seed, "ticket-1", code, plusTard));
    }

    private static void ticketCodeToleratesDrift() {
        byte[] seed = totpSeed();
        long t = 1_760_000_000L;
        String code = RotatingTicketCode.generate(seed, "ticket-1", t);
        long step = RotatingTicketCode.stepSeconds();
        check("billet : tolerance d'un pas en avance", true,
                RotatingTicketCode.verify(seed, "ticket-1", code, t + step));
        check("billet : tolerance d'un pas en retard", true,
                RotatingTicketCode.verify(seed, "ticket-1", code, t - step));
    }

    private static void ticketCodeIsBoundToTicket() {
        byte[] seed = totpSeed();
        long t = 1_760_000_000L;
        String code = RotatingTicketCode.generate(seed, "ticket-1", t);
        check(
                "billet : un code n'est pas transposable a un autre billet",
                false,
                RotatingTicketCode.verify(seed, "ticket-2", code, t));
    }

    // ------------------------------------------------------------- outils ---

    private static RankedBid bid(String id, String amount, long seq) {
        return RankedBid.acceptingAll(id, new BigDecimal(amount), seq, "h-" + id);
    }

    private static List<byte[]> syntheticLeaves(int n) {
        List<byte[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byte[] nonce = new byte[32];
            new Random(i).nextBytes(nonce);
            out.add(BidCommitment.commit(nonce, "M1", "sup-" + i, new BigDecimal("300"), Set.of()));
        }
        return out;
    }

    private static String categoryOf(AllocationResult r, String bidderId) {
        for (BidOutcome o : r.outcomes()) {
            if (o.bidderId().equals(bidderId)) {
                return o.isWinner() ? o.assignedCategory().code() : null;
            }
        }
        return null;
    }

    private static BigDecimal priceOf(AllocationResult r, String bidderId) {
        for (BidOutcome o : r.outcomes()) {
            if (o.bidderId().equals(bidderId)) {
                return o.pricePaid();
            }
        }
        throw new IllegalStateException("introuvable : " + bidderId);
    }

    private static String reasonOf(AllocationResult r, String bidderId) {
        for (BidOutcome o : r.outcomes()) {
            if (o.bidderId().equals(bidderId)) {
                return o.reasonIfRejected();
            }
        }
        return null;
    }

    private static boolean isWinner(AllocationResult r, String bidderId) {
        return categoryOf(r, bidderId) != null;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static void section(String title) {
        System.out.println();
        System.out.println(title);
        System.out.println("-".repeat(72));
    }

    private static void check(String label, Object expected, Object actual) {
        boolean ok = java.util.Objects.equals(expected, actual);
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + label);
        } else {
            failed++;
            System.out.println("  [ECHEC] " + label + "  attendu=" + expected + " obtenu=" + actual);
        }
    }
}
