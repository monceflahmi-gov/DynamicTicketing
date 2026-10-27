# Diagrammes UML — version 2

Sources PlantUML éditables (`.puml`) et rendus (`.png`, `.svg`). Les SVG sont vectoriels :
préférez-les pour l'insertion dans le rapport, ils restent nets à toute échelle.

| Fichier | Type | Contenu |
|---|---|---|
| `use-case-v2-supporter` | Cas d'utilisation | Supporter et abonné : engagement scellé, modification, preuve d'inclusion, confirmation de venue |
| `use-case-v2-systeme` | Cas d'utilisation | Ordonnanceur et administrateur : réconciliation, scellement, calcul, ancrage, captures |
| `class-domain-v2` | Classes | Noyau de tarification — `AllocationEngine`, `Category`, `RankedBid`, `BidOutcome`, `AllocationResult` |
| `class-security-v2` | Classes | Scellement et transparence — `BidCommitment`, `MerkleTree`, `InclusionProof`, `CinPseudonymizer`, `RotatingTicketCode` |
| `class-orchestration-v2` | Classes | Orchestration et persistance — `ClosureOrchestrator`, dépôts, entités, `IdempotencyGuard` |
| `sequence-v2-engagement` | Séquence | Phase 1 : dépôt d'un engagement scellé, preuve d'inclusion, ancrage horaire |
| `sequence-v2-cloture` | Séquence | Phase 2 : réconciliation, scellement, révélations, calcul de p*, allocation, captures |
| `architecture-v2-composants` | Composants | Vue logique : services, données, externes, et ce qui change en v2 |
| `architecture-v2-confiance` | Composants | Frontières de confiance : les quatre zones et les racines externes |
| `architecture-v2-deploiement` | Déploiement | Topologie d'exécution, répliques, singletons, réplication synchrone |

Convention de couleur sur les cas d'utilisation : vert = nouveau en v2, orange = modifié en v2,
blanc = inchangé depuis la v1.

## Régénérer après modification

```bash
java -jar plantuml.jar -charset UTF-8 -tsvg *.puml
java -jar plantuml.jar -charset UTF-8 -tpng *.puml
```

PlantUML se télécharge sur plantuml.com, ou s'installe via l'extension du même nom dans
VS Code et IntelliJ, qui affiche l'aperçu en direct pendant l'édition.

## Ce qui a changé depuis la v1

**Cas d'utilisation.** « Soumettre une disposition à payer » devient « Déposer un engagement
scellé », et une phase de révélation apparaît. Deux cas sont nouveaux côté supporter : recevoir
puis vérifier sa preuve d'inclusion. Un acteur système, l'ordonnanceur, reprend la clôture, qui
n'est plus un geste d'administrateur — celui-ci ne conserve qu'une clôture en mode incident sous
double validation. L'administrateur ne voit plus la courbe de demande pendant la fenêtre, les
montants étant scellés y compris pour lui : seul le nombre d'engagements reste visible, et il est
public. Deux acteurs externes s'ajoutent, l'autorité d'horodatage et le service WebAuthn, ce
dernier remplaçant la biométrie côté serveur.

**Classes.** Le diagramme est désormais tiré du code réellement implémenté. Le noyau de
tarification ne dépend que du JDK, ce qui le rend testable sans Spring ni base, et rejouable à
l'audit. Les trois vues séparent le calcul, la cryptographie et l'orchestration.

**Séquence.** Le flux unique de la v1 se scinde en deux : l'engagement pendant la fenêtre, puis la
clôture en cinq étapes à transactions courtes. Les frontières transactionnelles et les verrous
sont explicites, parce que c'est précisément là que la v1 était fautive.
