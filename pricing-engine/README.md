# pricing-engine v2 — corrections appliquées

Module de tarification corrigé pour la plateforme de billetterie dynamique.
Les décisions de conception et leur justification sont dans [`docs/SPECIFICATION-V2.md`](docs/SPECIFICATION-V2.md).

## Contenu

```
src/main/java/com/billetterie/
├── pricing/domain/              # Noyau — aucune dépendance au-delà du JDK
│   ├── AllocationEngine.java    #   p* = b(N+1), catégories, départage temporel
│   ├── Category.java            #   ordre de désirabilité publié à l'avance
│   ├── RankedBid.java           #   commit_seq = autorité de priorité
│   ├── BidOutcome.java
│   └── AllocationResult.java
├── pricing/merkle/              # Journal de transparence
│   ├── MerkleTree.java          #   RFC 6962, séparation de domaine
│   └── InclusionProof.java      #   preuve vérifiable hors ligne
├── pricing/commit/
│   └── BidCommitment.java       # engagement scellé HMAC + révélation
├── pricing/
│   ├── ClosureOrchestrator.java # 4 étapes, transactions courtes
│   ├── BidCommitmentRepository.java  # statistiques d'ordre en SQL
│   └── SubscriberZoneReconciler.java # zone abonnés, N_k figé avant clôture
├── security/
│   ├── CinPseudonymizer.java    # HMAC à clé KMS non extractible
│   └── RotatingTicketCode.java  # QR rotatif 30 s, type TOTP
└── payment/
    └── IdempotencyGuard.java    # anti double débit, clé en PostgreSQL

src/main/resources/db/migration/
├── V2__bid_commitment_log.sql          # journal en ajout seul + index de classement
├── V3__categories_and_subscriber_zone.sql
└── V4__idempotency_anchors_tickets.sql
```

Le paquet `pricing/domain` est volontairement sans dépendance : le calcul du prix est une
fonction pure, testable sans contexte Spring ni base, et donc rejouable à l'audit.

## Vérification sans dépendance externe

Le harnais couvre 79 assertions et ne requiert qu'un JDK 21 :

```bash
javac -d target/classes -encoding UTF-8 \
  src/main/java/com/billetterie/pricing/domain/*.java \
  src/main/java/com/billetterie/pricing/merkle/*.java \
  src/main/java/com/billetterie/pricing/commit/*.java \
  src/main/java/com/billetterie/security/*.java \
  src/test/java/com/billetterie/pricing/VerificationHarness.java

java -Dfile.encoding=UTF-8 -cp target/classes com.billetterie.pricing.VerificationHarness
```

Résultat attendu : `RESULTAT : 79 reussis, 0 echoues`.

## Tests JUnit

```bash
mvn test
```

`AllocationEngineTest` couvre l'exemple de référence, la réduction à `b(N+1)`, la monotonie des
prix emboîtés, le départage par priorité, la perte de priorité après modification, la dominance de
la véracité, la reproductibilité sous permutation et le passage à l'échelle.

## Mesures relevées

| Scénario | Résultat |
|---|---|
| 200 000 déclarations, 55 000 places, 3 catégories | 55 000 gagnants en **745 ms**, tas de 512 Mo |
| 400 ex æquo à 500 MAD pour 100 places | 100 gagnants, les 100 premières séquences exactement |
| Preuve d'inclusion sur 200 000 feuilles | 18 condensés, 576 octets |
| 20 permutations des mêmes entrées | prix et affectations identiques |

## Intégration dans le projet existant

1. Remplacer l'ancien `pricing-engine` par ce module.
2. Appliquer les migrations `V2` à `V4`. La table `bid` d'origine est remplacée par
   `bid_commitment` ; aucune reprise de données n'est prévue, les montants en clair ne devant pas
   être conservés.
3. Les classes `ClosureOrchestrator`, `SubscriberZoneReconciler`, `IdempotencyGuard` référencent
   des entités et dépôts du projet (`MatchEntity`, `DynamicPoolRepository`, `RevealService`,
   `PaymentCaptureService`…) à câbler sur l'existant. Les paquets `domain`, `merkle`, `commit` et
   `security` sont complets et compilent seuls.
4. Retirer du rapport la variante `p* = b(N)` : l'option ne doit pas survivre en configuration.
