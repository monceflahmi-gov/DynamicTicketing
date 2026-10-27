# Architecture v2

Remplace le chapitre 5 du rapport initial. Les décisions de conception du mécanisme lui-même
sont dans [`SPECIFICATION-V2.md`](SPECIFICATION-V2.md) ; ce document traite de la structure
logicielle qui les porte.

---

## 1. Le principe directeur

L'architecture v1 suivait une hypothèse implicite et répandue : le client n'est pas digne de
confiance, la plateforme l'est. Toute la sécurité était donc concentrée à la frontière — TLS,
JWT, limitation de débit — et l'intérieur était traité comme une zone sûre.

Cette hypothèse ne tient pas pour une enchère sous pli scellé. Le mécanisme de Vickrey repose
sur une propriété précise : personne ne connaît le contenu d'une enveloppe avant l'ouverture
simultanée de toutes. Une base de données où les montants sont lisibles par un DBA viole cette
propriété, et avec elle toute la justification théorique du projet.

Pire, la littérature économique identifie depuis Rothkopf, Teisberg et Kahn (1990) la raison
pour laquelle les enchères de Vickrey sont rares en pratique : **le vendeur a un intérêt direct,
mesurable et presque indétectable à tricher.** Connaissant la plus haute offre, il lui suffit
d'insérer une enchère fantôme juste en dessous pour capter presque tout le surplus du gagnant.
L'attaque est profitable, invisible — les enchères scellées ne sont jamais publiées — et
indémontrable, puisque le gagnant ne peut pas distinguer une concurrence réelle d'une
concurrence fabriquée.

L'architecture v2 part donc d'une prémisse inverse :

> **L'exploitant n'est pas une partie de confiance.** Les garanties doivent tenir même si la
> plateforme entière est hostile.

Ce renversement n'est pas un supplément de sécurité : c'est ce qui rend le mécanisme économique
applicable. C'est aussi ce qui explique les quatre composants nouveaux de la v2.

---

## 2. Vue logique des composants

Voir `uml/architecture-v2-composants.png`.

### 2.1 Ce qui a changé

| Composant | État | Rôle |
|---|---|---|
| **Merkle Service** | nouveau | Journal en ajout seul, preuves d'inclusion, ancrage horaire |
| **Reveal Service** | nouveau | Vérifie les révélations, saisit les cautions |
| **Ordonnanceur** | nouveau | Déclenche réconciliation, scellement, calcul, captures |
| **KMS / HSM** | nouveau | Détient la clé HMAC des CIN, hors d'atteinte des services |
| **Autorité d'horodatage** | nouveau | Date la racine Merkle pendant la fenêtre |
| Pricing Engine | modifié | Fonction pure, statistiques d'ordre déléguées à PostgreSQL |
| Bidding Service | modifié | Reçoit des condensés, plus des montants |
| Auth Service | modifié | WebAuthn/FIDO2 ; plus aucune biométrie stockée |
| Payment Service | modifié | Captures par lots idempotents, cascade sur échec |
| Redis | rôle réduit | Cache non critique uniquement |

### 2.2 Le client porte désormais la cryptographie

Inversion caractéristique de cette architecture : le secret qui protège le mécanisme est confié
au client, pas au serveur. L'application tire le nonce, calcule l'engagement, conserve la preuve
d'inclusion et vérifie elle-même son appartenance à la racine publiée. Le montant ne quitte
jamais l'appareil avant la clôture.

Conséquence opérationnelle à ne pas négliger : si le nonce est perdu, la révélation devient
impossible et la caution est saisie. Deux parades se cumulent — révélation automatique en tâche
de fond dès la clôture, et escrow chiffré du nonce dont la clé n'est libérée qu'à la clôture,
ce qui couvre la perte d'appareil sans rompre le scellement.

### 2.3 Le rôle réduit de Redis

En v1, Redis portait les clés d'idempotence et la courbe de demande. Les deux usages
disparaissent :

- Les clés d'idempotence passent en PostgreSQL. Redis est volatil par défaut ; un redémarrage ou
  une éviction perdrait la clé et rouvrirait la fenêtre de double débit.
