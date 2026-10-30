# Proposal

## Why

**AUD-QUA-08** (faible, sprint 11 de l'audit du 10 octobre 2026). L'ADR 0043 interdit un numéro de jalon dans le nom d'un gate permanent ; le code de production en garde dans ses identifiants. Le nom ne dit pas ce que la méthode garantit : il faut connaître l'histoire des jalons pour savoir laquelle appeler.

Inventaire au HEAD `5ce7b79b` (méthode reproductible dans `design.md`), plus large que celui de l'audit — 2 fichiers, 16 identifiants, 8 références `class=` :

- **Plugin IntelliJ** : `MinosM21Actions` et `MinosM21Client` (plus `MinosM21ClientTest`) ; 8 des 16 actions de `plugin.xml` déclarées par `class="…MinosM21Actions$…"`.
- **Catalogue des providers** (`ScipIndexerCatalog`) : **six** méthodes — `qualifiedM1Descriptors`, `registerQualifiedM1`, `qualifiedM17Providers`, `qualifiedM17Descriptors` (sans aucun appelant), `qualifiedM24Providers`, `qualifiedM24Descriptors` ; l'audit n'en citait que deux.
- **Descripteurs de build** (`BuildDescriptorPolicy`) : `M17_DEFAULT_FILE_NAMES`, `M24_ADDITIONAL_FILE_NAMES`, `m17Defaults()`, `m24Defaults()`, appelés dans trois autres classes de production.
- **Variables d'environnement** : quatre `MINOS_M14_*`, internes au runner PowerShell de `minos-provider-scip`.
- **Non renommés ici** : 39 lignes de chaînes publiques qui citent un jalon (descriptions MCP, limitations du catalogue figées par quatre goldens, messages) et 112 lignes de commentaires.

Rien ne garde la convention : aucun gate ne lit les identifiants de `src/main`.

## What Changes

- **Renommage par capacité** des classes, méthodes, constantes et variables ci-dessus, avec leurs appelants et leurs tests, à comportement constant ; `qualifiedM17Descriptors`, sans appelant, est supprimée.
- **`plugin.xml`** : seuls les huit attributs `class=` changent ; les identifiants d'action `id="Minos.*"` sont conservés (raccourcis et personnalisations des utilisateurs).
- **`scripts/docs/product-facts.py`** (`:60` cherche deux des anciens noms) mis à jour dans la même tâche.
- **Un gate à liste décroissante** (`scripts/quality/check-milestone-identifiers.py`) refuse un segment à jalon dans un nom de fichier, un identifiant, un attribut `class=` ou une variable d'environnement de `*/src/main/**`.
- **Chaînes publiques** : relevées et soumises à décision (D3), **non modifiées** par défaut.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `audit-qualite-code` : deux exigences ajoutées (aucun identifiant de production ne porte un numéro de jalon ; retirer un jalon d'un identifiant ne change aucune surface publique).
- `client-intellij` : l'exigence « Une seule tâche d'arrière-plan sert toutes les actions du plugin » nomme « les actions M21 » ; son texte est réécrit sans jalon, à portée égale.

## Hors périmètre

- **Plugin IntelliJ** : les identifiants d'action `Minos.*` et les libellés de menu sont conservés.
- Les **chaînes publiques** qui citent un jalon (descriptions MCP, limitations et diagnostics du catalogue, messages d'erreur, en-tête de manifeste) tant que D3 n'est pas tranchée ; les valeurs de données `"M3"` et `"M5"` (version d'`Origin`) dans tous les cas.
- Les **commentaires citant un jalon** (111 lignes au HEAD `349e4246`) : rattachés à `fixer-la-langue-des-commentaires` par décision du propriétaire du 2026-10-24 ; ce changement n'en réécrit aucun.
- Les **21 classes de test** nommées par jalon (`M17ProviderPlatformTest`, `M24PolyglotProviderTest`, etc.) : leurs appels sont mis à jour, leurs noms ne changent pas (`check-polyglot-provider-consistency.py` en exige quatre par chemin).
- Les **9 scopes JaCoCo** à jalon (`m24-polyglot-provider-platform`, etc.), les **sorties des gates** (« M21 MODULE BOUNDARY … ») et le chemin de sortie `target/m21-quality/` : ce sont des données et des sorties de gates, citées par la CI, `AGENTS.md`, `openspec/config.yaml` et la documentation.
- Les **fichiers livrés hors `src/main`** : `docker/scripts/configure-m30-docker-services.ps1`, la variable `$M30Services` de `configure-docker-mcp.ps1`, le workflow `m0-java-ci.yml`.
- Les dossiers `scripts/mNN/` gelés par assertion (ADR 0043) et `scripts/history/` (`check-m21-parity.py` y lit les anciens chemins et n'est pas exécuté).

## Impact

- **Modules du reactor** : `minos-provider-scip`, `minos-engine`, `minos-storage-local`, `minos-bootstrap` (appelant) ; tests de `minos-application` et `minos-app` ; hors reactor : `minos-intellij`.
- **Surfaces publiques** : aucune sortie ne change. CLI, MCP : identiques ; API Java : `com.minos.api` ne nomme aucun des identifiants (les méthodes internes renommées sont signalées dans « Ruptures » de `docs/user/java-api.md`, sans alias, comme pour l'ADR 0044) ; IntelliJ : identifiants d'action identiques ; NEXUS : sans effet. Aucune variable renommée n'est documentée.
- **ADR** : complète la « Mise en œuvre » de l'ADR 0043 (convention étendue aux identifiants de production) ; aucun ADR nouveau.
- **Gates à rejouer** : `python scripts/docs/product-facts.py --check` (attente modifiée), `python scripts/docs/check-current-docs.py`, gate neuf `python scripts/quality/check-milestone-identifiers.py` et son `--self-test`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-milestone-artifact-references.py`, `python scripts/architecture/check-module-boundaries.py`, `python scripts/quality/check-jacoco.py` (scope `m24-polyglot-provider-platform`, préfixes inchangés), baseline SpotBugs (`quality/spotbugs-baseline/`, aucune entrée concernée), gate du plugin (`minos-intellij/gradlew check`, plancher JaCoCo du paquet `protocol`), goldens de caractérisation inchangés.
- **Plateformes** : Windows et Linux ; le runner PowerShell ne s'exécute que sous Windows (test de cohérence sur les deux, exécution manuelle sous Windows).
