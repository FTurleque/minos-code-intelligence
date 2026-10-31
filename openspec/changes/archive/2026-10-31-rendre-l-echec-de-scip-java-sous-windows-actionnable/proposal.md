# Proposal

## Why

Lors de la clôture des sprints 3, 5 et 6 de l'audit du 10 octobre 2026, l'indexation d'un petit projet Maven Java sous Windows a échoué en 1.2.0 comme en 1.3.0 avec, pour seule sortie, `error: index failed: IllegalStateException` (consigné « à instruire à part » dans `docs/project/audits/2026-10-10/SUIVI.md` et `docs/project/governance/STATUS.md`). Un projet TypeScript s'indexe avec la même distribution. Le diagnostic du 2026-10-17 (`design.md`) établit deux défauts produit distincts :

1. **L'erreur publique est opaque.** La cause réelle est enregistrée dans le run, mais elle cite un chemin absolu ; la règle de nettoyage remplace alors **toute** la ligne par le nom de la classe d'exception. L'utilisateur n'a ni l'identifiant du run, ni la phase, ni l'endroit où lire la cause. C'était déjà le point 2 « à traiter plus tard » de `docs/project/audits/archive/2026-09/S15-REGRESSION-RUNTIME-GRANT.md`.
2. **La chaîne scip-java Windows ne s'exécute pas sous le bac à sable AppContainer.** Couche après couche : PowerShell choisi par le `PATH` (PowerShell 7 sous `Program Files`, refusé par le bac à sable) ; puis, avec PowerShell 5.1, tout appel natif du runner échoue (`DriveNotFound`, emplacement courant impossible) ; puis `csc.exe` ne crée pas son fichier temporaire (CS1619) ; puis la JVM du projet ne s'initialise pas (`java.security` illisible par `toRealPath` sous JDK 25, volume racine illisible sous JDK 21). Toutes ces couches ont une cause commune : le conteneur ne peut ni lister la racine du volume ni les ancêtres des racines qui lui sont accordées. Aucun test ni contrôle de CI n'exécute ce chemin ; `minos doctor` annonce pourtant scip-java `READY`, ce qui est contraire à la règle *capability-honest*. Le point 1 de S15 le signalait déjà.

## What Changes

Deux lots.

- **Lot 1 — diagnostic actionnable (implémentable sans décision)** :
  - un run d'indexation en échec donne une ligne publique qui nomme le run, sa phase, sa cause quand elle est sûre, et sinon l'emplacement de la cause relatif à `MINOS_HOME`, sans jamais de chemin absolu ;
  - l'échec de sortie d'un provider nomme son journal par un chemin relatif à `MINOS_HOME` ;
  - le refus « dépendance non accordable » du bac à sable nomme le fichier par son nom seul ;
  - scip-java sous Windows utilise le PowerShell 5.1 canonique du répertoire système (celui en qui le bac à sable a confiance), jamais un PowerShell trouvé dans le `PATH`.
- **Lot 2 — exécution réelle sous AppContainer (exige une décision)** : tant que la chaîne n'est pas qualifiée sous le bac à sable, `minos doctor` et `minos providers` ne déclarent plus scip-java `READY` sous Windows et l'indexation refuse avant tout lancement par une erreur d'usage qui dit qu'aucune voie n'indexe un projet Java depuis Windows (le runtime Docker ne lance aucun provider : ADR 0074). La qualification réelle (runner, shims compilés à l'installation, JVM du projet sous AppContainer) fait l'objet d'un ADR à écrire, car elle touche la frontière du bac à sable.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `surfaces-publiques` : deux exigences ajoutées (un run d'indexation en échec donne une erreur actionnable sans chemin absolu ; un échec de provider nomme ses traces sans chemin absolu).
- `installation-des-providers` : deux exigences ajoutées (PowerShell canonique pour scip-java sous Windows ; scip-java Windows non déclaré prêt tant que son exécution sous le bac à sable n'est pas qualifiée).

## Hors périmètre

- Changer la règle centrale `PublicErrorMessages` (masquer un chemin dans une phrase au lieu de rejeter la phrase) : une rédaction partielle laisserait passer des fragments d'un chemin à espaces ; le détecteur a été traité par `ne-plus-laisser-fuir-de-chemin-absolu-dans-les-erreurs` (archivé le 2026-10-17).
- Accorder au conteneur la liste de `C:\`, de `C:\Users` ou du profil : impossible sans élévation et contraire au confinement ; à trancher par l'ADR du lot 2.
- Les autres providers Windows (TypeScript qualifié en pratique, sans changement) ; Linux (chaîne différente, bubblewrap).
- Le contrôle d'intégrité du runner installé à l'exécution (constaté : `ManagedScipProviderRuntimeManager` le copie sans empreinte) : à instruire à part.

## Impact

- **Modules du reactor** : `minos-cli` (`operations/LocalAutonomousIndexOperations`) ; `minos-runtime-local` (`process/ProcessIndexerExecutor`, `sandbox/WindowsAppContainerWorkerSandboxBackend`) ; `minos-provider-scip` (`runtime/plan/ScipJavaProcessPlanFactory`, `runtime/install/ManagedScipProviderRuntimeManager`). Aucune dépendance ajoutée (ADR 0022).
- **Surfaces publiques** : CLI — la ligne `error: index failed: …` change de texte (additif : même code de sortie 1 `EXECUTION_ERROR`) ; lot 2 : `doctor`/`providers` rapportent scip-java bloqué sous Windows. API Java, MCP, IntelliJ, NEXUS : aucun changement de contrat.
- **ADR** : aucun pour le lot 1 ; un ADR nouveau (prochain numéro libre de `docs/architecture/adr/README.md`, 0060 au HEAD du 2026-10-18) pour le lot 2.
- **Gates à rejouer** : `scripts/remediation/check-post-mne.py` (lit `LocalAutonomousIndexOperations.java`, `ProcessIndexerExecutor.java`, `ManagedScipProviderRuntimeManager.java`), `scripts/remediation/check-minos-01.py:159` (littéraux de `ProcessIndexerExecutor.java`), `scripts/quality/check-polyglot-provider-consistency.py:104`, `scripts/quality/check-remote-distributed-consistency.py:130`, les gates du backend Windows (`check-mne.py`, `check-mnd.py`, `check-post228-hardening.py`) ; scopes JaCoCo `provider-sandbox-windows` et celui qui inclut `ProcessIndexerExecutor` (`scripts/quality/check-jacoco.py:146`).
- **Plateformes** : lot 1 vaut sous Windows et Linux (message) ; le choix de PowerShell et le lot 2 sont propres à Windows.
