---
id: SEC-0075
title: "scip-java sous Linux : dépôt Maven désigné en lecture seule, Maven hors ligne, réseau fermé"
domain: security
rules: false
---

# scip-java sous Linux : dépôt Maven désigné en lecture seule, Maven hors ligne, réseau fermé

- **Statut** : Accepted
- **Date** : 2026-10-31
- **Origine** : changement OpenSpec `indexer-maven-sans-reseau` (tâche 0.2) ; décision du propriétaire du 2026-10-31 (design du changement, « Décisions du propriétaire »)
- **Complète** : [SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) (décision 3), qui reste actif

## Context

scip-java indexe un projet Maven en lançant Maven sur une copie du projet. Ce build exécute du code que contrôle le dépôt analysé : plugins du cycle de vie et du `pom.xml`, extensions de build et de cœur (`.mvn/extensions.xml`), options de la JVM de Maven (`.mvn/jvm.config`), options de `.mvn/maven.config`. Ce code n'est pas de confiance ; sous Linux, il s'exécute dans le bac à sable bubblewrap + cgroup v2 avec un réseau `DENY` ([SEC-0070](SEC-0070-code-non-fiable-confinement-et-indexation-distante.md)). Les factories SCIP gardent `DENY` (`minos-runtime-local/src/main/java/com/minos/runtime/local/process/IndexerProcessPlanFactory.java:17-30`).

Sans réseau, Maven ne résout ni ses plugins ni les dépendances du projet ; aucun dépôt Maven n'est visible du bac à sable, qui ne monte qu'une liste fermée de racines système, la racine d'outil gérée et les fichiers nommés par la commande. Mesure du 2026-10-31 dans WSL2 (Ubuntu 24.04, MINOS 1.3.0, bac à sable `linux-bubblewrap-cgroup2-v5` qualifié, Maven 3.9.16 sous `/usr/local`) : l'indexation de `fixtures/java/java-simple` échoue sur la résolution de `maven-clean-plugin` (« Temporary failure in name resolution »).

L'ADR SEC-0074, décision 3, exige pour la voie WSL2 : « JDK du projet et dépôt Maven visibles du bac à sable sans sortie réseau ». Il n'en fixe pas le moyen.

Trois voies ont été instruites :

| Voie | Description | Raison de l'écart |
|---|---|---|
| A | dépôt Maven désigné par l'utilisateur, monté en lecture seule ; Maven hors ligne | retenue |
| B1 | phase de résolution lancée par MINOS avec réseau `ALLOW` (`dependency:go-offline`) | Maven construit le modèle du projet et charge `.mvn/extensions.xml` : du code du dépôt s'exécute avec la sortie réseau, contraire à SEC-0070 ; le téléchargement est fait par le provider, contraire à la règle de sécurité 8 et à l'esprit de [BE-0065](BE-0065-providers-scip-polyglottes-livres-dans-le-paquet.md) (empreinte épinglée avant exécution) ; `go-offline` ne garantit pas un build hors ligne complet |
| B2 | résolveur Maven embarqué dans MINOS, sans exécution de plugin | MINOS lit un `pom.xml` hostile dans un processus qui a le réseau et des secrets, contacte des URL choisies par le projet, ne voit pas les plugins qu'apporte une extension ; une dizaine de dépendances nouvelles |
| C | dépôt géré par MINOS sous `MINOS_HOME`, peuplé d'un catalogue épinglé | ne couvre qu'un projet sans dépendance ni plugin déclaré ; aucun projet réel ne s'indexe ; différé comme complément possible de A |

## Decision

