# Proposal

## Why

Le constat **AUD-QUA-02** (Moyenne, Qualité) de l'audit `docs/project/audits/2026-11-15/` (sprint 3) relève qu'un
échec interne du serveur MCP, de la CLI, de l'API Java ou du worker WSL ne laisse aucune trace exploitable :

- le MCP n'écrit que la classe de l'exception et celle de sa cause racine
  (`minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java:259-263`) ;
- le worker WSL réduit l'échec à son message ou au nom simple de sa classe
  (`minos-runtime-local/src/main/java/com/minos/runtime/local/wsl/WslLinuxWorker.java:115-116`, `:180-186`), et son
  stderr est jeté par le lanceur (`ProcessWslCommandRunner.java:36`) ;
- aucun réglage de diagnostic n'existe : `MINOS_DEBUG`, `--debug`, `--verbose`, `stackTrace` et `printStackTrace`
  ont zéro occurrence dans les sources de production.

Pour un `NullPointerException`, il ne reste ni classe d'origine, ni ligne, ni pile : le seul diagnostic est la
reproduction. Le sprint 3 pose cette observabilité **avant** les sprints 4 à 8, qui changent des chemins chauds et des
frontières. La partie « explorateur » du constat est traitée par le changement
`fiabiliser-la-surface-de-l-explorateur`, qui réutilise le composant livré ici.

## What Changes

- **Ligne de diagnostic enrichie** (stderr, toujours) : tout échec interne opaque du MCP écrit, en plus de l'outil, de
  la classe et de la classe de la cause racine, la **première trame MINOS de la cause racine** (`Classe.méthode:ligne`)
  et une **référence de corrélation** ; jamais de message d'exception ni de valeur d'argument.
- **Journal de diagnostic privé, désactivé par défaut** : activé par `MINOS_DIAGNOSTIC_LOG=enabled` (ou la propriété
  `minos.diagnostic.log`), il reçoit sous `MINOS_HOME/logs/` la pile de chaque échec interne du MCP, de la CLI, de
  l'API Java et du worker WSL (côté Windows), sous la même référence : un fichier privé par échec, écrit par les
  primitives d'E/S privées existantes, en nombre borné, sans chemin absolu ni secret (un message d'exception jugé
  sensible par la politique publique est retenu). Les surfaces l'atteignent par une façade de `minos-application`.
- **Référence dans l'erreur opaque** (additif) : l'erreur générique du MCP et, quand le journal est actif, l'échec
  d'exécution rendu par le cadre commun des commandes de la CLI ou par son lanceur portent la référence, pour relier
  la réponse à la trace.
- **Worker WSL** : avant sa ligne de statut `FAILED`, le worker émet une ligne de diagnostic structurée (classes,
  trames, référence ; aucun message, aucun chemin) que l'installation Windows valide puis journalise. Le protocole de
  requête `minos-wsl-worker-v1` est inchangé.
- **Journal indisponible** (répertoire de données en lecture seule, plan de requête Docker) : la surface répond à
  l'identique ; l'indisponibilité est dite une fois sur stderr.
