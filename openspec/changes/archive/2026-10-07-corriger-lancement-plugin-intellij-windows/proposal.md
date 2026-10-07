# Proposal

## Why

Sous Windows, le plugin IntelliJ lance par défaut le lanceur `minos.cmd`
(`MinosSettingsState.defaultExecutable`, documenté dans `docs/user/intellij-plugin.md`). La
ligne de commande préparée pour `cmd.exe` est ensuite re-sérialisée une seconde fois, selon les
règles du runtime C, par le script `windows-cli-job-owner-v1.ps1` : `cmd.exe` reçoit
`\"C:\...\minos.cmd\"` et répond « n'est pas reconnu en tant que commande interne ou externe »
(exit 1). Le plugin est donc inutilisable avec son lanceur par défaut tant que l'utilisateur ne
configure pas `minos.exe`. Aucun test ne lance un vrai `.cmd`, et la CI Windows du plugin
(`windows-ownership`) n'exerce que la propriété des processus.

Ce changement rend le lancement d'un lanceur par lots (`.cmd`, `.bat`) sous Windows correct et
vérifié par un test qui démarre réellement un `.cmd`. Les autres constats du plugin issus de
l'audit (délai global, environnement du plan, handshake répété) sont traités avec honnêteté :
ceux dont la preuve ne tient pas au HEAD ou qui relèvent d'une décision produit sont renvoyés en
questions ouvertes de `design.md` et ne bloquent pas la correction.

## What Changes

- Le plan de lancement Windows transmet à `cmd.exe` la chaîne de commande **brute** (suite de
  jetons entre guillemets) sans la repasser par la sérialisation du runtime C ; le script de
  lancement Job Object ne modifie pas la sémantique de propriété du processus.
- Le rendu `cmd.exe` d'un lanceur par lots cesse de doubler `^` et `%` et d'échapper `&|<>` à
  l'intérieur de guillemets (inutile et altérant) ; il **refuse** (fail-closed) tout argument
  qu'un `.cmd` ne peut pas recevoir intact : guillemet `"` et `%`.
- Nouveau test `MinosCommandLineBatchLaunchTest` (Windows) qui démarre un vrai `.cmd` de test à
  travers la chaîne complète `MinosCommandLine` + `MinosStrongProcessLauncher`, rouge avant
  correctif ; tests purs complémentaires dans `MinosCommandLineTest` (valides sur Linux).
- `docs/user/intellij-plugin.md` : décrit le comportement des arguments refusés avec un lanceur
  `.cmd` et le contournement `minos.exe`.
- Aucun changement de protocole : les arguments de la CLI (`ide handshake --format json`,
  `project list`, …) et les schémas JSON restent identiques.

### Hors périmètre

- MINOS-AUD-C07 (délais par classe de commande) : décision à clarifier, voir `design.md`
  (Questions ouvertes) ; aucune tâche bloquante.
- MINOS-AUD-C08 (environnement du plan) : la preuve de la fiche ne tient pas au HEAD sur son
  point central (voir `design.md`) ; la liste blanche d'environnement est une décision ouverte.
- MINOS-AUD-C09 (handshake avant chaque action M21, fenêtre de versions) : P3, tâche différée
  hors périmètre bloquant ; l'acceptation d'une fenêtre de versions amenderait l'ADR-0027.
- MINOS-AUD-C10, C13, C14, C15 (autres changements ou surfaces) et tout code hors
  `minos-intellij` : le reactor Maven n'est pas modifié.
- Résolution automatique de `minos.cmd` vers un `app\minos.exe` voisin : alternative écartée de
  ce changement, soumise en question ouverte.
- Release du plugin, publication, signature : inchangées.

## Capabilities

### New Capabilities

- `client-intellij`: comportement du client IntelliJ externe du protocole CLI `minos-ide` v1,
  limité dans ce changement au lancement local de la CLI sous Windows par un lanceur par lots et
  à la préservation de l'indépendance du plugin vis-à-vis des artefacts `com.minos:*`.

### Modified Capabilities

(aucune : `openspec/specs/` est vide à ce jour)

## Constats d'audit couverts

| Constat | Qualification | Priorité | Traitement dans ce changement |
|---|---|---|---|
| MINOS-AUD-C04 | DÉFAUT CONFIRMÉ par reconstitution, non exécuté dans l'IDE (rejoué ici hors dépôt avec la même sérialisation et un `.cmd` réel : même erreur, exit 1) | P1 à confirmer dans le plugin livré ; contournement : configurer `minos.exe` | Corrigé : exigences « Un lanceur par lots démarre sous Windows et reçoit ses arguments intacts », « Les arguments qu'un lanceur par lots ne peut pas recevoir sont refusés », « Le lancement par lots conserve la frontière de propriété du processus » ; tâches 1.x et 2.x |
| MINOS-AUD-C07 | DÉFAUT DE CONCEPTION / DÉCISION À CLARIFIER (preuves relues, elles tiennent) | P2 | Non tranché : Questions ouvertes de `design.md` ; aucune exigence, aucune tâche bloquante |
| MINOS-AUD-C08 | RISQUE ; preuve centrale inexacte au HEAD (le script supprime le plan avant de démarrer la CLI, et un test existant le vérifie) | P2 (fiche) | Non retenu comme exigence ; liste blanche d'environnement et emplacement du script en Questions ouvertes |
| MINOS-AUD-C09 | AMÉLIORATION + dérive de contrat apparente (en réalité conforme à l'ADR-0027, qui impose l'égalité stricte) | P3 | Tâche différée hors périmètre bloquant ; amendement de l'ADR-0027 nécessaire si la fenêtre de versions est voulue |

Qualification d'ensemble : ce changement corrige un défaut établi par lecture du code et par
reconstitution du pipeline ; il n'a **pas** été exécuté dans l'IDE. La preuve finale attendue
est le test Windows `MinosCommandLineBatchLaunchTest` sur le job `windows-ownership` de
`.github/workflows/intellij-plugin.yml`.

## Impact

- Modules du reactor Maven touchés : **aucun**. Seul `minos-intellij` (Gradle, Java 21, hors
  reactor) est modifié (code, script PowerShell packagé, tests) ; ADR-0022 non concerné.
- Surfaces publiques : IntelliJ (comportement du lanceur et refus explicite d'arguments) ;
  CLI, API Java, MCP et NEXUS **inchangés** ; protocole `minos-ide` v1 inchangé.
- Dépendances : aucune nouvelle ; le plugin ne déclare et n'importe aucun `com.minos:*` hors
  `com.minos.intellij.*` (vérifié au HEAD dans `build.gradle.kts` et par grep des imports ;
  garde CI « Verify no MINOS engine dependency leaks into plugin »).
- ADR : **aucun nouvel ADR ni amendement requis** pour la correction. L'ADR-0027 (Accepted) est
  conforme à ce changement ; il ne serait à amender que pour l'acceptation d'une fenêtre de
  versions de protocole (C09, différé, nouvel ADR ou amendement à proposer, statut Proposed).
- Documentation : `docs/user/intellij-plugin.md` (gate `scripts/docs/check-current-docs.py`).
- Gates à rejouer : `scripts/remediation/check-p0-p2.py` (affirme des chaînes littérales dans
  `MinosCliClient.java`, `MinosExecutableResolver.java`, `MinosStrongProcessLauncher.java`,
  `MinosSettingsState.java`). Aucun scope JaCoCo ne couvre `minos-intellij`
  (`scripts/quality/check-jacoco.py` ne le référence pas).
