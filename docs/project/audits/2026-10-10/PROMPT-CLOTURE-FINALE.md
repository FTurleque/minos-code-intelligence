# Prompt : clore le dernier constat de l'audit du 10/10/2026 et lever trois réserves

> À coller dans une session Claude Code ouverte sur `N:\workspace-dev\minos-code-intelligence`, branche `develop` à jour (`git pull --ff-only`).

---

Tu clos **AUD-SEC-04**, le dernier constat encore ouvert de l'audit du 10 octobre 2026 de MINOS. Tu lèves ensuite trois réserves sur des constats déjà fermés : AUD-DEP-13, AUD-TST-03 et AUD-DEP-03. La revérification du 05/11 a été faite sur `e9ae6cfe`.

Rédige tout en français, comme le reste du dépôt. Suis le contrat de `openspec/config.yaml`. Ce lot est petit et sans changement de capacité : pas de nouveau change OpenSpec. Le premier lot est une reprise du change archivé `2026-10-17-ne-plus-laisser-fuir-de-chemin-absolu-dans-les-erreurs`, et ton suivi le cite.

## 0. Lire avant tout

1. `docs/project/audits/2026-10-10/ETAT-2026-10-13.md`, sections « Suite au 29 octobre » et « Suite au 5 novembre » : les preuves `fichier:ligne` de chaque point.
2. `docs/project/audits/2026-10-10/findings.json` : `verification.reserve` de AUD-SEC-04, AUD-DEP-13, AUD-TST-03 et AUD-DEP-03.
3. `openspec/changes/archive/2026-10-17-ne-plus-laisser-fuir-de-chemin-absolu-dans-les-erreurs/` (design et tâches).
4. `scripts/architecture/check-private-io.py`, en particulier `path_rule_sites()` (l. 320 et suivantes, dont la docstring qui documente la limite « one reached through an accessor is not seen »), et `scripts/architecture/test_check_private_io.py`.

## 1. AUD-SEC-04 : aucun chemin absolu dans un message d'erreur (PR `sec/…`)

**État au 05/11.** Deux messages de `minos-storage/src/main/java/com/minos/storage/local/registry/LocalProjectRegistry.java` contiennent encore une racine de projet absolue :

```java
// l. 330-331
throw new IOException("project path cannot be represented by configured runtime mapping: "
        + project.rootPath(), exception);
// l. 400-401 (legacyRoot est la racine absolue héritée, sous forme de String)
throw new IOException("legacy project root cannot be migrated with configured mapping: "
        + legacyRoot, exception);
```

Le gate `message(Path)` ne les voit pas : il ne reconnaît que les variables déclarées `Path x` ou `File x` dans le fichier. Un `Path` rendu par un accesseur lui échappe, de même qu'une `String` qui contient un chemin.

**Ordre imposé : le gate d'abord.**

1. **Étendre le gate et le prouver rouge sur le dépôt réel.** Dans `path_rule_sites()`, considère aussi comme un chemin, dans un message d'exception ou de journal :
   - un appel d'accesseur dont le nom finit par `Path`, `Root`, `Dir`, `Directory` ou `File` et qui n'a pas d'argument (`project.rootPath()`, `x.dataRoot()`, `config.secretFile()`) ;
   - une variable ou un paramètre de type `String` dont le nom finit par `Path`, `Root`, `Dir`, `Directory` ou `File` (`legacyRoot`, `configuredSecretPath`) ;
   - l'appel `.toAbsolutePath()` ou `.toRealPath()` concaténé.

   Exclus ce que `leafName(...)`, `getFileName()` ou `PublicErrorMessages` enveloppent déjà. Garde la règle syntaxique, sans moteur de types : la docstring doit dire ce qui reste invisible (par exemple un message préparé dans une variable).

   Ajoute des fixtures à `test_check_private_io.py` :
   - les deux formes refusées ci-dessus ;
   - `leafName(project.rootPath())` accepté ;
   - un accesseur sans rapport avec un chemin (`project.id()`) accepté ;
   - une entrée de liste blanche périmée refusée.

   Lance le gate sur le dépôt : il doit être rouge, au moins sur les deux sites de `LocalProjectRegistry`. Donne le compte réel de tout ce qu'il trouve en plus.
2. **Corriger chaque site trouvé.** Utilise `PublicErrorMessages.leafName(...)` (`minos-domain`), ou un message qui nomme l'entrée par son rôle, sans chemin. Ne crée pas de nouvel assainisseur. Pour `LocalProjectRegistry`, le nom de feuille suffit au diagnostic : `"project path cannot be represented by configured runtime mapping: " + leafName(project.rootPath())`. Pour `legacyRoot`, qui est une `String`, convertis-la avec `Path.of(...)` dans un `try`, ou donne un message sans chemin.

   Un site interne qui ne peut atteindre aucune surface publique (CLI, MCP, API, journal WARNING ou plus) et qui doit garder le chemin pour le diagnostic entre dans `private-io-allowlist.json` : nominatif, avec un maximum et une justification. Jamais de joker. Le but est de n'en ajouter aucun.
3. **Tests Java.** Un test dans `minos-storage` par message corrigé. Il provoque l'`IOException` : correspondance impossible pour le premier, migration refusée pour le second. Il asserte que le message ne contient ni la racine absolue ni son parent, et qu'il contient le nom de feuille. Les goldens de caractérisation doivent rester identiques à l'octet.

**Critère de sortie.**
- `python -I scripts/architecture/check-private-io.py` est vert et son auto-test passe.
- `git grep -n "+ project.rootPath()\|+ legacyRoot" -- "*.java"` ne renvoie rien dans un message.
- Une mutation qui réintroduit `+ project.rootPath()` dans un `new IOException(...)` rend le gate rouge. Montre-le sur une copie.

