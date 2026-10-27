package com.billetterie.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.billetterie.pricing.domain.AllocationEngine;
import com.billetterie.pricing.domain.AllocationResult;
import com.billetterie.pricing.domain.BidOutcome;
import com.billetterie.pricing.domain.Category;
import com.billetterie.pricing.domain.RankedBid;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests du moteur d'allocation.
 *
 * <p>Le paquet {@code domain} ne dépendant que du JDK, ces tests sont purement unitaires :
 * aucun contexte Spring, aucune base. C'est la conséquence voulue du choix d'une fonction pure.
 */
class AllocationEngineTest {

    private static final BigDecimal RESERVE = new BigDecimal("200.00");
    private final AllocationEngine engine = new AllocationEngine();

    private static RankedBid bid(String id, String amount, long seq) {
        return RankedBid.acceptingAll(id, new BigDecimal(amount), seq, "h-" + id);
    }

    private static String categoryOf(AllocationResult r, String id) {
        return r.outcomes().stream()
                .filter(o -> o.bidderId().equals(id))
                .findFirst()
                .map(o -> o.isWinner() ? o.assignedCategory().code() : null)
                .orElse(null);
    }

    private static BigDecimal paidBy(AllocationResult r, String id) {
        return r.outcomes().stream()
                .filter(o -> o.bidderId().equals(id))
                .findFirst()
                .map(BidOutcome::pricePaid)
                .orElseThrow();
    }

    private static boolean wins(AllocationResult r, String id) {
        return categoryOf(r, id) != null;
    }

    @Nested
    @DisplayName("Règle de prix")
    class Pricing {

        @Test
        @DisplayName("avec une seule catégorie, le prix est exactement b(N+1)")
        void singleCategoryGivesBNPlusOne() {
            var cats = List.of(new Category("TRIBUNE", 1, 3));
            var bids =
                    List.of(
                            bid("a", "900", 1),
                            bid("b", "700", 2),
                            bid("c", "550", 3), // b(N) — ne doit PAS être le prix
                            bid("d", "480", 4), // b(N+1) — doit être le prix
                            bid("e", "300", 5));

            var r = engine.allocate(cats, bids, RESERVE);

            assertEquals(0, new BigDecimal("480").compareTo(r.priceOf("TRIBUNE")));
            assertEquals(3, r.winnerCount());
        }

        @Test
        @DisplayName("aucun bénéficiaire ne contribue au prix de sa propre catégorie")
        void winnersNeverSetTheirOwnPrice() {
            var cats = List.of(new Category("VIP", 1, 2), new Category("TRIBUNE", 2, 4));
            var bids = new ArrayList<RankedBid>();
            int[] amounts = {950, 880, 700, 660, 640, 610, 590, 500, 400};
            for (int i = 0; i < amounts.length; i++) {
                bids.add(bid("b" + i, String.valueOf(amounts[i]), i + 1));
            }

            var r = engine.allocate(cats, bids, RESERVE);

            for (Category c : cats) {
                BigDecimal price = r.priceOf(c.code());
                if (price.compareTo(RESERVE) == 0) {
                    continue;
                }
                for (BidOutcome w : r.winners()) {
                    boolean sameOrBetter = w.assignedCategory().rank() <= c.rank();
                    boolean carriesPrice = w.declaredAmount().compareTo(price) == 0;
                    assertFalse(
                            sameOrBetter && carriesPrice,
                            "le gagnant " + w.bidderId() + " porte le prix de " + c.code());
                }
            }
        }

        @Test
        @DisplayName("les prix sont emboîtés : une meilleure catégorie coûte plus cher")
        void pricesAreMonotonic() {
            var cats =
                    List.of(
                            new Category("VIP", 1, 2),
                            new Category("TRIBUNE", 2, 3),
                            new Category("VIRAGE", 3, 4));
            var bids = new ArrayList<RankedBid>();
            int[] amounts = {1000, 900, 800, 700, 600, 550, 500, 450, 400, 380, 350, 300};
            for (int i = 0; i < amounts.length; i++) {
                bids.add(bid("b" + i, String.valueOf(amounts[i]), i + 1));
            }

            var r = engine.allocate(cats, bids, RESERVE);

            // p(k) = b(cumul(k)+1) : VIP → b(3)=800, TRIBUNE → b(6)=550, VIRAGE → b(10)=380
            assertEquals(0, new BigDecimal("800").compareTo(r.priceOf("VIP")));
            assertEquals(0, new BigDecimal("550").compareTo(r.priceOf("TRIBUNE")));
            assertEquals(0, new BigDecimal("380").compareTo(r.priceOf("VIRAGE")));
            assertTrue(r.priceOf("VIP").compareTo(r.priceOf("TRIBUNE")) >= 0);
            assertTrue(r.priceOf("TRIBUNE").compareTo(r.priceOf("VIRAGE")) >= 0);
        }

