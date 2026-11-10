# Proposal

## Why

AUD-SEC-04 est « fermé avec réserve » (audit `docs/project/audits/2026-10-10/`, relecture du 06/11). La réserve
qui reste : deux messages de `ScipJavaProjectJdk` exposent encore la valeur de `JAVA_HOME`, un chemin absolu qui
porte le nom de l'utilisateur et l'arborescence du poste.

- `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/plan/ScipJavaProjectJdk.java:68` :
  `Optional.of("JAVA_HOME does not contain javac: " + javaHome)`, diagnostic qui part vers `minos doctor`
  (`ManagedScipProviderRuntimeManager.java:299`) et vers la sonde WSL (`LocalWslJarChecks.java:50`).
- `ScipJavaProjectJdk.java:90` : `new IllegalStateException("JAVA_HOME does not point to a JDK containing javac: " + javaHome)`,
  levée par la fabrique de plan scip-java avant tout lancement.

Le gate `message(Path)` de `scripts/architecture/check-private-io.py` ne les voit pas : il ne reconnaît une
`String` porteuse de chemin que par les suffixes `Path|Root|Dir|Directory|File` (`check-private-io.py:142`), pas
`Home`, et il ne lit que les exceptions construites et les journaux par niveau (`:357-363`), pas un message
préparé dans un `Optional.of(...)`. Les deux sites ne tiennent aujourd'hui que par la barrière heuristique
`PublicErrorMessages`. Le propriétaire a fixé la spécification le 10/11 : gate d'abord, puis correction.

## What Changes

- Le gate `message(Path)` reconnaît une `String` déclarée ou un accesseur sans argument dont le nom finit par
  `Home` (`javaHome`, `minosHome()`), comme les suffixes existants.
- Le gate reconnaît les expressions chaînées qui rendent encore un chemin : `+ x.getParent()`, `+ x.normalize()`,
  `+ x.resolve(…)`, `+ x.resolveSibling(…)` (et leurs enchaînements) sur un chemin connu, et `+ Path.of(…)` /
  `+ Paths.get(…)`. Un appel final qui ne rend pas le chemin (`getFileName()`, `leafName(...)`) reste accepté.
- Le gate lit aussi l'argument d'un `Optional.of(...)` / `Optional.ofNullable(...)` qui rend un chemin, forme
  qu'emprunte un diagnostic (`problem()`).
- Les deux messages de `ScipJavaProjectJdk` nomment le rôle (`JAVA_HOME`) sans sa valeur ; les textes gardent
  leur préfixe actuel, en anglais comme le reste des messages du module.
- Auto-tests du gate : fixtures refusées (suffixe `Home`, chaînes, `Optional.of`), acceptées (`leafName`,
  `getFileName()`), entrée de liste blanche périmée, et témoin du site réel remis à sa forme fautive.

Aucune rupture : aucune surface publique ne promet la valeur de `JAVA_HOME` dans un message.

## Capabilities

### New Capabilities

Aucune : le gate et le refus du JDK de projet ont déjà leur capacité.

### Modified Capabilities

- `stockage-prive-et-secrets` : trois exigences ajoutées pour la règle `message(Path)` de `check-private-io.py`
  (suffixe `Home`, expressions chaînées, diagnostic en `Optional`). Cette capacité porte déjà les exigences du
  gate (`Une écriture brute nouvelle hors des primitives est refusée par le gate`, liste de tolérance à cliquet)
  et celle des messages sans chemin absolu des primitives ; la règle `message(Path)` n'y est pas encore écrite.
