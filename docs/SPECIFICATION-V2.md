# Spécification v2 — Corrections appliquées

Plateforme de billetterie dynamique. Ce document fixe les décisions de conception de la version 2
et remplace, sur chacun des points traités, le contenu du rapport initial.

---

## 1. Règles fonctionnelles

### 1.1 Prix de clôture : `p* = b(N+1)`, non paramétrable

La version initiale proposait `p* = b(N)` par défaut, avec `b(N+1)` en « variante stricte ».
C'est inversé, et le compromis annoncé entre recette et véracité est un faux arbitrage.

Sous `b(N)`, le gagnant marginal paie sa propre déclaration : son surplus est nul, et sous-déclarer
abaisse le prix pour lui-même et pour tous les autres gagnants tout en restant servi. La séparation
entre « ma déclaration décide si je gagne » et « les autres décident ce que je paie » est rompue, et
avec elle toute la justification théorique du projet.

Le gain de recette invoqué est négligeable — `b(N)` et `b(N+1)` sont deux statistiques d'ordre
adjacentes, soit une fraction de dirham par place sur 70 000 déclarations — et probablement négatif
en pratique, puisque les déclarations elles-mêmes se dégradent.

**Décision.** `b(N+1)` est le comportement unique. Le paramètre est retiré : une option de
configuration qui casse l'incitation-compatibilité est un piège pour le futur exploitant.

