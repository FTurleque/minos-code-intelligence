# Proposal

## Why

Sous Linux, un provider géré s'exécute dans le bac à sable bubblewrap + cgroup v2, réseau `DENY` (ADR SEC-0070). scip-java y lance `mvn --batch-mode clean verify -DskipTests` sur une copie du projet. Mesure du 2026-10-31 dans WSL2 (Ubuntu 24.04, MINOS 1.3.0, `linux-bubblewrap-cgroup2-v5 AVAILABLE`, scip-java `READY`, JDK autonome `/usr/lib/jvm/temurin-25`, Maven 3.9.16 sous `/usr/local`) : l'indexation de `fixtures/java/java-simple` échoue, `maven-clean-plugin:3.2.0` ne se résout pas (« repo.maven.apache.org: Temporary failure in name resolution »).

Au HEAD `8c3d4615`, rien ne rend un dépôt Maven visible au build analysé : le plan scip-java ne déclare ni dépôt, ni réglages Maven, ni environnement (`ScipJavaProcessPlanFactory.java:165-171`) ; le bac à sable ne monte que `/usr`, `/bin`, `/lib`, `/lib64`, `/sbin`, la racine d'outil gérée et les fichiers nommés par la commande, jamais un répertoire arbitraire (`LinuxBubblewrapWorkerSandboxBackend.java:377-410`). Le réseau reste fermé par conception (`IndexerProcessPlanFactory.java:17-30`) : l'ouvrir donnerait la sortie réseau aux plugins Maven que contrôle le dépôt analysé.

Conséquence : sous Linux comme dans la voie WSL2 retenue par l'ADR SEC-0074 (décision 3, « dépôt Maven visible du bac à sable sans sortie réseau »), aucun projet Maven réel ne s'indexe. Ce changement instruit les options et propose une voie ; **aucune implémentation avant la décision du propriétaire** (design, « Décisions attendues »).

## What Changes

Proposition (option A du design, recommandée ; à confirmer) :

- **Dépôt Maven désigné, monté en lecture seule.** L'utilisateur désigne explicitement un dépôt Maven local (réglage MINOS, aucun défaut). Le bac à sable Linux le monte en lecture seule, ce répertoire exact et lui seul, jamais un ancêtre. Désignation refusée si elle est le dossier personnel, l'un de ses ancêtres, ou un dossier qui contient `settings.xml`, `settings-security.xml` ou `toolchains.xml`.
- **Maven hors ligne, réglages neutralisés.** Le plan passe à scip-java, après `--`, une ligne Maven fixe : `--batch-mode --offline`, réglages utilisateur et globaux remplacés par un fichier vide écrit par MINOS dans le run (aucun serveur, miroir ni proxy), dépôt local inscriptible et éphémère dans le run, dépôt désigné en « tail » lecture seule (Maven 3.9), puis `clean verify -DskipTests`.
- **Racines de lecture déclarées par le plan.** `IndexerProcessPlan` porte une liste de racines en lecture seule ; le backend Linux les monte telles quelles ; tout backend qui ne sait pas les honorer (Windows AppContainer, backend natif) refuse le plan avant tout lancement.
- **Refus actionnable.** Sans dépôt désigné, ou dépôt illisible, le plan scip-java d'un projet Maven est refusé avant toute copie du projet, par un message qui nomme le réglage, sans chemin absolu. Un artefact absent du dépôt donne l'échec Maven hors ligne, que la documentation explique.
- **ADR** : un nouvel ADR du domaine sécurité (numéro attribué à la rédaction, `SEC-0075` si SEC-0074 est fusionné) fixe la voie retenue pour l'exigence de SEC-0074 décision 3 et confirme `DENY` de SEC-0070 pour scip-java ; ou, au choix du propriétaire, un ADR consolidé remplace SEC-0074. Planifié en tâche 0.2, rien n'est écrit pendant la spécification.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `confinement-code-non-fiable` : racines de lecture déclarées par le plan, montées exactement sous Linux, refusées par les backends qui ne les honorent pas.
- `installation-des-providers` : plan scip-java Maven hors ligne sur dépôt désigné ; refus avant préparation sans dépôt ; réglages Maven de l'hôte jamais lus.

## Impact

- Modules : `minos-runtime-local` (`IndexerProcessPlan`, backends de bac à sable), `minos-provider-scip` (`ScipJavaProcessPlanFactory`), `minos-bootstrap` (lecture et câblage du réglage), `minos-app` (goldens de `doctor`) ; documentation utilisateur et développeur.
- Surfaces publiques : un réglage additif (nom à fixer : `minos.scip.java.maven-repository`) ; `doctor` et `tools verify` le rapportent. Aucune rupture CLI, API Java, MCP, IntelliJ ni NEXUS. `IndexerProcessPlan` est un type exporté de `minos-runtime-local` (règle A10) : composante additive, constructeur existant conservé.
- Gates à rejouer : `check-module-boundaries.py`, `check-private-io.py`, `check-minos-01.py`, `check-mnd.py`, `check-post-mne.py`, `check-p0-p2.py`, `check-comment-language.py`, `check-jacoco.py` (scopes de `ScipJavaProcessPlanFactory` et `LinuxBubblewrapWorkerSandboxBackend`).
- Plateformes : Linux (et WSL2) gagne la voie ; Windows natif refuse explicitement un plan à racine de lecture ; Docker inchangé.

## Hors périmètre

- Une phase de résolution avec réseau (option B du design) et un dépôt géré et peuplé par MINOS (option C) : écartées ou différées par la recommandation, chacune exigerait son propre ADR.
- Le peuplement du dépôt désigné : responsabilité de l'utilisateur ; la documentation dit qu'un `mvn` lancé sur l'hôte exécute le build du projet hors bac à sable.
- Le Maven exécuté sous Linux (celui du `PATH`, non vérifié par MINOS) et le JDK du projet sous `/etc` (traité dans une autre session) ; Gradle.
- Windows natif (scip-java `BLOCKED`, ADR SEC-0074) et runtime Docker (aucun provider) : inchangés.
- Les workers distribués (ADR SEC-0070 § distribué) : la désignation est locale.