        @Test
        @DisplayName("sous-souscription : le prix retombe à la réserve")
        void undersubscribedFallsToReserve() {
            var cats = List.of(new Category("TRIBUNE", 1, 50));
            var r = engine.allocate(cats, List.of(bid("a", "900", 1), bid("b", "400", 2)), RESERVE);

            assertEquals(0, RESERVE.compareTo(r.priceOf("TRIBUNE")));
            assertEquals(2, r.winnerCount());
        }

        @Test
        @DisplayName("une déclaration sous la réserve est rejetée")
        void belowReserveIsRejected() {
            var cats = List.of(new Category("TRIBUNE", 1, 5));
            var r =
                    engine.allocate(
                            cats,
                            List.of(bid("ok", "300", 1), bid("sous", "150", 2), bid("pile", "200", 3)),
                            RESERVE);

            assertFalse(wins(r, "sous"));
            assertTrue(wins(r, "pile"), "une déclaration égale à la réserve est acceptable");
        }
    }

    @Nested
    @DisplayName("Affectation par catégorie")
    class Assignment {

        @Test
        @DisplayName("exemple de référence : 6 places dont 1 VIP, pour 600/500×4/300")
        void referenceExample() {
            var cats = List.of(new Category("VIP", 1, 1), new Category("TRIBUNE", 2, 5));
            var bids =
                    List.of(
                            bid("s600", "600", 1),
                            bid("s500a", "500", 2),
                            bid("s500b", "500", 3),
                            bid("s500c", "500", 4),
                            bid("s500d", "500", 5),
                            bid("s300", "300", 6));

            var r = engine.allocate(cats, bids, RESERVE);

            assertEquals("VIP", categoryOf(r, "s600"), "le plus offrant obtient le VIP");
            assertEquals("TRIBUNE", categoryOf(r, "s500a"));
            assertEquals("TRIBUNE", categoryOf(r, "s500d"));
            assertEquals("TRIBUNE", categoryOf(r, "s300"));
            assertEquals(6, r.winnerCount());

            // Le VIP coûte 500 : c'est la déclaration du premier évincé du VIP.
            assertEquals(0, new BigDecimal("500").compareTo(r.priceOf("VIP")));
            // La tribune retombe à la réserve : personne n'en a été évincé.
            assertEquals(0, RESERVE.compareTo(r.priceOf("TRIBUNE")));
            // Le gagnant du VIP paie 500 et non les 600 qu'il a déclarés.
            assertEquals(0, new BigDecimal("500").compareTo(paidBy(r, "s600")));
        }

        @Test
        @DisplayName("un supporter peut refuser une catégorie mieux classée")
        void acceptableCategoriesAreRespected() {
            var cats = List.of(new Category("VIP", 1, 2), new Category("VIRAGE", 2, 2));
            var bids =
                    List.of(
                            new RankedBid("ultra", new BigDecimal("900"), 1, "h1", Set.of("VIRAGE")),
                            bid("b", "800", 2),
                            bid("c", "700", 3),
                            bid("d", "600", 4),
                            bid("e", "500", 5));

            var r = engine.allocate(cats, bids, RESERVE);

            assertEquals("VIRAGE", categoryOf(r, "ultra"), "l'ultra n'est pas forcé en VIP");
            assertEquals("VIP", categoryOf(r, "b"));
            assertEquals("VIP", categoryOf(r, "c"));
            assertEquals("VIRAGE", categoryOf(r, "d"));
            assertFalse(wins(r, "e"));
        }
    }

    @Nested
    @DisplayName("Départage des ex æquo")
    class TieBreaking {

        @Test
        @DisplayName("à montant égal, priorité au premier ayant posé sa demande")
        void earliestCommitWins() {
            var cats = List.of(new Category("TRIBUNE", 1, 3));
            var bids =
                    List.of(
                            bid("tard", "500", 40),
                            bid("tot", "500", 5),
                            bid("moyen", "500", 17),
                            bid("tresTard", "500", 90),
                            bid("bas", "300", 1));

            var r = engine.allocate(cats, bids, RESERVE);

            assertTrue(wins(r, "tot"));
            assertTrue(wins(r, "moyen"));
            assertTrue(wins(r, "tard"));
            assertFalse(wins(r, "tresTard"), "le dernier arrivé est évincé");
            assertFalse(wins(r, "bas"), "une séquence précoce ne compense pas un montant inférieur");
        }

        @Test
        @DisplayName("une modification vaut nouvelle demande et fait perdre la priorité")
        void modificationResetsPriority() {
            var cats = List.of(new Category("TRIBUNE", 1, 2));

            var avant = List.of(bid("A", "500", 1), bid("B", "500", 2), bid("C", "500", 3));
            var r1 = engine.allocate(cats, avant, RESERVE);
            assertTrue(wins(r1, "A"));
            assertTrue(wins(r1, "B"));
            assertFalse(wins(r1, "C"));

            // A modifie : son engagement est réinséré avec une séquence plus élevée.
            var apres = List.of(bid("A", "500", 9), bid("B", "500", 2), bid("C", "500", 3));
            var r2 = engine.allocate(cats, apres, RESERVE);
            assertFalse(wins(r2, "A"), "A perd sa priorité au profit de C");
            assertTrue(wins(r2, "B"));
            assertTrue(wins(r2, "C"));
        }

