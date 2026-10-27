# Billetterie dynamique — plateforme d'enchères scellées pour matchs à forte demande

Projet de hackathon. Plateforme de billetterie dont le prix est déterminé par une enchère de
Vickrey à places multiples et catégories hétérogènes, conçue pour éliminer structurellement la
marge du marché noir tout en garantissant aux abonnés un accès à prix fixe.

**Auteur** : Moncef — INSEA Rabat
**État** : spécification v2, moteur de tarification implémenté et vérifié, intégration à terminer

---

## Le problème et la réponse

Sur les matchs à forte demande, tant que le prix officiel reste inférieur à la disposition à
payer du marché, il existe un écart capturable par les revendeurs. Aucune mesure de contrôle ne
supprime cette incitation ; seul l'alignement du prix officiel sur le prix d'équilibre l'annule.

La plateforme fait donc déclarer à chaque supporter sa disposition à payer maximale pendant une
fenêtre bornée, puis fixe un prix unique par catégorie égal à la plus haute déclaration évincée.
Les abonnés à la saison disposent d'une zone dédiée à prix fixe, hors du mécanisme.

---

## Contenu de l'archive

```
billetterie-dynamique/
├── README.md                       ← vous êtes ici
├── rapport/
│   └── Rapport_Billetterie_Dynamique.docx     rapport v1, 26 pages
├── docs/
│   ├── SPECIFICATION-V2.md         décisions de conception corrigées
│   ├── ARCHITECTURE-V2.md          architecture, remplace le chapitre 5 du rapport
│   └── uml/                        10 diagrammes : sources .puml + rendus .png et .svg
└── pricing-engine/                 module Java du moteur de tarification
    ├── pom.xml
    ├── README.md
    └── src/
```

### Ordre de lecture conseillé

1. `rapport/Rapport_Billetterie_Dynamique.docx` — le contexte, la problématique, la solution
2. `docs/SPECIFICATION-V2.md` — ce qui a été corrigé et pourquoi
3. `docs/ARCHITECTURE-V2.md` — la structure logicielle qui porte ces décisions
4. `docs/uml/` — les diagrammes, à insérer dans le rapport en version SVG
5. `pricing-engine/` — le code, avec son harnais de vérification

---

## Avertissement sur le rapport v1

Le rapport a été rédigé avant la revue de conception. Trois passages sont **périmés** et doivent
être corrigés avant toute soutenance :

| Passage | Problème | Remplacé par |
|---|---|---|
| Chapitre 3.2 | Propose `p* = b(N)` par défaut, ce qui détruit l'incitation à la véracité | `SPECIFICATION-V2.md` §1.1 |
| Chapitre 5 | Architecture sans scellement ni ancrage externe | `ARCHITECTURE-V2.md` |
| Chapitre 8.2 | Code Java qui charge 200 000 entités en mémoire — `OutOfMemoryError` garanti | `SPECIFICATION-V2.md` §3.1 |

Le chapitre 8 est le plus urgent : un jury technique qui lit `findAll` suivi de `subList` sur
200 000 lignes posera la question immédiatement.

---

## Les quatre corrections structurantes

**Le prix est `b(N+1)`, pas `b(N)`.** Sous `b(N)`, le gagnant marginal paie sa propre
déclaration : son surplus est nul et sous-déclarer lui rapporte strictement. La séparation entre
« ma déclaration décide si je gagne » et « les autres décident ce que je paie » est rompue, et
avec elle toute la justification théorique du projet.

**Les enchères sont réellement scellées.** TLS protège le transport, pas le repos. Une enchère
lisible par un DBA n'est pas scellée. Le client calcule un engagement HMAC et ne transmet que le
condensé ; le montant n'est révélé qu'après la clôture.

**L'exploitant n'est pas une partie de confiance.** C'est le problème phare de l'enchère de
Vickrey : le vendeur a un intérêt profitable, invisible et indémontrable à insérer une enchère
fantôme. L'arbre de Merkle horodaté par un tiers pendant la fenêtre est ce qui le lui interdit.

**Le calcul va vers la donnée.** Trouver la `(N+1)`-ième valeur d'un ensemble trié est une
statistique d'ordre : elle se calcule dans PostgreSQL par parcours d'index, pas en chargeant
200 000 entités Hibernate.

---

## Vérifier le moteur sans rien installer

Le noyau de tarification ne dépend que du JDK 21. Depuis `pricing-engine/` :

```bash
javac -d target/classes -encoding UTF-8 \
  src/main/java/com/billetterie/pricing/domain/*.java \
  src/main/java/com/billetterie/pricing/merkle/*.java \
  src/main/java/com/billetterie/pricing/commit/*.java \
  src/main/java/com/billetterie/security/*.java \
  src/test/java/com/billetterie/pricing/VerificationHarness.java

java -Dfile.encoding=UTF-8 -cp target/classes com.billetterie.pricing.VerificationHarness
```

Attendu : `RESULTAT : 79 reussis, 0 echoues`.

Avec Maven et un accès réseau, `mvn test` exécute en plus la suite JUnit.

### Mesures relevées

| Scénario | Résultat |
|---|---|
| 200 000 déclarations, 55 000 places, 3 catégories | 55 000 gagnants en **745 ms**, tas de 512 Mo |
| 400 ex æquo à 500 MAD pour 100 places | les 100 premières séquences, exactement |
| 20 permutations des mêmes entrées | prix et affectations identiques |
| Preuve d'inclusion sur 200 000 feuilles | 18 condensés, 576 octets |

---

## Exemple de référence

Six places, dont une VIP et cinq en tribune. Déclarations : 600, 500, 500, 500, 500, 300.

| Rang | Déclaration | Catégorie | Prix payé |
|------|-------------|-----------|-----------|
| 1 | 600 | VIP | **500** |
| 2–5 | 500 | Tribune | réserve |
| 6 | 300 | Tribune | réserve |

Le VIP coûte 500 parce que le deuxième déclarant en a été évincé ; la tribune retombe à la
réserve, personne n'en ayant été évincé. Le gagnant du VIP paie 500, pas les 600 qu'il a
déclarés — c'est la séparation de Vickrey.

Ce cas est couvert par `AllocationEngineTest.referenceExample`.

---

## Ce qu'il reste à faire

**Avant la soutenance**
- Corriger les chapitres 3.2, 5 et 8.2 du rapport
- Exécuter un test de charge reproduisant le pic de clôture
- Préparer la réponse à « vous réservez le stade aux riches » : pool solidaire et redistribution

**Avant une mise en production**
- Câbler `ClosureOrchestrator`, `SubscriberZoneReconciler` et `IdempotencyGuard` sur les entités
  du projet existant — la logique et les requêtes sont complètes, il manque le branchement
- Intégration PSP réelle, en vérifiant qu'aucune donnée de carte ne transite par l'infrastructure
- SLO chiffrés par phase, répétition générale de la clôture, procédure de crise écrite
- Rachat officiel à `p*` avec réattribution en cascade, qui est ce qui permet d'affirmer
  « zéro marché noir *et* zéro siège vide »

---

## Pile technique

Java 21, Spring Boot 3.3, PostgreSQL 16 avec réplique synchrone, Redis, Kafka, React 18 et
React Native. Clé HMAC en KMS ou HSM, horodatage RFC 3161, paiement via CMI.

Le détail et les justifications sont dans `docs/ARCHITECTURE-V2.md` §6.
