# Design

Changement de commentaires de fichiers livrés, de test de contrat et de documentation : seules les sections Contexte, État vérifié, Écarts, Décisions, Vérification et Traçabilité sont développées. Les autres sections du modèle sont « Non applicable : aucun comportement du produit, aucune surface publique, aucune donnée persistée, aucune image n'est modifié dans ce lot ».

## Contexte

Motivation : `proposal.md` (Why). Fichiers touchés : `docker/compose-mcp.connected.yaml`, `docker/compose-mcp.prod.yaml`, `docker/Dockerfile.mcp.release` (commentaires seuls), un test de contrat de `minos-app` (`com.minos.packaging`), `docs/user/docker-runtime.md`. Toute modification sous `docker/` déclenche la qualification Docker A → B de la PR.

## État vérifié au HEAD

HEAD `89be3fb4`. Lecture ; aucune image construite, aucun conteneur lancé.

| Fait | Preuve |
|---|---|
| [Existant vérifié] Commentaire « Administration image: indexing and tools list/verify launch the providers and their toolchains. » | `docker/compose-mcp.connected.yaml:168` ; `docker/compose-mcp.prod.yaml:71` |
| [Existant vérifié] Commentaire du Dockerfile : « Project dependency resolution for the real Maven build is a separate indexing concern handled by the ephemeral admin plane » | `docker/Dockerfile.mcp.release:257-263` |
| [Existant vérifié] Le même bloc dit que le graphe transitif Coursier n'a pas de verrou du dépôt, puis construit le lanceur par `cs bootstrap … --standalone … -o /usr/local/bin/scip-java` | `docker/Dockerfile.mcp.release:257-276` |
| [Existant vérifié] Cible `admin` sur une image JDK ; toolchains Rust, Go, .NET et Node copiées ou installées | `docker/Dockerfile.mcp.release:73`, `:103-112`, `:148-180` |
| [Existant vérifié] SEC-0074 : `minos-admin` ne lance aucun provider ; `index` y est refusé avant tout processus fils (mesure du 2026-10-31) | `docs/architecture/adr/SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md`, § Context |
| [Existant vérifié] SEC-0080 décision 7 : le lanceur autonome du Dockerfile reste hors verrou, « image qui n'exécute aucun provider » | `docs/architecture/adr/SEC-0080-forcage-nominatif-d-une-dependance-du-verrou-de-scip-java.md`, § Decision |
| [Existant vérifié] R-13 « Graphe transitif Coursier scip-java non locké », assumé | `docs/architecture/risks/register.md:18` |
| [Existant vérifié] La qualification échoue si `index` n'est pas refusé ou si scip-java n'est pas `UNSUPPORTED_BY_BACKEND` dans le plan d'administration | `scripts/ci/qualify-docker-upgrade.ps1:386-413` |
| [Existant vérifié] La veille des avis lit le verrou du classpath (`windows-x64`), pas le lanceur de l'image | `minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/embedded-tools.json:231-240` et `scripts/quality/check-embedded-tools-advisories.py:116` (cités par l'audit, non relus ligne à ligne) |
| [Existant vérifié] Un service de la même image exécute bien les outils, pour les sonder : `minos-provider-probe` lance `java -version`, `javac -version`, `mvn --version`, `scip-java --version`, les indexeurs et les toolchains ; son commentaire le dit | `docker/compose-mcp.connected.yaml:298-312` (commentaire `:300`) ; `docker/compose-mcp.prod.yaml:223-230` (commentaire `:225`) |
| [Existant vérifié] Un test de contrat exige que la ligne placée juste au-dessus de `image:` soit un commentaire commençant par `# Administration image:` (ou `# Query image:`) | `minos-app/src/test/java/com/minos/packaging/M29DockerQueryImageContractTest.java:413-415` |
| [Existant vérifié] Tests de contrat Docker existants | `minos-app/src/test/java/com/minos/packaging/M29DockerAdministrationContractTest.java`, `M29DockerQueryImageContractTest.java` |

Non vérifié : ce que `tools list`, `tools verify` et `doctor` exécutent eux-mêmes dans le service `minos-admin` ; ce que les tests de contrat affirment sur la présence des toolchains ; la taille que ces toolchains ajoutent à l'image.

