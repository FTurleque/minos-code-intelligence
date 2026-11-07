# Proposal

## Why

Sous Windows natif, scip-java est `BLOCKED` et aucun projet Java ne s'indexe depuis un poste Windows (ADR [SEC-0074](../../../docs/architecture/adr/SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md), décision 1). L'ADR retient comme voie un MINOS Linux dans WSL2, sous le bac à sable Linux qualifié, et ne permet de l'annoncer qu'après sa qualification : installation, scope systemd délégué, JDK du projet et dépôt Maven visibles du bac à sable sans sortie réseau, propriétaire des fichiers sur `/mnt/<lecteur>`, indexation réelle prouvée (décision 3).

Le 2026-11-07, une aide provisoire non versionnée (`index-java.ps1` et `index-java.sh`, hors dépôt) a indexé trois projets Maven réels depuis Windows par cette voie, avec le jar de l'installation exécuté par une JVM de WSL. jarvis et collection-manager sont `READY` dans l'installation Windows et leurs usages se résolvent. morpheus-engine a échoué au plafond de 256 Mio des instantanés. Ce plafond est relevé dans `develop` (PR #671, ADR DATA-0078), et un jar de `develop` importe le même SCIP. La voie fonctionne donc, mais hors du produit : pas de commande, pas de diagnostic, pas de test, et l'aide fait deux choses que le produit interdit (voir Statut des informations).

Le propriétaire a choisi, le 2026-11-07, une commande du CLI plutôt qu'un script livré.

## What Changes

- Le CLI Windows gagne une option additive `minos index <projet> --via-wsl` (et `--wsl-distro <nom>`). Elle indexe un projet Maven Java dans une distribution WSL2, sous le bac à sable Linux, puis importe dans l'installation Windows l'artefact vérifié, par le staging et la promotion atomique existants.
- `minos doctor` gagne, sous Windows, une section `wslJava` qui rapporte chaque prérequis de la voie : distribution WSL2, systemd, cgroup v2 délégué, bubblewrap, JVM 21 de MINOS, Maven 3.9.x, JDK du projet, dépôt Maven désigné. Aucun chemin absolu n'y figure.
- Sous Linux, l'installation de scip-java installe Coursier `linux-x64` depuis le catalogue épinglé, vérifié par son empreinte SHA-256. Aujourd'hui elle échoue et demande `cs` dans le `PATH`.
- Le diagnostic de scip-java sous Windows natif reste `BLOCKED`. Une fois la voie qualifiée, il nomme `minos index --via-wsl` au lieu de dire qu'aucune voie n'existe.
- La documentation utilisateur et le diagnostic de scip-java Windows n'annoncent la voie qu'après la tâche de qualification ; avant, rien ne la présente comme disponible.
- Aucune **RUPTURE** : options, section de `doctor` et diagnostic sont additifs.

## Capabilities

### New Capabilities

- `indexation-java-via-wsl` : indexer depuis Windows un projet Java dans WSL2, sous le bac à sable Linux, et en importer l'artefact vérifié. Elle est nouvelle parce qu'aucune capacité existante ne décrit une indexation dont l'exécution et l'import vivent sur deux systèmes : `installation-des-providers` couvre l'installation et la disponibilité des providers, `confinement-code-non-fiable` le bac à sable, et `decouverte-et-negociation` le choix du provider.

### Modified Capabilities

- `installation-des-providers` : l'exigence « scip-java sous Windows n'est pas déclaré prêt sans exécution qualifiée sous le bac à sable » change de diagnostic une fois la voie qualifiée. Une exigence est ajoutée pour l'installation de Coursier sous Linux.

## Hors périmètre

- Lever le blocage natif de scip-java sous Windows : il exige le test de bout en bout sous AppContainer de SEC-0074 (décision 2).
- Peupler le dépôt Maven désigné. MINOS ne lance jamais Maven avec le réseau dans un projet analysé (SEC-0076, Don't). Peupler le dépôt reste à la charge de l'utilisateur, comme sous Linux.
- Installer ou configurer WSL, systemd, bubblewrap, Maven ou un JDK dans la distribution : `doctor` les vérifie et en donne l'action, il ne les installe pas.
- Les autres langages par WSL (TypeScript, Python, .NET), qui s'indexent déjà sous Windows.
- Le MCP et le plugin IntelliJ : aucun outil MCP n'indexe (ADR ARCH-0017) ; le plugin pourra appeler l'option plus tard, par son protocole CLI.
- Rouvrir l'indexation distante de code non fiable (SEC-0070, décision 6). La voie WSL est une indexation locale sur un hôte Linux du poste, pas une source distante.
- La promotion `develop` → `main` et la release qui embarque le correctif #671.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| scip-java est `BLOCKED` sous Windows natif, avec « no supported way to index Java projects from a Windows host » | [Existant vérifié] | `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java:100-102` |
| La voie WSL2 n'est annoncée qu'après qualification | [Exigence] | SEC-0074, décision 3 |
| Le jar de l'installation, exécuté par une JVM de WSL, indexe un projet Maven sous bubblewrap et cgroup délégué | [Existant vérifié] | journal systemd utilisateur du 2026-11-07 (scopes de 00:41 à 01:25) ; `minos index-status jarvis` → `READY`, scip-java 0.13.2 |
| Sous Linux, Coursier n'est pas installé par MINOS | [Existant vérifié] | `ManagedScipProviderRuntimeManager.java:418` (« install `cs` in PATH ») ; l'entrée `coursier`/`linux-x64` du catalogue a une empreinte : `embedded-tools.json:99-113` |
| Un artefact de worker est vérifié (format, bornes, SHA-256, provenance) avant import | [Existant vérifié] | `DistributedIndexerExecutor.java:72-93`, `DistributedArtifactManifest.java:15-35` |
| Le manifeste v2 n'accepte qu'un SHA-1 Git comme source : il ne peut pas porter un projet local | [Existant vérifié] | `DistributedArtifactManifest.java:50-52` |
| Une seule orchestration d'indexation | [Exigence] | `openspec/specs/decouverte-et-negociation/spec.md:101` |
| L'aide provisoire peuple le dépôt Maven en lançant Maven avec réseau dans le projet, et télécharge Coursier elle-même | [Existant vérifié] | `index-java.sh`, étapes 1 et 2 (hors dépôt) ; contraire à SEC-0076 (Don't) et à la règle 8 de `.claude/rules/securite.md` |
| Commande du CLI plutôt que script livré | [Décision] | propriétaire, 2026-11-07 |
| La voie est un exécuteur de l'orchestration unique, qui réutilise `DistributedIndexing.Worker` et le magasin vérifié avec un manifeste additif v3 | [Proposition] | design, D2 et D3 |
| Le JDK des paquets Ubuntu échoue dans le bac à sable | [Question] | SEC-0074 (Context) l'affirme ; les runs du 2026-11-07 ont réussi avec `/usr/lib/jvm/java-21-openjdk-amd64` |

## Impact

- **Modules du reactor** : `minos-cli` (option, section `doctor`), `minos-application` (opération d'indexation via WSL, sans dépendance d'adaptateur), `minos-runtime-local` (worker WSL, lancement de `wsl.exe`, côté worker Linux), `minos-provider-scip` (Coursier Linux, diagnostic Windows), `minos-bootstrap` (câblage). Aucun module nouveau.
- **Surfaces publiques** : CLI additif (`index --via-wsl`, `--wsl-distro`, section `wslJava` de `doctor` en texte et JSON, diagnostic de scip-java Windows). API Java, MCP, NEXUS : aucun changement. IntelliJ : aucun changement. Format de transport : `minos-distributed-artifact-v3` additif et éphémère ; v1 et v2 inchangés.
- **ADR** : un ADR à écrire (prochain numéro, 0079 au HEAD `54fff28d`), mise en œuvre de SEC-0074 décision 3 ; SEC-0074 reçoit « Complété par ». SEC-0070 et SEC-0076 restent inchangés.
- **Gates à rejouer** : `check-module-boundaries.py`, `check-private-io.py`, `check-tools-manifest.py`, `check-ci-wiring.py`, `check-comment-language.py`, `check-capability-dossiers.py`, `check-current-docs.py`, `product-facts.py --check` ; scopes JaCoCo de `minos-runtime-local`, `minos-cli` et `minos-provider-scip`.
- **Plateformes** : la voie n'existe que sous Windows ; ailleurs, `--via-wsl` est refusé en erreur d'usage. Le côté worker et Coursier tournent sous Linux, ce qui profite aussi à Linux natif.
- **Documentation durable et diagrammes** : nouveau dossier `docs/architecture/diagrams/capacites/indexation-java-via-wsl.md` ; dossier `installation-des-providers.md` ; `docs/user/troubleshooting.md` et `docs/user/production-installation.md` ; vue de déploiement arc42 ; `STATUS.md` et `ROADMAP.md` à la clôture.

## Questions ouvertes

Aucune question bloquante. Questions non bloquantes, laissées au design : JDK des paquets Ubuntu (Q1), affinité CPU de l'aide provisoire (Q2), domaine de l'ADR (Q3).