1. **Le réseau du build analysé reste `DENY`.** Aucun plan scip-java n'ouvre la sortie réseau, ni pour une phase de résolution, ni sur consentement de l'utilisateur.
2. **Les artefacts Maven viennent d'un dépôt désigné par l'utilisateur.** Le réglage `minos.scip.java.maven-repository` n'a pas de valeur par défaut. Le plan le déclare comme racine de lecture ; le bac à sable Linux monte ce répertoire exact, en lecture seule, jamais un ancêtre.
3. **Une désignation qui exposerait un dossier sensible est refusée** avant toute copie du projet : relative, absente, lien ; égale à, ou ancêtre de, le dossier personnel, `/mnt/<lecteur>/Users/<compte>`, `/etc`, `/proc`, `/sys`, `/dev`, `/root` ou `MINOS_HOME` ; située sous `MINOS_HOME` ou `/run`, ou nommée `.m2` ; racine qui n'a pas la forme d'un dépôt Maven (`settings.xml`, `settings-security.xml`, `toolchains.xml`, dossier caché, fichier hors d'une liste fermée). Un sous-dossier exact du profil Windows vu de WSL est accepté.
4. **Maven est lancé hors ligne avec des réglages fixés par MINOS.** Ligne constante passée à scip-java : `--batch-mode --offline`, `-s`/`-gs` vers un `settings.xml` constant du run (sans serveur, miroir, proxy ni profil), dépôt de tête créé vide dans le run, dépôt désigné en `maven.repo.local.tail`, `clean verify -DskipTests`. `conf/settings.xml` et `conf/toolchains.xml` du Maven de l'hôte sont masqués par un fichier vide.
5. **Seul Maven 3.9.x est accepté** (`maven.repo.local.tail` existe depuis 3.9 ; Maven 4 lit des réglages contrôlés par le projet). La version est lue sur disque, sans exécuter Maven ; une autre version, ou l'absence de Maven, rend scip-java `BLOCKED` sous Linux et fait refuser le plan.
6. **Les racines de lecture sont une capacité déclarée du plan.** Seul le bac à sable Linux annonce qu'il les monte ; tout autre backend (AppContainer Windows, Job Object, exécution sans bac à sable OS) refuse le plan avant tout lancement, sans octroi nouveau.
7. **Le chemin désigné n'apparaît dans aucune sortie publique.** `doctor`, `tools verify`, les refus et les journaux WARNING nomment le réglage et son état.

## Do's and Don'ts

### Do

- Garder `DENY` pour tout plan scip-java, sous Linux et dans WSL2.
- Monter le seul répertoire désigné, en lecture seule, après revérification de son chemin réel juste avant le lancement.
- Écrire le `settings.xml` du run par une primitive privée, avec un contenu constant, et créer un dépôt de tête vide par run.
- Rapporter scip-java `BLOCKED` sous Linux sans Maven 3.9.x lisible du bac à sable ou sans dépôt désigné valide.
- Déclarer dans la documentation utilisateur le canal de sortie par l'index et le risque des artefacts privés, et recommander un dépôt dédié.

### Don't

- Ne pas ouvrir le réseau du bac à sable pour résoudre des dépendances, ni lancer Maven sur le projet analysé hors du bac à sable.
- Ne pas monter `~/.m2`, le dossier personnel, le profil Windows ni un ancêtre du dépôt désigné ; ne pas choisir de dépôt par défaut.
- Ne pas transmettre au build les réglages Maven de l'utilisateur ou de l'hôte (`settings.xml`, `settings-security.xml`, `toolchains.xml`).
- Ne pas écrire le chemin du dépôt désigné dans une sortie publique ou un journal WARNING.
- Ne pas recommander de peupler le dépôt en lançant Maven dans un projet non fiable.

## Consequences

### Positives

- Un projet Maven réel s'indexe sous Linux et dans WSL2 sans sortie réseau pour le code analysé.
- Aucune dépendance nouvelle ; le mécanisme réutilise le montage en lecture seule déjà qualifié du bac à sable.
- La racine de lecture déclarée par le plan sert de mécanisme générique ; tout autre usage (Gradle, NuGet) demande sa propre décision.

### Négatives

- Capacité partielle : un artefact absent du dépôt désigné fait échouer le build hors ligne ; peupler le dépôt revient à l'utilisateur.
- Le Maven exécuté est celui du `PATH` de l'hôte ; MINOS en vérifie la version, pas l'intégrité.
- Un artefact altéré du dépôt désigné est exécuté, sous les mêmes bornes que le reste du build.
- `IndexerProcessPlan` garde, à côté de son constructeur canonique, le constructeur à cinq composantes sans montage, utilisé par environ soixante-dix sites de test et six de production : exception écrite à la règle du constructeur unique, limitée à ce type interne du runtime local.

### Risques

- **Canal de sortie par l'index** : tout ce que le bac à sable rend lisible, dont le dépôt désigné, peut être recopié par le build dans l'artefact SCIP que MCP sert ensuite. Le dépôt peut contenir des artefacts privés d'autres projets : risque déclaré, réduit par un dépôt dédié.
- Peupler le dépôt en lançant Maven dans le projet analysé exécuterait `.mvn/extensions.xml` et `.mvn/jvm.config` sur l'hôte, avec réseau et secrets : la documentation l'interdit et donne une procédure sûre.
- Sur DrvFs (`/mnt/<lecteur>`), Windows peut modifier le dépôt pendant le run ; la revérification avant montage laisse un TOCTOU limité au même compte.
- Sur DrvFs monté avec les options par défaut de WSL, les fichiers apparaissent inscriptibles par tous : le bac à sable refuse alors le dépôt ; la voie `/mnt/<lecteur>` exige l'option `metadata` et un `umask` qui retire l'écriture du groupe et des autres.

## Compliance and Enforcement

- Tests `minos-runtime-local` : montage exact et refus des racines de lecture par le backend Linux, refus par les backends Windows et l'exécuteur sans bac à sable OS, propagation des racines par chaque transformateur de plan.
- Tests `minos-provider-scip` : ligne Maven fixe, contenu constant du `settings.xml`, refus des désignations dangereuses avant création de l'espace de travail, messages sans chemin absolu, état `BLOCKED` sans Maven 3.9.x ou sans dépôt.
- Gate `scripts/architecture/check-private-io.py` (écriture du `settings.xml` par une primitive privée) ; règles A10 (liste des types exportés de `minos-runtime-local`) et A13 (`ADAPTER_TYPES_NAMED_BY_ADAPTER` : quatre fichiers de `minos-provider-scip` nomment `IndexerProcessPlan`) de `scripts/architecture/check-module-boundaries.py`.
- Revue : aucune ouverture du réseau d'un provider, aucun montage d'ancêtre, aucun chemin désigné dans une sortie publique.

## Mise en œuvre

- `IndexerProcessPlan` porte `readOnlyRoots` et `maskedFiles` ; `ProcessIndexerExecutor` refuse un plan qui en déclare quand la frontière n'annonce pas qu'elle les monte (`ProcessPlanTransformer.mountsDeclaredSandboxMounts`), ce que seul le bac à sable Linux fait. `LinuxSandboxReadOnlyRoots` revérifie et monte chaque racine, et lie `/dev/null` sur les fichiers masqués.
- `ScipJavaMavenOffline` (`minos-provider-scip`) décide de la désignation, du Maven 3.9.x et prépare les fichiers Maven du run ; `minos-bootstrap` lit le réglage dans `MinosRuntimeSettings`.
- Preuve locale du 2026-11-01, WSL2 Ubuntu 24.04, Maven 3.9.16 sous `/usr/local`, JDK Temurin 25 sous `/usr/lib/jvm` : le script `scripts/ci/qualify-linux-offline-maven-index.sh`, lancé dans un scope systemd délégué, rapporte scip-java `READY`, `linux-bubblewrap-cgroup2-v5 AVAILABLE`, un index `SUCCEEDED` de `fixtures/java/java-simple` et un dépôt désigné inchangé (liste des fichiers, tailles et horodatages).
- Preuve de CI : étape « Offline Maven index under the Linux sandbox » du job `Verify (ubuntu-24.04)`.

## References

- Changement OpenSpec : `openspec/changes/indexer-maven-sans-reseau/`.
- ADR [SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md), décision 3 ; ADR [SEC-0070](SEC-0070-code-non-fiable-confinement-et-indexation-distante.md) (confinement, réseau `DENY`) ; ADR [BE-0065](BE-0065-providers-scip-polyglottes-livres-dans-le-paquet.md) (outils sous empreinte) ; ADR [SEC-0061](SEC-0061-deux-niveaux-de-confidentialite-pour-l-ecriture-de-fichiers-prives.md) (fichiers privés).
- Règle de sécurité : `.claude/rules/securite.md`, points 1, 2, 7 et 8.