Vérifié par `AllocationEngineTest.singleCategoryGivesBNPlusOne` et
`truthfulnessIsDominant` (aucune sous-déclaration, de 690 à 200, n'améliore le surplux).

### 1.2 Catégories hétérogènes et affectation assortative

Un prix unique pour une tribune d'honneur et un virage haut est économiquement faux.

**Décision.** Le stade est découpé en catégories dotées d'un `desirability_rank` **publié à
l'avance** avec le match. Les déclarations sont classées une fois, puis parcourues dans l'ordre :
chacun reçoit la meilleure catégorie encore disponible parmi celles qu'il accepte.

Exemple de référence — 6 places (1 VIP, 5 tribune), déclarations 600 / 500 × 4 / 300 :

| Rang | Déclaration | Catégorie | Prix payé |
|------|-------------|-----------|-----------|
| 1 | 600 | VIP | **500** |
| 2–5 | 500 | Tribune | réserve |
| 6 | 300 | Tribune | réserve |

Le prix d'une catégorie `k` est la plus haute déclaration parmi ceux qui **acceptaient `k` et
n'ont obtenu ni `k` ni mieux** — le coût d'opportunité de sa dernière place. Ici le VIP coûte 500
parce que le deuxième déclarant en a été évincé ; la tribune retombe à la réserve, personne n'en
ayant été évincé. Avec une seule catégorie, cette règle redonne exactement `b(N+1)`.

Propriété obtenue : `p(VIP) ≥ p(Tribune) ≥ p(Virage)`. Une grille où la tribune coûterait plus
cher que le VIP serait indéfendable publiquement ; la monotonie est vérifiée et imposée en sortie.

**Hypothèse assumée.** Différenciation verticale : l'ordre de désirabilité est le même pour tous.
Le champ `acceptableCategories` est la soupape pour le supporter dont les préférences ne suivent
pas cet ordre — celui qui veut le virage avec les ultras et refuse le VIP. Par défaut, toutes les
catégories sont acceptées, ce qui correspond exactement à l'affectation assortative.

**Limite à énoncer en soutenance.** Prix unique et véracité stricte sont incompatibles dès qu'un
supporter veut plusieurs places (réduction de demande). VCG resterait véridique mais ferait payer
un prix différent à chaque gagnant. Choix retenu : prix uniforme et lots bornés à 4 places. Un lot
de 4 sur 55 000 représente 0,007 % du pool : le pouvoir de marché individuel est trop faible pour
être exploitable, donc la véracité devient approximativement dominante — le standard des enchères
réelles à grande échelle.

### 1.3 Départage par priorité temporelle

Avec des montants ronds (200, 300, 500 MAD), les ex æquo au prix de clôture sont le cas **normal**,
pas un cas limite.

**Décision.** À montant égal, priorité au premier ayant posé sa demande. Une modification vaut
nouvelle demande : c'est la dernière modification qui compte.

L'autorité de priorité est `bid_commitment.commit_seq`, un `BIGSERIAL` attribué par la base au
moment de l'**engagement**. Deux propriétés en découlent :

- Aucune horloge applicative n'intervient : ni dérive NTP ni recul d'horloge ne peuvent produire
  deux déclarations de même rang. Le tri est total, donc le classement est reproductible.
- Une modification insère un nouvel engagement, qui reçoit une séquence plus élevée et perd sa
  priorité. **Cette règle est nécessaire**, pas seulement acceptable : sans elle, chacun déposerait
  un engagement bidon à l'ouverture pour réserver son rang, puis le corrigerait à la dernière
  seconde en conservant sa priorité.

Deux conséquences à assumer. D'abord, cette règle crée une incitation à déclarer **tôt**, et non
tard : elle aplatit la courbe de charge au lieu de concentrer un pic à l'échéance. Ensuite, elle
avantage marginalement ceux qui sont connectés à l'ouverture, ce qui corrèle avec le niveau
d'équipement ; l'effet ne porte que sur le groupe exactement au prix de clôture.

Un ex æquo résiduel sur montant **et** séquence est départagé par `tieBreakHash`, dérivé d'une
graine publique. Impossible avec un `BIGSERIAL`, mais la garde rend le tri total en toutes
circonstances.

Vérifié par `massTieAtClearingPrice` (400 déclarations à 500 pour 100 places : les 100 premières
séquences, et elles seules) et `modificationResetsPriority`.

### 1.4 Zone abonnés et réconciliation

**Décision.** Les supporters fidèles porteurs d'une carte de saison disposent d'un nombre de places
**fixe**, dans une zone dédiée, à prix fixe et stable, entièrement hors du mécanisme d'enchère.

Le problème résiduel est celui des places réservées non consommées : si la zone compte 5 000 places
et que 3 800 abonnés confirment, 1 200 sièges restent vides alors que des supporters ont été évincés
au prix de clôture. Perte de recette et de garnissage, visible à la télévision.

**Correction.** Une échéance de confirmation strictement antérieure à la clôture. À cette échéance,
les places non confirmées sont réintégrées au pool dynamique de leur propre catégorie, et `N_k`
devient définitif :

```
ouverture ──► confirmations abonnés ──► réconciliation ──► clôture ──► calcul de p*
                                        (N_k figé ici)
```

La réconciliation est déclenchée par l'échéance publiée, jamais par un administrateur : qui choisit
le moment de la réconciliation choisit la capacité du pool, donc le prix.

---

## 2. Corrections de sécurité

| Faille | Correction | Composant |
|---|---|---|
| Montants en clair en base | Engagement HMAC scellé, révélation après clôture | `BidCommitment` |
| Chaîne de hash réinscriptible par l'opérateur | Arbre de Merkle RFC 6962 + ancrage horodaté tiers | `MerkleTree`, `merkle_anchor` |
| CIN hachée énumérable | HMAC à clé non extractible (KMS/HSM) | `CinPseudonymizer` |
| Enchère fantôme de l'organisateur | Racine publiée pendant la fenêtre + comptage public | `MerkleAnchorService` |
| QR rejouable | Code rotatif 30 s + consommation à usage unique | `RotatingTicketCode`, `ticket` |
| Double débit par rejeu Kafka | Clé d'idempotence en PostgreSQL, même transaction | `IdempotencyGuard` |
| Clôture choisie par l'administrateur | Clôture automatique sur échéance signée | `ClosureOrchestrator` |

### 2.1 Enchères réellement scellées

TLS protège le transport, pas le repos. Une enchère « sous pli scellé » lisible par le DBA n'est pas
scellée, et l'hypothèse qui fait tenir la preuve de Vickrey est violée pour tout l'intérieur du
système.

Le client calcule `c = HMAC-SHA256(nonce, "BIDv1|matchId|bidderId|montant|catégories")` et ne
transmet que `c`. Le nonce de 256 bits assure la **dissimulation** : sans lui, `H(450)` serait
trivialement inversible, les montants plausibles en MAD ne comptant que quelques milliers de
valeurs. Le hachage assure la **liaison** : plus de modification sans collision.

`matchId` et `bidderId` sont dans la charge — séparation de domaine contre le rejeu inter-match et
la revendication par un autre compte. Les catégories acceptées y figurent aussi : sinon un
déclarant pourrait modifier son ensemble d'acceptation à la révélation, la concurrence connue.

Conséquence à ne pas manquer : le serveur ignorant le montant au dépôt, il ne peut pas le valider
contre le prix de réserve à ce moment. Le rejet des déclarations sous la réserve se fait donc au
calcul, pas à la soumission.

L'abstention de révélation fausserait le décompte : elle entraîne la saisie de la caution, et la
révélation est automatique côté client, sans action de l'utilisateur.

### 2.2 Intégrité : pourquoi la chaîne de hash ne suffisait pas

Une chaîne de hachage n'interdit que la modification **locale** d'un maillon. L'opérateur, qui
détient les entrées et l'algorithme, peut reconstruire la chaîne entière hors ligne avec les
déclarations de son choix : le résultat est parfaitement cohérent de bout en bout, et totalement
falsifié. Une structure d'intégrité auto-vérifiée par celui qui la produit ne prouve rien.

Deux ajouts :

1. **Ancrage externe daté.** La racine Merkle est publiée et horodatée par un tiers (RFC 3161)
   toutes les heures pendant la fenêtre. C'est ce qui prive l'exploitant de l'enchère fantôme — le
   problème phare de Vickrey, attaque autrement profitable, invisible et indémontrable. Le nombre
   d'engagements étant public avant la révélation, une déclaration surnuméraire devient
   arithmétiquement détectable.
2. **Preuves d'inclusion individuelles.** Chaque déclarant reçoit un chemin d'authentification de
   `log₂(M)` condensés — 18 condensés, 576 octets, pour 200 000 déclarations — et vérifie seul que
   sa déclaration figure dans la racine publiée.

Deux détails de construction sont des choix de sécurité, non d'implémentation :

- **Séparation de domaine** : feuilles préfixées `0x00`, nœuds internes `0x01`. Sans elle, un nœud
  interne peut être présenté comme une feuille et une inclusion fictive devient prouvable.
- **Pas de duplication du nœud orphelin.** La construction naïve duplique la dernière feuille, et
  `{a,b,c}` et `{a,b,c,c}` donnent alors la même racine (CVE-2012-2459). RFC 6962 découpe à la plus
  grande puissance de deux inférieure à `n`.

Le journal est en **ajout seul** : une modification n'écrase rien, elle insère un nouvel engagement
et marque l'ancien comme remplacé. L'arbre ne fait donc que croître, et une preuve remise à un
supporter reste valide après que d'autres ont modifié leur déclaration.

### 2.3 Pseudonymisation de la CIN

Un hash n'est pas un chiffrement : il ne protège que si l'espace des entrées est trop vaste pour
être parcouru. Le format de la CIN marocaine donne environ 2³⁰ candidats, contre 2⁸⁰ pour un mot de
passe fort. Quelques secondes de GPU suffisent.

Le sel ne sauve pas ce cas : le pseudonyme doit rester **déterministe** pour garantir « une CIN, une
déclaration », donc le sel est nécessairement global, donc il vit dans le code et tombe avec le
serveur. La sortie est de remplacer un secret connu du système par un secret inaccessible au
système : HMAC-SHA256 dont la clé réside dans un KMS et n'en sort jamais. La version de clé est
incluse dans la charge et stockée, pour permettre la rotation.

La CIN en clair n'est jamais écrite en base ni journalisée, et les messages d'erreur ne la
reproduisent pas — un journal d'erreurs ne doit pas devenir un gisement de CIN.

### 2.4 Biométrie — hors périmètre serveur

**Décision.** La biométrie ne quitte jamais l'appareil. WebAuthn/FIDO2 : le téléphone vérifie
localement et signe un challenge ; la plateforme ne stocke qu'une clé publique.

Un gabarit biométrique fuité est **irrévocable**. Cette décision supprime la classe de risque
entière plutôt que de la mitiger, et simplifie massivement la conformité (loi 09-08, RGPD art. 9).

### 2.5 Fenêtre courte et expiration des autorisations

Une autorisation de carte expire en quelques jours. Une fenêtre de trois semaines produirait des
captures refusées découvertes la veille du match.

**Décision.** Fenêtre de 48 à 72 h, capture immédiate après clôture, et réattribution en cascade au
rang suivant en cas d'échec de capture — sans quoi des sièges attribués restent impayés et vides.

---

## 3. Corrections opérationnelles

### 3.1 Calcul : déplacer le calcul vers la donnée

Le chapitre 8 du rapport initial contenait cette faille :

```java
List<Bid> bids = bidRepository.findAllByMatchIdOrderByAmountDesc(matchId);  // 200 000 entités
winners = bids.subList(0, dynamicPoolSize);
```

Plusieurs centaines de mégaoctets d'entités Hibernate suivies par le contexte de persistance, plus
le coût du *dirty checking*, sur un pod limité à 512 Mo : `OutOfMemoryError` au moment précis où le
système ne doit pas échouer.

Trouver la `(N+1)`-ième valeur d'un ensemble trié est une **statistique d'ordre** : elle se calcule
dans le moteur, par parcours d'un index B-tree déjà ordonné, et ne transfère qu'une valeur.

```sql
SELECT revealed_amount FROM bid_commitment
WHERE match_id = :matchId AND superseded_by IS NULL AND reveal_status = 'VALID'
ORDER BY revealed_amount DESC, commit_seq ASC
LIMIT 1 OFFSET :cumulative_capacity;
```

L'affectation se fait ensuite par `UPDATE` ensembliste — d'abord les déclarations strictement
supérieures au prix, puis les ex æquo exactement au prix dans l'ordre de priorité — sans jamais
charger un gagnant.

Mesuré : 200 000 déclarations, 55 000 places, 3 catégories → **745 ms avec un tas de 512 Mo**.

### 3.2 Verrou réduit à ce qu'il protège

Le verrou pessimiste couvrait le calcul, l'allocation et les paiements, soit plusieurs minutes. Une
transaction longue bloque le `VACUUM`, fait gonfler les tables et retient les connexions du pool au
pire moment.

**Décision.** Quatre étapes, transactions courtes : scellement sous verrou (millisecondes), calcul
sans verrou (fonction pure), persistance ensembliste, puis captures par lots de 2 000 dans des
transactions indépendantes et idempotentes.

### 3.3 Frontière temporelle

Il n'existe pas d'horloge globale dans un système réparti. Une soumission n'est pas un instant mais
un intervalle, et la question « est-elle arrivée avant la clôture ? » dépend du point de mesure.

**Décision.** L'autorité temporelle unique est le `commit` de transaction PostgreSQL. La décision
d'admission est prise **dans la transaction qui écrit l'engagement**, en lisant le statut du match
sous verrou partagé. La règle est publiée à l'avance : le commit fait foi.

Horloge client exclue (falsifiable), horloge de pod exclue (dérive, non monotone), offset Kafka
exclu (ordonné par partition seulement, pas d'ordre total).

### 3.4 Pic de clôture

L'autoscaling corrige une montée progressive, pas un effet de troupe : détection 15–60 s plus
démarrage de pod 30–90 s, quand le pic dure 120 s. Le renfort arrive après.

Trois leviers, par ordre d'efficacité :

1. **Supprimer le pic.** Au second prix scellé, enchérir tard n'apporte rien, et la règle de
   priorité temporelle récompense même la déclaration précoce. Le pic est comportemental, pas
   structurel : à communiquer agressivement.
2. **Découpler arrivée et service.** Réponse 202 immédiate, mise en file, traitement à débit
   constant, confirmation par notification.
3. **Pré-provisionner** 30 minutes avant l'échéance, plus coupe-circuit et plafond de reprises côté
   client — un système qui réessaie sans limite transforme une surcharge passagère en panne totale.

### 3.5 Objectifs de reprise différenciés

La date du match est une contrainte physique : il n'existe pas de « on recommencera demain ».

| Phase | RPO | RTO |
|---|---|---|
| Fenêtre de déclaration | zéro | quelques heures |
| Clôture et calcul | zéro | quelques minutes |
| Paiements | zéro | une heure |

Un RPO nul impose une réplication synchrone, pas des sauvegardes nocturnes. La propriété qui rend
tout cela gérable est la **reproductibilité** : `p*` est une fonction pure des déclarations
révélées, et l'aléa de départage est journalisé avec sa graine. Vérifié par `resultIsReproducible`
(20 permutations des entrées, résultat identique).

---

## 4. Ce qui reste à faire avant une mise en production

- SLO chiffrés par phase, et non un « 99,9 % » global qui ne veut rien dire ici.
- Test de charge reproduisant le pic de clôture (k6), exécuté avant la soutenance.
- Répétition générale de la clôture sur données de test, chronométrée de bout en bout.
- Procédure de crise écrite : qui décide, qui communique, dans quel délai, avec quel message.
- Intégration PSP réelle avec vérification explicite que **aucune donnée de carte ne transite** par
  l'infrastructure — saisie en iframe hébergée par le PSP, tokens seulement, pour rester hors du
  périmètre PCI-DSS complet.
- Rachat officiel à `p*` avec réattribution en cascade : c'est ce qui permet d'affirmer « zéro
  marché noir **et** zéro siège vide », argument plus fort que la seule non-transférabilité.
- Pool solidaire par loterie (10–15 % de la capacité, prix plafonné, financé par le surplus), pour
  répondre à la critique sociale — la disposition à payer d'un supporter modeste est plafonnée par
  sa liquidité, pas par son attachement au club.
