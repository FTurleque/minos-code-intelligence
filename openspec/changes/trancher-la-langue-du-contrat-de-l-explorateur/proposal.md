# Proposal

## Why

Constat **AUD-QUA-03** de l'audit du 2026-11-15 (sprint 8) : le contrat v1 de l'explorateur mêle deux langues. Les états de projection (`ABSENTE`, `PRETE`, `PERIMEE`, `ECHOUEE`) et de divergence (`IDENTIQUE`, `DIFFERENTE`, `SOURCE_INTROUVABLE`, `NON_COMPARABLE`) sont en français ; l'état d'index (`READY`, `STALE`…), les refus, les modes de recherche et les codes d'erreur sont en anglais. La page, en français, affiche tel quel le champ `message` des erreurs de l'API, rédigé en anglais.

Ces valeurs sont sérialisées par trois surfaces versionnées : l'API HTTP `/api/v1/` de l'explorateur, l'API Java `com.minos.api` et la sortie JSON de `minos semantic projection`. Une surface publique est additive : la langue se tranche par une décision écrite, pas par un renommage dans v1.

## What Changes

- Une décision écrite (ADR, design D1) fixe la règle : toute constante nouvelle d'un contrat public est en anglais ; les huit constantes françaises du contrat v1 restent telles quelles et forment une liste close ; leur équivalent anglais n'est publié que par une version suivante du contrat.
- Un test de contrat fige, champ par champ, les valeurs que les trois surfaces sérialisent en v1, nature de document comprise ; une valeur ajoutée ou retirée est une édition visible. Le champ `limitations`, liste ouverte de chaînes, n'en fait pas partie.
- Les erreurs de l'API de l'explorateur portent un `code` pris dans une liste fermée tenue par un test ; le champ `message` reste, en anglais, comme diagnostic.
- La page traduit le `code` par un libellé français et n'affiche plus `message` ; un code sans libellé donne un message générique qui cite le code.
- Aucune valeur, aucun champ, aucune route n'est renommé ni retiré.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `exploration-semantique` : trois exigences ajoutées (valeurs v1 figées, code d'erreur de liste fermée, traduction par la page). Aucune exigence existante n'est modifiée.

## Hors périmètre

- Le renommage des constantes dans v1 (action littérale de l'audit) : exclu par la règle des surfaces additives.
- La publication d'un contrat v2 (`/api/v2/`) : option décrite en D1, non spécifiée ici.
- Les statuts HTTP, les causes de refus et la journalisation des erreurs (sprint 3, `fiabiliser-la-surface-de-l-explorateur`, `distinguer-l-erreur-d-usage-de-l-echec-interne`).
- L'exécution d'`app.js` par des tests (AUD-TST-02, sprint 1, `executer-le-front-de-l-explorateur-en-test`), dont ce changement utilise le harnais.
- Les optimisations de la carte (sprint 4, `rendre-le-survol-de-la-carte-independant-du-nombre-de-points`).
- La langue des messages de la CLI et du MCP.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Constantes françaises : état de projection et divergence | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticProjectionService.java:177`, `SemanticCorpusQueryService.java:456` |
| Constantes anglaises dans le même contrat | [Existant vérifié] | `SemanticProjectionService.java:180`, `SemanticIndexService.java:266`, `SemanticCorpusQueryService.java:574`, `:592` |
| Sérialisation par `.name()` dans l'API HTTP et la CLI | [Existant vérifié] | `minos-explorer/src/main/java/com/minos/explorer/ExplorerApi.java:192`, `:298` ; `minos-cli/src/main/java/com/minos/cli/command/SemanticProjectionCommand.java:101`, `:118` |
| Les erreurs portent déjà `code` et `message` | [Existant vérifié] | `ExplorerApi.java:74-76` |
| La page affiche `message` | [Existant vérifié] | `minos-explorer/src/main/resources/com/minos/explorer/web/app.js:99`, `:124` |
| Aucune release ne contient `minos-explorer` | [Existant vérifié] | `git log --oneline v1.3.0 -- minos-explorer` : 0 commit ; 11 depuis |
| Anglais pour le neuf, v1 figé | [Proposition] | design D1, à trancher par le propriétaire |

## Impact

- **Modules du reactor** : `minos-explorer` (page, test de contrat), `minos-api` et `minos-cli` (tests de contrat seulement). Aucune énumération de `minos-application` n'est modifiée.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : additif ; aucune valeur sérialisée ne change. MCP, IntelliJ et NEXUS non concernés.
- **ADR** : un ADR à écrire, domaine ARCH, prochain numéro libre à l'écriture : « Langue des constantes des contrats publics » (design D1).
- **Gates à rejouer** : `scripts/quality/check-explorer-front.py` (créé par le sprint 1), `scripts/quality/check-comment-language.py`, `scripts/docs/check-current-docs.py`, `scripts/docs/check-capability-dossiers.py`. Scope JaCoCo de `minos-explorer` à relire dans `scripts/quality/check-jacoco.py` (aucun code Java de production ajouté hors constante de liste).
- **Plateformes** : Windows et Linux — aucun comportement propre à une plateforme.
- **Documentation durable et diagrammes** : `docs/user/semantic-explorer.md`, `docs/developer/semantic-explorer.md`, `docs/user/cli.md` (valeurs citées), dossier `docs/architecture/diagrams/capacites/exploration-semantique.md`.

## Questions ouvertes

- Bloquante avant implémentation : D1 (trois options, recommandation dans le design). Les specs décrivent l'option recommandée ; elles restent vraies si le propriétaire choisit en plus de publier un contrat v2.
