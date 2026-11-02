# Design

## État vérifié au HEAD

Relu sur `develop` au commit `90c24fd0` (2026-10-14).

- `minos-provider-scip/.../plan/ScipJavaProcessPlanFactory.java:139-165` : lanceur autonome cherché dans le `PATH`, sinon `coursier launch <coordonnée> --jvm system --main org.scip_code.scip_java.ScipJava -- index --output …`.
- `:119-122` : sous Windows, `-ClasspathFile` n'est transmis que si le fichier existe ; sinon le runner résout par Coursier (`scip-java-windows-runner.ps1:235-246`, `:379-384`).
- `ManagedScipProviderRuntimeManager.java:303-308`, `:369-379` : `coursier fetch --classpath`, vérification contre `ClasspathLock`, puis `classpath.txt` = chemins absolus vers le cache.
- `ManagedScipProviderRuntimeManager.java:226-249` (`inspectJavaInstalled`) : « installé » = Coursier présent (et, sous Windows, runner et Maven) ; le classpath n'y entre pas.
- `LinuxBubblewrapWorkerSandboxBackend.java:365-406` : seuls les arguments de commande qui sont des chemins absolus existants sont montés ; sous `MINOS_HOME/tools`, la racine `tools/<outil>/<version>` entière est montée en lecture seule. Le cache Coursier n'est jamais monté.
- Image Docker (`docker/Dockerfile.mcp.release:239-263`) : lanceur autonome `/usr/local/bin/scip-java`, choisi en premier par le plan.
- `scripts/release/build-embedded-tools.py:206-234` : le paquet Windows range les jars à côté de `classpath.txt`, noms relatifs.
- Verrou : 74 jars, 86 140 068 octets, plus gros jar 56 255 947 octets.

## Décisions

### D1. Le classpath exécuté vit sous `MINOS_HOME/tools`

L'installation copie les jars vérifiés dans le dossier du classpath (disposition du paquet Windows), vérifie les **copies** contre le verrou, puis écrit le lanceur et, en dernier, la liste ; l'arbre est assemblé dans `classpath.partial` puis substitué. Le cache Coursier n'est plus lu après l'installation. Alternative écartée : vérifier à l'indexation les jars du cache et monter le cache dans le bac à sable — le cache contient d'autres artefacts que scip-java et sa position dépend de l'utilisateur.

### D2. Un lanceur à manifeste seul plutôt qu'un `-cp` composé

Le bac à sable Linux ne monte que les arguments qui sont des chemins existants : une valeur `-cp a.jar:b.jar` n'en est pas un. Un jar sans classe, dont le manifeste porte `Main-Class` et un `Class-Path` relatif, est un argument unique sous `tools/scip-java/0.13.1`, ce qui fait monter tout le dossier. Ses octets sont déterministes (entrée stockée, date fixe) : le plan les recalcule depuis la liste et compare. Aucune modification du bac à sable n'est nécessaire.

### D3. Revérification à chaque plan, sur les deux systèmes

Le plan compare chaque jar au verrou (≈ 86 Mo hachés, de l'ordre de la seconde, à comparer à un build Maven) et, sous Linux, le lanceur. L'état (`inspect`) ne regarde que la présence, pour rester bon marché ; un classpath présent mais altéré donne un refus du plan avec la commande de réparation, et l'installation, qui revérifie, le refait.

### D5. Classpath d'abord, droits hérités (après revue de sécurité)

La revue de sécurité du 2026-10-14 a relevé deux points, corrigés dans ce changement : (1) un `scip-java` quelconque du `PATH` passait **avant** le classpath vérifié ; il n'est plus utilisé que sans classpath installé ; (2) sous Windows, les fichiers créés par `PrivateLocalStorage.createPrivateFile` / `writePrivateFile` portent une liste de droits protégée, qui ne reçoit pas le droit `RX` hérité que le bac à sable AppContainer pose sur le dossier : les fichiers sont maintenant créés (`CREATE_NEW`, sans suivre de lien) dans le dossier partiel privé et en héritent, comme l'arbre extrait du paquet Windows. Rouge reproduit sous Windows (`classpath.txt has an access entry that is not inherited`), puis vert. L'ancien arbre est renommé en `classpath.previous` pendant la substitution et restauré si elle échoue.

### D4. Un seul constructeur pour la fabrique

Le vérificateur est un paramètre obligatoire ; les constructeurs à 3 et 4 arguments, sans appelant de production, auraient produit des fabriques incapables d'indexer. Ils sont retirés et les tests modifiés dans le même lot (ADR 0045, « constructeur unique »).

## Direction des dépendances

Inchangée : `plan.ScipJavaClasspath` (nouveau) n'importe que `com.minos.io` (`minos-engine`) ; `install.ClasspathLock` implémente `ScipJavaClasspath.Verifier` (sens `install → plan`, déjà présent par `ScipJavaProcessPlanFactory`). Aucun cycle de paquets nouveau.

## Qualification des capacités

- Indexation scip-java hors Docker, sous Linux : **partielle**, comme avant. Le plan est construit et testé (tests de plan dans une JVM fille, Linux) ; l'exécution réelle dans bubblewrap n'est pas qualifiée par la CI, qui n'exécute aucun provider. Le lanceur à manifeste est prouvé par l'exécution réelle d'une classe principale par `java -jar` (test du module, deux systèmes).
- Windows : inchangé hors refus nouveaux ; le runner lit la même liste qu'avec le paquet Windows.
- Docker : inchangé (lanceur autonome).

## Risks / Trade-offs

- Une installation existante devra être refaite (message actionnable).
- La revérification ajoute un hachage de ≈ 86 Mo par indexation scip-java.
- Fenêtre de vérification puis exécution : voir « Hors périmètre » de la proposition.

## Mesure de la tâche 1.5 (2026-11-02)

WSL2 Ubuntu 24.04, hors Docker, JAR ombré construit au HEAD de `develop` (`4a92dd81`), scope systemd délégué, `linux-bubblewrap-cgroup2-v5 AVAILABLE`, Coursier épinglé (même commit et SHA-256 que `docker/Dockerfile.mcp.release`) dans le `PATH`, dépôt Maven désigné (ADR SEC-0076).

- `minos tools install scip-java` → scip-java 0.13.2 `READY` ; `MINOS_HOME/tools/scip-java/0.13.2/classpath` contient 72 fichiers en `0600` sous des dossiers `0700`.
- `minos index` de `fixtures/java/java-simple` → `SUCCEEDED`. Processus relevé pendant le run (`ps`) : `java -jar <MINOS_HOME>/tools/scip-java/0.13.2/classpath/scip-java-launcher.jar index --output … --targetroot … -- --batch-mode --offline …`, fils direct de `bwrap`, qui monte `tools/scip-java/0.13.2` par `--ro-bind`.
- Un jar copié modifié (`maven-resolver-spi-1.9.27.jar`, un octet ajouté) : `minos index` échoue avant tout lancement (« scip-java classpath verification failed: maven-resolver-spi-1.9.27.jar does not match its pinned SHA-256; run `minos tools install scip-java` »).
- Le lanceur modifié : refus de même (« scip-java-launcher.jar does not match the verified classpath »).
- Fichiers restaurés : `minos index` → `SUCCEEDED`.
- Écart : `minos doctor` déclare scip-java `READY` avec un jar altéré ; la vérification a lieu à chaque plan (D3), pas dans `doctor`.