Lecture de la tâche 2.1 (HEAD `c388df1b`) : `tools list` et `tools verify` appellent `AutonomousIndexOperations.providers()` (`minos-cli/src/main/java/com/minos/cli/command/ToolsCommand.java:46`), qui passe par `ProviderPlatformService.runtimes()` (`minos-cli/src/main/java/com/minos/cli/operations/LocalAutonomousIndexOperations.java:71-74`, `minos-application/src/main/java/com/minos/application/ProviderPlatformService.java:77-79`) ; la Javadoc de ce service dit que lister « inspecte chaque runtime » (`:72-74`) et le verdict `UNSUPPORTED_BY_BACKEND` ne bloque pas `verify` (`ToolsCommand.java:68-70`). **Non lu** : l'implémentation de `runtimes.list()` (ce qu'elle exécute réellement dans le conteneur) et celle de `doctor` ; la raison écrite dit donc « inventorient » et non « exécutent », et la question ouverte reste ouverte sur ces deux points.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| E1. L'audit ne cite que les deux compose ; le Dockerfile porte la même idée (« handled by the ephemeral admin plane ») | preuve d'AUD-DEP-01 | `docker/Dockerfile.mcp.release:262-263` | Corriger aussi ce commentaire (tâche 1.2) |
| E2. La spec de référence dit « L'image d'administration SHALL conserver ce qu'exige l'indexation » et classe l'image d'administration parmi les services qui exécutent « provider ou outil embarqué » | `openspec/specs/distribution-docker/spec.md` (exigences « L'image du plan de requête ne contient ni compilateur ni outil de provider » et « Chaque service Docker utilise l'image qui correspond à son rôle ») | SEC-0074 : le runtime Docker n'indexe pas | Ne pas modifier ces exigences dans ce lot (elles restent vraies pour la séparation des images) ; l'écart est porté au dossier de capacité et tranché avec D2 |
| E3. L'audit affirme que les toolchains et le lanceur scip-java ne sont exécutés par « aucun service » (confiance « probable ») | action d'AUD-DEP-01 | le service `minos-provider-probe` les exécute pour en lire la version, dont `scip-java --version` (`compose-mcp.connected.yaml:300-312`, `compose-mcp.prod.yaml:225`) | Le constat est partiel : aucun provider n'**indexe** dans cette image, mais ses outils y sont lancés par la sonde. La raison écrite (D2) le dit, et R-13 est relu : le lanceur hors verrou est exécuté, sur aucun code de projet |
| E5. Un commentaire réécrit librement casserait un test existant | `M29DockerQueryImageContractTest.java:413-415` | D1 | Le commentaire de `minos-admin` reste sur la ligne juste au-dessus de `image:` et commence par `# Administration image:` (tâche 1.2) |
| E4. Retirer les toolchains est un changement d'image (effort M), pas un correctif de commentaire | action d'AUD-DEP-01 (« retirer […] ou écrire pourquoi ils y restent ») | qualification Docker, inventaire de l'image, tests de contrat | Ce lot écrit la raison ; le retrait éventuel est un changement distinct (D2) |

## Décisions

### D1 — Dire dans les fichiers livrés ce que le plan d'administration exécute

[Décision] Les trois commentaires sont réécrits : `minos-admin` porte les commandes d'administration, l'inventaire et la vérification des outils ; l'indexation y est refusée faute de bac à sable OS (renvoi à SEC-0074 par son identifiant). Dans chaque compose, le commentaire reste sur la ligne juste au-dessus de `image:` et garde le préfixe `# Administration image:` qu'exige `M29DockerQueryImageContractTest.java:413-415`. Le test de contrat ajouté est borné au bloc du service `minos-admin` de chaque compose (de sa clé au service suivant) et au passage nommé du Dockerfile : il y refuse un commentaire qui associe ce service au lancement de providers et exige la mention du refus de l'indexation. Le service `minos-provider-probe`, dont le commentaire dit vrai, est hors du contrôle. Écarté : corriger sans garde (le commentaire d'origine a survécu à SEC-0074 faute de contrôle).

### D2 — Toolchains et lanceur scip-java de l'image d'administration — **en attente du propriétaire**

