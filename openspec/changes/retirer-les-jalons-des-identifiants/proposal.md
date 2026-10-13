# Proposal

## Why

**AUD-QUA-08** (faible, sprint 11 de l'audit du 10 octobre 2026). L'ADR 0043 a retiré les numéros de jalon des gates permanents ; le code de production en garde dans ses identifiants. Le nom ne dit pas ce que la méthode garantit : il faut connaître l'histoire des jalons pour savoir laquelle appeler.

Décompte au HEAD, plus large que celui de l'audit :

- **Plugin IntelliJ** : `MinosM21Actions` et `MinosM21Client` (plus `MinosM21ClientTest`), et **8** actions de `minos-intellij/src/main/resources/META-INF/plugin.xml:39-49` déclarées par `class="…MinosM21Actions$…"`.
- **Catalogue des providers** : `qualifiedM17Providers`, `qualifiedM17Descriptors`, `qualifiedM24Providers`, `qualifiedM24Descriptors` (`ScipIndexerCatalog.java:50,58,63,72` ; l'audit omettait les deux `…Descriptors`), et `qualifiedM1Descriptors` (`:41`, un seul chiffre, cité ni par l'audit ni par sa contre-vérification).
- **Descripteurs de build** : `M17_DEFAULT_FILE_NAMES`, `M24_ADDITIONAL_FILE_NAMES`, `m17Defaults()`, `m24Defaults()` (`BuildDescriptorPolicy.java:15,29,54,59`), appelés dans trois autres classes de production.
- **Non cités par l'audit** : quatre variables d'environnement `MINOS_M14_*` internes au runner PowerShell de `minos-provider-scip` ; des **chaînes publiques** qui citent un jalon (descriptions de trois outils MCP, limitations du catalogue, trois messages) ; 74 lignes de commentaires.

Rien ne garde la convention : aucun gate ne lit les identifiants de `src/main`.

## What Changes

- **Renommage par capacité** des classes, méthodes et constantes ci-dessus, avec leurs appelants et leurs tests, à comportement constant.
- **`plugin.xml`** : seuls les attributs `class=` changent ; les identifiants d'action `id="Minos.*"` sont conservés (raccourcis et personnalisations des utilisateurs).
- **`scripts/docs/product-facts.py`** (`:60` cherche littéralement deux des noms) mis à jour dans la même tâche.
- **Variables `MINOS_M14_*`** renommées dans le runner, avec validation sous Windows.
- **Un gate à liste décroissante** refuse un jalon dans un nom de fichier ou une déclaration de `src/main`.
- **Chaînes publiques** : relevées et soumises à décision, **non modifiées** par défaut.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `audit-qualite-code` : deux exigences ajoutées (aucun identifiant de production ne porte un numéro de jalon ; retirer un jalon d'un identifiant ne change aucune surface publique).
- `client-intellij` : l'exigence « Une seule tâche d'arrière-plan sert toutes les actions du plugin » nomme « les actions M21 » ; son texte est réécrit sans jalon, à portée égale.

## Hors périmètre

- Les chaînes publiques qui citent un jalon (descriptions MCP, limitations du catalogue, messages d'erreur) tant que D3 n'est pas tranchée.
- Les 74 lignes de commentaires : non gardées ici ; elles relèvent du changement `fixer-la-langue-des-commentaires` et du fil de l'eau.
- Les classes de **test** nommées par jalon (`M17ProviderPlatformTest`, `M24PolyglotProviderTest`, etc.) : leurs appels sont mis à jour, leurs noms ne changent pas.
- Les noms de scopes JaCoCo à jalon (`m24-polyglot-provider-platform`, etc.) et les dossiers `scripts/mNN/` gelés par assertion (ADR 0043).
- `scripts/history/` (historique : `check-m21-parity.py` y lit les anciens chemins et n'est pas exécuté).

## Impact

- **Modules du reactor** : `minos-provider-scip`, `minos-engine`, `minos-storage-local`, `minos-bootstrap` (appelant), tests de `minos-app` ; hors reactor : `minos-intellij`.
- **Surfaces publiques** : aucune sortie ne change. CLI, API Java (`minos-api` n'expose aucun des noms), MCP : identiques ; IntelliJ : identifiants d'action identiques ; NEXUS : sans effet.
- **ADR** : complète la « Mise en œuvre » de l'ADR 0043 (convention étendue aux identifiants de production) ; aucun ADR nouveau.
- **Gates à rejouer** : `python scripts/docs/product-facts.py --check`, `python scripts/docs/check-current-docs.py`, `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` (gate neuf câblé), `python scripts/architecture/check-module-boundaries.py`, scope JaCoCo `m24-polyglot-provider-platform` (`scripts/quality/check-jacoco.py`), baseline SpotBugs (`quality/spotbugs-baseline/`), gate du plugin (`minos-intellij/gradlew check`).
- **Plateformes** : Windows et Linux ; le runner PowerShell ne se valide que sous Windows.