- **ADR** `SEC-<prochain numéro libre>` (numéro suivant lu dans l'index, non réservé) : journal de diagnostic privé et opt-in, ce qu'il
  peut contenir, où il vit, pourquoi il n'est pas un état de la lecture seule (ARCH-0017).
- **Documentation** : page utilisateur et skill `minos-incident-triage`.

## Capabilities

### New Capabilities

Aucune : ce que MINOS laisse voir d'un échec relève de `journalisation` ; le rendu de l'erreur de `surfaces-publiques` ;
le diagnostic du worker de `indexation-java-via-wsl`.

### Modified Capabilities

- `journalisation` : exigences **ajoutées** (origine et référence dans la ligne de diagnostic ; journal privé désactivé
  par défaut ; contenu sans chemin ni secret ; borne en nombre d'entrées et en profondeur ; écriture par les
  primitives privées ; indisponibilité sans effet sur la réponse).
- `surfaces-publiques` : exigences **ajoutées** (référence de corrélation dans l'erreur opaque du MCP et de la CLI ;
  échec interne de l'API Java tracé sans changer l'exception publique).
- `indexation-java-via-wsl` : exigence **ajoutée** (diagnostic structuré du worker, validé avant d'être journalisé).

Aucune exigence existante n'est modifiée.

## Hors périmètre

- L'explorateur (`ExplorerServer`, `ExplorerApi`) : changement `fiabiliser-la-surface-de-l-explorateur` (même
  sprint), qui branche la surface sur le composant de ce changement.
- La requalification d'une `IllegalArgumentException` en erreur d'usage (AUD-QUA-01) : changement
  `distinguer-l-erreur-d-usage-de-l-echec-interne`.
- Un journal privé **dans la distribution WSL** : il exigerait un champ nouveau dans la requête du worker, dont le jeu
  de clés est fermé (`WslWorkerRequest.java:62`, `:114`) ; la trace du worker est tenue côté Windows.
- Un journal privé **en Docker** : les plans de requête montent `MINOS_HOME` en lecture seule
  (`docker/compose-mcp.connected.yaml:137-141`) ; la capacité y est déclarée non supportée, la ligne stderr enrichie
  reste disponible par `docker compose logs`. Un volume dédié est une décision du propriétaire (design, D6).
- Les commandes de la CLI qui rendent elles-mêmes le code d'erreur d'exécution hors du cadre commun
  (`DockerRuntimeBootstrap`, `WslWorkerLaunch`, `TeamCommand`, `IdeIntelligenceCommand`, `NexusExportCommand`,
  `SemanticProjectionCommand`) : leurs échecs sont pour l'essentiel des refus attendus ; à brancher une à une si un
  besoin de diagnostic apparaît.
- Le plugin IntelliJ (pile déjà dans `idea.log`, AUD-QUA-04 du 10/10) et NEXUS.
- Les journaux WARNING existants et la liste nominative des causes de `check-private-io.py` : inchangés.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le MCP ne journalise que deux classes | [Existant vérifié] | `MinosMcpTools.java:259-263` |
| Le worker WSL perd la pile et son stderr est jeté | [Existant vérifié] | `WslLinuxWorker.java:115-116`, `:180-186` ; `ProcessWslCommandRunner.java:36` |
| Aucun réglage de diagnostic dans les sources de production | [Existant vérifié] | recherche `MINOS_DEBUG\|--debug\|--verbose\|stackTrace\|printStackTrace` sur `minos-*/src/main` : 0 résultat |
| La CLI rend toute exception d'une commande en une ligne `error:` et le code 1, sans trace | [Existant vérifié] | `minos-cli/src/main/java/com/minos/cli/support/CliCommandSupport.java:104-107` |
| L'API Java republie l'échec sans cause | [Existant vérifié] | `minos-api/src/main/java/com/minos/api/MinosApiSupport.java:59-78` |
| Aucune primitive n'ouvre un fichier en ajout ; `PrivateLocalStorage` n'est pas une primitive du garde et ses appels bruts sont plafonnés par la liste nominative | [Existant vérifié] | `scripts/architecture/check-private-io.py:8-10`, `:73-78`, `:82-103` ; `scripts/architecture/private-io-allowlist.json:129-131` |
| Un type de `minos-engine` nommé par une surface échoue au cliquet A11, tenu par deux tables | [Existant vérifié] | `scripts/architecture/check-module-boundaries.py:566-592`, `:862-865` ; `minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java:93` |
| La requête du worker a un jeu de clés fermé | [Existant vérifié] | `WslWorkerRequest.java:62`, `:114` |
| Le journal privé ne contient ni chemin absolu ni jeton | [Exigence] | critère de sortie du sprint 3 (`sprints.md:75`) ; `.claude/rules/securite.md`, règles 2 et 7 |
| Nom du réglage, emplacement, un fichier par échec, borne de 200 entrées | [Proposition] | `design.md`, D2, D4 |
| Messages d'exception non sensibles conservés dans le journal privé | [Proposition] | `design.md`, D3 |
| Référence ajoutée à l'erreur générique du MCP | [Proposition] | `design.md`, D5 |

## Impact

- **Modules du reactor** : `minos-domain` (rendu pur de la trace, `com.minos.diagnostics`), `minos-engine` (journal,
  `com.minos.io`, par les primitives existantes), `minos-application` (façade des surfaces), `minos-mcp`,
  `minos-cli`, `minos-api`, `minos-runtime-local` (worker et lecture du diagnostic). Aucun module nouveau, aucune
  dépendance Maven nouvelle, aucun type du moteur nommé par une surface.
- **Surfaces publiques** : MCP et CLI, **additif** (suffixe de référence) ; API Java : exception inchangée ;
  IntelliJ, NEXUS : aucun.
- **ADR** : `SEC-<prochain numéro libre>` à écrire (tâche 6.1).
- **Gates à rejouer** : `scripts/architecture/check-private-io.py` (aucun appel brut nouveau, liste nominative
  inchangée), `scripts/architecture/check-module-boundaries.py` (A2, A8, A11 : tables inchangées) et
  `ModuleArchitectureTest` de `minos-app`, `scripts/remediation/check-p0-p2.py` (chaîne
  `error: MINOS tool execution failed`), `scripts/quality/check-comment-language.py`,
  `scripts/quality/check-runtime-dynamic-consistency.py`, `scripts/quality/check-hosted-control-plane-consistency.py`
  et `scripts/docs/product-facts.py --check` (lisent `MinosMcpTools.java`) ; scopes JaCoCo `mcp-mapping` et
  `m19-m20-mcp-catalogue` ; SonarCloud : nouveau code ≥ 80 % par module.
- **Plateformes** : Windows et Linux, même code ; permissions privées par ACL sous Windows et mode POSIX sous Linux
  (primitives existantes) ; la partie worker ne s'exerce que sous Windows avec WSL.
- **Documentation durable et diagrammes** : dossiers `journalisation.md`, `surfaces-publiques.md`,
  `indexation-java-via-wsl.md` ; `docs/user/` (diagnostic d'un échec interne) ; skill `minos-incident-triage`.

## Questions ouvertes

Aucune bloquante. Les décisions D3, D5 et D6 du design attendent le propriétaire, chacune avec une recommandation ;
elles ne changent pas le découpage.