        @Test
        @DisplayName("ex æquo massifs au prix de clôture : départage déterministe et complet")
        void massTieAtClearingPrice() {
            var cats = List.of(new Category("TRIBUNE", 1, 100));
            var bids = new ArrayList<RankedBid>();
            // 400 déclarations identiques à 500 pour 100 places : le cas normal avec des
            // montants ronds, pas un cas limite.
            for (int i = 0; i < 400; i++) {
                bids.add(bid("b" + i, "500", i + 1));
            }

            var r = engine.allocate(cats, bids, RESERVE);

            assertEquals(100, r.winnerCount());
            assertEquals(0, new BigDecimal("500").compareTo(r.priceOf("TRIBUNE")));
            // Les 100 premières séquences, et elles seules.
            for (int i = 0; i < 100; i++) {
                assertTrue(wins(r, "b" + i), "b" + i + " devrait être servi");
            }
            for (int i = 100; i < 400; i++) {
                assertFalse(wins(r, "b" + i), "b" + i + " devrait être évincé");
            }
        }
    }

    @Nested
    @DisplayName("Incitation et reproductibilité")
    class Incentives {

        @Test
        @DisplayName("aucune sous-déclaration n'améliore le surplus sous b(N+1)")
        void truthfulnessIsDominant() {
            var cats = List.of(new Category("TRIBUNE", 1, 3));
            var truthful =
                    new ArrayList<>(
                            List.of(
                                    bid("a", "900", 1),
                                    bid("cible", "700", 2),
                                    bid("c", "600", 3),
                                    bid("d", "520", 4),
                                    bid("e", "300", 5)));

            BigDecimal valeur = new BigDecimal("700");
            BigDecimal surplusHonnete =
                    valeur.subtract(paidBy(engine.allocate(cats, truthful, RESERVE), "cible"));
            assertEquals(0, new BigDecimal("180").compareTo(surplusHonnete));

            for (int shade = 690; shade >= 200; shade -= 10) {
                var deviated = new ArrayList<>(truthful);
                deviated.set(1, bid("cible", String.valueOf(shade), 2));
                var r = engine.allocate(cats, deviated, RESERVE);
                BigDecimal surplus =
                        wins(r, "cible") ? valeur.subtract(paidBy(r, "cible")) : BigDecimal.ZERO;
                assertTrue(
                        surplus.compareTo(surplusHonnete) <= 0,
                        "déclarer " + shade + " au lieu de 700 améliorerait le surplus");
            }
        }

        @Test
        @DisplayName("le résultat est invariant par permutation des entrées")
        void resultIsReproducible() {
            var cats = List.of(new Category("VIP", 1, 3), new Category("TRIBUNE", 2, 7));
            var base = new ArrayList<RankedBid>();
            var rnd = new Random(42);
            for (int i = 0; i < 60; i++) {
                base.add(bid("b" + i, String.valueOf(200 + rnd.nextInt(14) * 50), i + 1));
            }

            var reference = engine.allocate(cats, base, RESERVE);
            for (int trial = 0; trial < 20; trial++) {
                var shuffled = new ArrayList<>(base);
                Collections.shuffle(shuffled, new Random(trial));
                var other = engine.allocate(cats, shuffled, RESERVE);

                assertEquals(reference.clearingPricesSorted(), other.clearingPricesSorted());
                for (BidOutcome o : reference.outcomes()) {
                    assertEquals(
                            categoryOf(reference, o.bidderId()),
                            categoryOf(other, o.bidderId()),
                            "affectation instable pour " + o.bidderId());
                }
            }
        }

        @Test
        @DisplayName("passage à l'échelle : 200 000 déclarations, 55 000 places")
        void scalesToProductionVolume() {
            var cats =
                    List.of(
                            new Category("VIP", 1, 2_000),
                            new Category("TRIBUNE", 2, 23_000),
                            new Category("VIRAGE", 3, 30_000));
            var bids = new ArrayList<RankedBid>(200_000);
            var rnd = new Random(7);
            for (int i = 0; i < 200_000; i++) {
                bids.add(bid("b" + i, String.valueOf(200 + rnd.nextInt(40) * 25), i + 1));
            }

            long start = System.nanoTime();
            var r = engine.allocate(cats, bids, RESERVE);
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertEquals(55_000, r.winnerCount());
            assertTrue(r.priceOf("VIP").compareTo(r.priceOf("TRIBUNE")) >= 0);
            assertTrue(r.priceOf("TRIBUNE").compareTo(r.priceOf("VIRAGE")) >= 0);
            assertTrue(millis < 10_000, "calcul trop lent : " + millis + " ms");
        }
    }
}