- La courbe de demande n'existe plus pendant la fenêtre, les montants étant scellés y compris
  pour l'exploitant.

Il ne reste que du cache non critique : listes de matchs, grilles de catégories, sessions.

### 2.4 Une conséquence fonctionnelle à assumer

**L'administrateur ne voit plus la courbe de demande en temps réel.** Il ne dispose que du
nombre d'engagements déposés, qui est d'ailleurs public — et dont la publicité est utile, car
elle rend une déclaration surnuméraire arithmétiquement détectable au moment du calcul.

C'est une perte de confort réelle, et elle est délibérée. Un administrateur qui voit la
distribution des montants pendant la fenêtre peut calculer `p*` par anticipation ; combiné au
pouvoir de clôturer, cela constitue une capacité de manipulation totale. Mieux vaut l'annoncer
que la laisser découvrir en soutenance.

---

## 3. Frontières de confiance

Voir `uml/architecture-v2-confiance.png`.

Quatre zones, et une seule question pour chacune : que peut faire un adversaire qui la contrôle ?

**Zone 1 — l'appareil du supporter.** Détient la clé privée WebAuthn, le nonce et la preuve
d'inclusion. Un adversaire qui la contrôle compromet *ce supporter*, jamais le mécanisme.

**Zone 2 — le réseau public.** TLS 1.3. Cette zone était la seule réellement traitée en v1.

**Zone 3 — la plateforme, explicitement non digne de confiance.** Microservices, PostgreSQL,
administrateurs, DBA, comptes de service. Ce qu'elle détient : des condensés HMAC. Ce qu'elle ne
peut pas faire :

- lire un montant avant la révélation — le nonce est en zone 1 ;
- énumérer les CIN — la clé HMAC est en zone 4 ;
- réécrire le journal — la racine est déjà datée en zone 4 ;
- insérer une enchère fantôme — le nombre d'engagements est public et daté.

**Zone 4 — les racines de confiance externes.** Autorité d'horodatage, KMS/HSM, publication de
la racine. C'est la seule zone dont la compromission casserait les garanties, et c'est
précisément pourquoi elle est hors du contrôle de l'exploitant.

Le critère qui résume l'ensemble : une garantie fondée sur « nous promettons de ne pas regarder »
est une politique interne, pas une garantie de sécurité. Une vraie garantie est celle que
l'exploitant lui-même ne peut pas violer, parce que les mathématiques le lui interdisent.

---

## 4. Vue de déploiement

Voir `uml/architecture-v2-deploiement.png`.

### 4.1 Trois familles de charge, trois stratégies

**Services sans état, autoscalés.** Auth, Match, Ticket, Notification. HPA classique sur CPU et
latence.

**Traitements singleton.** Ordonnanceur, Pricing Engine, Merkle Service. Réplique unique
assumée, avec verrou ShedLock. Un `@Scheduled` déployé sur N pods se déclenche N fois, et deux
calculs concurrents de `p*` produiraient deux allocations divergentes pour le même match. Le
verrou garantit l'exécution unique même pendant un déploiement progressif.

**Travailleurs de lot.** Reveal et Capture. Horizontalement scalables, chaque lot étant une
transaction courte et idempotente.

### 4.2 Le pic de clôture

L'autoscaling corrige une montée progressive, pas un effet de troupe : détection de 15 à 60
secondes, plus démarrage de pod et de JVM de 30 à 90 secondes, quand le pic dure environ 120
secondes. Le renfort arrive après. D'où trois mesures, par ordre d'efficacité :

1. **Supprimer le pic.** Au second prix scellé, enchérir tard n'apporte rien, et la règle de
   priorité temporelle récompense même la déclaration précoce. Le pic est comportemental, pas
   structurel : c'est à communiquer agressivement.
2. **Découpler arrivée et service.** Réponse 202 immédiate, mise en file, traitement à débit
   constant.
3. **Pré-provisionner** 30 minutes avant l'échéance, plutôt que de monter en réaction.

