## ADDED Requirements

### Requirement: La documentation courante de la façade décrit son état en vigueur
Hors des relevés figés (`docs/project/history/`, `docs/project/audits/`, archives), `docs/` et les sources de production de `minos-application` SHALL ne présenter `MinosApplication` ni comme une racine de composition, ni comme un accès public à `compositionRoot()`, et SHALL ne recopier le statut d'aucun ADR à côté d'un lien vers lui. La ligne d'ARCH-0087 de l'index des ADR SHALL citer les trois changements que l'ADR nomme.

#### Scenario: Prose en vigueur
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -nE 'Long-lived composition root|composition root long-lived|via .MinosApplication\.compositionRoot\(\).|ARCH-0087\]\([^)]*\) \(Proposed\)' -- docs minos-application/src/main ':!docs/project/history' ':!docs/project/audits' ':!**/archive/**'` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1

#### Scenario: Ancienne description réintroduite
- **GIVEN** une copie de travail où le Javadoc de classe de `MinosApplication` redevient « Long-lived composition root for one MINOS home »
- **WHEN** la même commande est exécutée
- **THEN** elle rend cette ligne et se termine avec le code 0, ce que la revue refuse

#### Scenario: Index des ADR complet pour ARCH-0087
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -n 'retirer-la-racine-de-composition-de-la-facade' -- docs/architecture/adr/README.md` est exécuté
- **THEN** la commande rend la ligne d'ARCH-0087 et se termine avec le code 0

### Requirement: La table de rupture de la façade ne promet aucun remplaçant absent
Dans `docs/user/java-api.md`, chaque remplaçant d'un accesseur retiré de `MinosApplication` SHALL être une méthode publique qui rend ce service. `retentionService()` SHALL être documenté sans remplaçant à la demande, la rétention suivant chaque indexation et chaque import qui publie un snapshot.

#### Scenario: Rétention sans remplaçant promis
- **GIVEN** le dépôt après correction
- **WHEN** `git grep -nE 'retentionService\(\)` \| `new LocalProjectOperations' -- docs/user/java-api.md` est exécuté
- **THEN** la commande ne rend aucune ligne et se termine avec le code 1

#### Scenario: Promesse réintroduite
- **GIVEN** une copie de travail où la ligne `retentionService()` de la table redonne `new LocalProjectOperations(application)` comme remplaçant
- **WHEN** la même commande est exécutée
- **THEN** elle rend cette ligne et se termine avec le code 0, ce que la revue refuse