- `installation-des-providers` : l'exigence `Sans JDK de projet, le plan scip-java est refusé avant toute
  préparation` gagne l'absence du chemin dans le refus (MODIFIED), et une exigence ajoutée couvre le diagnostic
  de `doctor` sur un `JAVA_HOME` sans `javac`, sur le modèle de `Le dépôt désigné n'apparaît dans aucune sortie
  publique ni journal par son chemin`.

Écartées : `audit-qualite-code` (outillage SpotBugs, PIT, langue des commentaires, jamais `check-private-io.py`) ;
`surfaces-publiques` (la barrière de sortie `PublicErrorMessages`, inchangée ici) ; `journalisation` (journaux
`LOGGER.log`, non touchés).

## Hors périmètre

- La barrière `PublicErrorMessages` et ses heuristiques (AUD-SEC-05), inchangées.
- Les autres limites écrites du gate : message préparé dans une variable puis levé plus loin, chemin atteint par
  un champ ou un accesseur dont le nom ne finit pas par un suffixe reconnu, cause qui porte un chemin. Elles
  restent documentées dans la docstring.
- Les autres constats d'audit et le risque R-28 ; `ETAT-2026-10-13.md` et `findings.json`.
- Les fichiers partagés (`docs/architecture/risks/register.md`, `docs/project/audits/2026-10-10/SUIVI.md`,
  `docs/developer/supply-chain.md`) : le texte proposé est dans `design.md`.
- Le JDK désigné par `minos.scip.java.project-jdk`, déjà refusé par le nom du réglage (`ScipJavaProjectJdk.java:61`, `:80`).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Deux messages rendent la valeur de `JAVA_HOME` | [Existant vérifié] | `ScipJavaProjectJdk.java:68`, `:90` (HEAD `3cadc4ca`) |
| Le gate ignore le suffixe `Home` et `Optional.of` | [Existant vérifié] | `check-private-io.py:142`, `:357-363` |
| Avec `Home`, le gate trouve 1 site sur 653 sources (`:90`), aucun faux positif | [Existant vérifié] | mesure sur copie, voir `design.md` |
| Chaînes : 0 site réel, 0 faux positif ; `Optional.of` : 1 site (`:68`), 0 faux positif | [Existant vérifié] | mesure sur copie, voir `design.md` |
| Gate d'abord, correction par rôle ou `leafName` | [Exigence] | spécification du propriétaire, 10/11 |
| Messages en anglais, préfixes conservés, pas de journal DEBUG | [Décision] | `design.md` D3, D4 |
| Lecture de `Optional.of(...)` par le gate | [Décision] | `design.md` D2, option A retenue par le propriétaire le 10/11 |

## Impact

- **Modules du reactor** : `minos-provider-scip` (une classe et son test). Le gate est un script Python, hors reactor.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : additif. `minos doctor` et l'erreur d'indexation
  perdent la valeur de `JAVA_HOME`, gardent le nom de la variable et la cause.
- **ADR** : aucun (application de la règle existante des sorties sans chemin absolu).
- **Gates à rejouer** : `scripts/architecture/check-private-io.py`, `scripts/architecture/test_check_private_io.py`,
  `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py --fast` ; tests Maven
  `-pl minos-provider-scip -am`. Aucun renommage : aucun gate littéral ne cite `ScipJavaProjectJdk` (vérifié par
  `git grep -n ScipJavaProjectJdk -- scripts`).
- **Plateformes** : Windows et Linux — aucun comportement propre ; les tests posent leur propre `JAVA_HOME`.
- **Documentation durable et diagrammes** : `docs/developer/quality-gates.md` (règle `message(Path)`), dans la
  colonne du changement et la même PR. Les dossiers `docs/architecture/diagrams/capacites/stockage-prive-et-secrets.md`
  et `installation-des-providers.md` (traçabilité) sont mis à jour à l'archivage.

## Décisions du propriétaire

Tranchées le 10/11 (détail dans `design.md`) : la lecture de `Optional.of(...)` est retenue (D2, option A) ; les
messages restent en anglais sans journal DEBUG (D3, D4) ; `docs/developer/quality-gates.md` entre dans la colonne,
les dossiers de capacité sont mis à jour à l'archivage.