La limitation de débit s'applique par compte authentifié et par empreinte d'appareil, **pas par
adresse IP** : le CGNAT mobile marocain fait partager une poignée d'adresses publiques à des
milliers d'abonnés, de sorte qu'un seuil bas bloquerait massivement des utilisateurs légitimes
et qu'un seuil haut ne protégerait de rien.

### 4.3 Persistance et objectifs de reprise

La date du match est une contrainte physique : il n'existe pas de « on recommencera demain ».

| Phase | RPO | RTO |
|---|---|---|
| Fenêtre de déclaration | zéro | quelques heures |
| Clôture et calcul | zéro | quelques minutes |
| Paiements | zéro | une heure |

Un RPO nul impose une **réplique synchrone**, pas des sauvegardes nocturnes : perdre une
déclaration, c'est fausser `p*` et léser un supporter identifiable.

La propriété qui rend la reprise gérable est la reproductibilité. `p*` est une fonction pure des
déclarations révélées, et l'aléa de départage est journalisé avec sa graine : le calcul peut être
rejoué autant de fois que nécessaire, et donne rigoureusement le même résultat. C'est aussi ce
qui permet de le défendre devant une contestation.

---

## 5. Découpage en couches du code

```
pricing.domain      ── aucune dépendance au-delà du JDK
   AllocationEngine, Category, RankedBid, BidOutcome, AllocationResult
   ↑ testable sans Spring ni base, rejouable à l'audit

pricing.merkle      ── SHA-256 seul
pricing.commit      ── HMAC seul
security            ── délègue au KMS par une interface

pricing (services)  ── Spring : orchestration, transactions, dépôts
payment             ── Spring : idempotence, PSP
```

L'absence de dépendance du paquet `domain` n'est pas une coquetterie : elle garantit que le
calcul du prix reste une fonction pure. Un moteur qui lirait l'heure, interrogerait la base ou
tirerait un aléa non fourni en entrée cesserait d'être rejouable, et deviendrait indéfendable en
cas de litige.

---

## 6. Pile technique

Inchangée pour l'essentiel, avec les ajouts imposés par les corrections.

| Couche | Technologie | Changement v2 |
|---|---|---|
| Langage | Java 21 (LTS) | — |
| Framework | Spring Boot 3.3 | — |
| Passerelle | Spring Cloud Gateway | limitation par compte, pas par IP |
| Sécurité | Spring Security, OAuth2 | WebAuthn/FIDO2 en remplacement de la biométrie serveur |
| Persistance | Spring Data JPA, Flyway | index de classement, journal en ajout seul |
| Base | PostgreSQL 16 | réplique synchrone, RPO nul |
| Cache | Redis 7 | rôle réduit au cache non critique |
| Événements | Apache Kafka | — |
| Ordonnancement | Spring Scheduling + **ShedLock** | nouveau : exécution unique garantie |
| Clés | **AWS KMS ou HSM** | nouveau : clé HMAC non extractible |
| Horodatage | **TSA RFC 3161** | nouveau : ancrage externe de la racine |
| Observabilité | Actuator, Micrometer, Prometheus, Grafana, Loki | — |
| Frontend | React 18 + Vite, React Native | calcul cryptographique côté client |
| Conteneurs | Docker, Kubernetes | pré-provisionnement planifié |

---

## 7. Ce que l'architecture ne couvre pas encore

- **Intégration PSP réelle**, avec vérification explicite qu'aucune donnée de carte ne transite
  par l'infrastructure : saisie en iframe hébergée par le PSP, jetons seulement, pour rester hors
  du périmètre PCI-DSS complet.
- **SLO chiffrés par phase**, plutôt qu'un « 99,9 % » global qui ne veut rien dire ici.
- **Test de charge** reproduisant le pic de clôture, et répétition générale de la clôture
  chronométrée de bout en bout.
- **Procédure de crise écrite** : qui décide, qui communique, dans quel délai, avec quel message.
- **Rachat officiel à `p*`** avec réattribution en cascade, qui est ce qui permettrait d'affirmer
  « zéro marché noir *et* zéro siège vide ».
- **Pool solidaire par loterie**, financé par le surplus, pour répondre à la critique sociale : la
  disposition à payer d'un supporter modeste est plafonnée par sa liquidité, pas par son
  attachement au club.