## 2. Réserve de AUD-DEP-13 : le gate doit voir la déconnexion réellement exécutée (PR `ci/…`)

**État.** `scripts/quality/check-workflow-pins.py:256` accepte une étape dès qu'une de ses lignes contient le texte `docker logout`. Sur copie :
- retirer seulement `trap log_out EXIT` (`pr-ci.yml:329`) laisse le gate vert, alors que `log_out()` n'est plus jamais appelée et que le jeton reste sur le disque pendant `mvnw` ;
- mettre ce trap en commentaire (`code-audit.yml:162`) le laisse vert aussi.

**À faire.**
1. Ignorer les lignes commentées (`#` après les espaces de début) dans la recherche.
2. Accepter l'étape seulement si l'une de ces deux conditions est vraie :
   - un `docker logout` est exécuté directement, hors définition de fonction ;
   - une fonction contenant `docker logout` est définie **et** référencée par un `trap <fonction> EXIT` actif.

   Sous PowerShell (`shell: pwsh`), l'équivalent attendu est `try { … } finally { docker logout }`. Vérifie quelle forme les workflows utilisent vraiment, et ne gère que celles qui existent.
3. Ajoute des fixtures à `test_check_workflow_pins.py` : trap retiré (refusé), trap commenté (refusé), `docker logout` seul en commentaire (refusé), forme actuelle de `pr-ci.yml` (acceptée), logout direct (accepté).
4. Le gate doit rester vert sur le dépôt réel. Ne touche pas à `DOCKER_LOGIN_KEPT` : `release-promotion-gate.yml` y reste avec sa raison.

**Critère de sortie.** Les deux mutations ci-dessus rendent le gate rouge (montre-le sur une copie), et l'auto-test passe.

## 3. Réserve de AUD-TST-03 : un test de bac à sable qui se saute en silence (même PR que le § 2 ou une PR `test/…`)

**État.** `minos-runtime-local/src/test/java/com/minos/runtime/local/sandbox/LinuxSandboxToolchainHomeTest.java:51` appelle `assumeTrue(packaged.isPresent(), …)` directement, sans passer par `SandboxTestSupport.decide(required, available, reason)`. Avec `-Dminos.sandbox.tests.required=true` (`pr-ci.yml:399`), il se saute donc quand même si aucun JDK de distribution n'est installé sous `/usr/lib/jvm`. La restriction de `/etc` à Java (commits `6234dbf2`, `5904d80f`) resterait alors sans test en CI.

**À faire.**
1. Regarder si le runner `ubuntu-24.04` du job Linux a un JDK de distribution sous `/usr/lib/jvm` dont `conf/security/java.security` pointe vers `/etc`. Lis les journaux d'un run existant (`gh run view <id> --log` sur le job Linux, cherche le nom du test et `skipped`). Ne déclenche pas de run.
2. Selon le résultat :
   - **le JDK existe sur le runner** : remplace l'`assumeTrue` par `SandboxTestSupport.decide(SandboxTestSupport.required(), packaged.isPresent(), …)`, pour que le mode requis le rende obligatoire ;
   - **il n'existe pas** : installe dans le job Linux un paquet `openjdk-*-jre-headless` épinglé par version, uniquement pour ce test, puis applique la même modification. Sinon, si tu juges l'installation disproportionnée, propose-moi le choix avant de coder.

**Critère de sortie.** En mode requis, ce test ne peut plus être sauté sans faire échouer le job.

## 4. Réserve de AUD-DEP-03 : la dérogation minimatch au registre (PR `docs/…`)

**État.** `minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/osv-scanner.toml` déroge à GHSA-g3hv-75wg-m249 (minimatch 3.1.5, verrou scip-python) jusqu'au 2027-01-31. La raison y est écrite, mais la dérogation n'est ni dans `docs/architecture/risks/register.md` ni dans le suivi.

**À faire.** Inscris un risque R-30 sur le modèle de R-29 : avis, origine dans le verrou, exposition (scip-python dans le bac à sable, egress `DENY`), décision avec sa date, révision au plus tard le 2027-01-31. Profites-en pour corriger les références périmées à l'« ADR 0060 » (remplacé par SEC-0074) dans `scripts/quality/embedded-tools-advisory-waivers.json` et dans `SUIVI.md`.

## 5. Contraintes

- **Aucune CI déclenchée sans mon accord.** Ne pousse rien et n'ouvre aucune PR avant de m'avoir montré le diff.
- Vérifications locales attendues :
  - les gates touchés et leurs auto-tests (`python -I …`, `python -m unittest …`) ;
  - `mvnw -pl minos-storage -am test` avec `JAVA_HOME` défini ;
  - `scripts/run_gates.py --fast` s'il existe.

  Lance un `mvnw verify` complet seulement à la fin.
- Une branche et une PR par paragraphe (§ 3 peut rejoindre § 2), branchées sur `develop`.
- Ne touche à aucun autre constat.

## 6. Suivi et rendu

- Mets à jour `docs/project/audits/2026-10-10/SUIVI.md` : statut, PR, commit, preuve sous forme de commande. Corrige au passage :
  - la ligne SEC-04 (« 0 après » était faux) ;
  - la ligne QUA-08, qui se contredit : « 4.2 sans objet » dans le statut, « Restent : 4.2… » dans le texte.
- Ne modifie pas `ETAT-2026-10-13.md` ni `findings.json` : l'auditeur les mettra à jour après vérification.
- Réponse finale courte :
  - pour chaque paragraphe, la commande qui prouve le résultat ;
  - le compte de sites trouvés par le gate étendu avant et après correction ;
  - le choix fait pour le JDK de distribution (§ 3).