[Proposition] Options : (a) les garder et écrire pourquoi (ce lot) ; (b) les retirer de la cible `admin` (changement distinct). Recommandation : **(a) maintenant, (b) à instruire**. Le service `minos-provider-probe` les exécute pour les sonder (E3) : la raison écrite est « outils sondés par `minos-provider-probe` et inventoriés par `tools list/verify` ; aucun n'indexe dans un conteneur (SEC-0074) ; le lanceur scip-java, hors verrou, n'est lancé que pour sa version (R-13) ». La lecture de la tâche 2.1 complète ce que `tools list`, `tools verify` et `doctor` exécutent dans `minos-admin`. Un retrait supprimerait la sonde ou la viderait de son objet : il se décide avec elle, dans un changement distinct qui reprendra la décision 7 de SEC-0080, R-13 et l'exigence citée en E2.

### D3 — Aucune exigence existante modifiée

[Décision] Une exigence ajoutée suffit ; E2 reste un écart documenté jusqu'à D2, pour ne pas réécrire une exigence d'après le code.

## Vérification

- Test : `M29DockerAdministrationContractTest` (cas ajoutés), `./mvnw -B -ntp -pl minos-app -am test` ; les autres tests de contrat Docker restent verts sans changement d'attente.
- Gates : `python scripts/quality/check-compose-limits.py`, `python scripts/quality/check-image-pins.py`, `python scripts/quality/check-toolchain-baseline.py`, `python .claude/scripts/run_gates.py --fast` ; chercher la phrase d'origine dans `scripts/` avant de la modifier (gates littéraux).
- CI (à la demande du propriétaire) : qualification Docker A → B de la PR verte (elle prouve le troisième scénario).
- Non vérifiable localement sans Docker : le scénario « Le runtime confirme ce que disent les fichiers ».

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| distribution-docker › Les fichiers de distribution Docker ne présentent pas le plan d'administration comme lançant des providers | Commentaire qui annonce le lancement des providers | D1 | 1.1, 1.2 | `M29DockerAdministrationContractTest#aCommentThatAnnouncesTheLaunchOfProvidersIsRefusedAndNamedByFileAndLine` (rouge observé : 2 échecs nommant `docker/compose-mcp.prod.yaml:71`, `docker/compose-mcp.connected.yaml:168`, `docker/Dockerfile.mcp.release:262` ; vert après 1.2 : 20 tests) |
| idem | Fichiers réels | D1 (E1) | 1.2 | `M29DockerAdministrationContractTest#theShippedFilesDoNotPresentTheAdministrationPlaneAsLaunchingProviders`, `…#theLauncherCommentOfTheDockerfileDoesNotSayTheAdministrationPlaneIndexes` |
| idem | Service de sonde des outils | D1 (E3) | 1.1, 1.2 | `M29DockerAdministrationContractTest#theProbeServiceIsNotConcernedByTheAdministrationServiceRule` |
| idem | Le runtime confirme ce que disent les fichiers | D1 | 1.3 | `qualify-docker-upgrade.ps1:386-413` (existant) ; run CI de la PR — **manquante** (tâche 1.3) |

## Questions ouvertes

- [Question] Ce que `tools list`, `tools verify` et `doctor` exécutent dans le service `minos-admin` (tâche 2.1) : la réponse complète la raison écrite de D2 mais ne change ni l'exigence ni les tâches du groupe 1.

## Écarts constatés à l'implémentation

- Le commentaire de `minos-admin` tient sur **une seule ligne** (`# Administration image: …`) : `M29DockerQueryImageContractTest` exige que la ligne juste au-dessus de `image:` commence par ce préfixe (E5), un commentaire sur deux lignes aurait cassé ce test.
- Le test borne la règle au bloc de `minos-admin` (de sa clé au service suivant) ; la règle « lance des providers » est une expression régulière sur les lignes de commentaire (`launch … provider`), le passage du Dockerfile est jugé sur le bloc de commentaires qui contient « Build a standalone scip-java launcher ».
- D2 appliquée dans sa branche (a) : raison écrite dans `docs/user/docker-runtime.md` (règle de classement et paragraphe des toolchains), dans le Dockerfile et dans R-13 ; le retrait éventuel reste un changement distinct, décision du propriétaire non prise (recommandation du design prise).
- Les gates `check-compose-limits.py`, `check-image-pins.py` et `check-toolchain-baseline.py` restent verts ; aucune chaîne littérale des commentaires réécrits n'était citée par un gate ou un autre test.
- Non mesuré : la qualification Docker A → B en CI (tâche 1.3), l'exécution réelle de `tools list/verify/doctor` dans `minos-admin`.
