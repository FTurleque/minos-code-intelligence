# État courant — MINOS

Dernière mise à jour : **27 août 2026**.

Ce fichier est la synthèse autoritative de l'état produit courant. L'historique détaillé antérieur à la campagne post-#226 est conservé sans modification dans [`history/reconciliations/STATUS-pre-post226-audit-20260821.md`](history/reconciliations/STATUS-pre-post226-audit-20260821.md) ; la version courante au 21 août 2026 (remédiation post-#228 encore en qualification) est conservée dans [`history/reconciliations/STATUS-pre-post243-audit-20260827.md`](history/reconciliations/STATUS-pre-post243-audit-20260827.md).

> **Convention.** Une capacité n'est considérée intégrée qu'après merge. Une correction présente sur une branche ou une PR reste « en qualification » jusqu'à son merge.

## Réconciliation post-PR #228 — intégrée

**La remédiation quota/readiness du réaudit #228 est intégrée** (PR #229) et a depuis été promue sur `main` (PR #243).

- toute perte réelle de visibilité d'un writable root supervisé devient un breach et détruit le job ; une disparition concurrente normale d'une entrée ne provoque pas de faux breach ;
- sous Windows, le budget global historique de **8 GiB / 400 000 entrées** reste borné : **7 GiB / 350 000** pour les roots explicites MINOS et **1 GiB / 50 000** réservé au stockage fichier privé AppContainer ;
- les mutations du stockage registre privé AppContainer sont refusées avant reprise du child suspendu ; le superviseur du stockage privé est armé avant `ResumeThread` et tue le Job Object au dépassement ou à la perte de visibilité ;
- la découverte Windows exécute un probe réel et borné du launcher packagé (profil, token AppContainer, Job Object, limites relues depuis le noyau, assignment, membership, reprise d'un child inoffensif) ; la disponibilité d'un provider local dépend du sandbox réellement utilisé à l'exécution ;
- un gate dédié (**Post-228 Hardening Invariants**) vérifie statiquement ces invariants et impose une couverture JaCoCo ciblée de `ProviderWriteQuotaSupervisor`, en plus de `mvn verify` et des tests réels AppContainer/Job Object exact-head Linux/Windows.

## Ligne de développement 229 → 241 — intégrée

Entre la PR #229 et la PR #241 (toutes intégrées dans `develop`) :

- **#230** : refactors de maintenabilité (composition root, dissociation schéma/dispatch MCP, extraction du client de clone JGit, extraction de la politique d'URL JDBC PostgreSQL) et décomposition CI pour M23/M25/M26/M27 ;
- **#231** : moteur de mise à jour transactionnelle Windows du payload programme (upgrade in-place avec rollback) ;
- **#232** : revue de sécurité à 8 angles du moteur de mise à jour, gaps réels fermés ;
- **#233–#234** : fermeture des gaps restants de la matrice contractuelle de test des clients MCP (préexistant/fallback) ;
- **#235** : documentation de la détection Claude Desktop MSIX et de la chaîne complète de vérification des clients MCP ;
- **#236** : fermeture de 3 gaps identifiés par la revue de sécurité finale (capstone) ;
- **#237** : ouverture de la ligne de développement **1.1.0-SNAPSHOT** ;
- **#238** : un profil Claude Desktop MSIX obsolète ne masque plus l'installation classique active ;
- **#239** : l'assistant affiche quels clients MCP sont déjà gérés par MINOS ;
- **#240** : le build de l'image provider ne s'invalide plus à chaque bump de version ;
- **#241** : la case à cocher d'un client MCP déjà correctement configuré se verrouille au lieu d'être précochée.

## Corrections Windows non-admin / Docker MCP — intégrées (27 août 2026)

Trois corrections trouvées et qualifiées en conditions réelles (installation, mise à jour et usage concurrent sur un poste Windows standard, sans droits administrateur) :

- **PR #242 / promotion #243** : `WindowsAppContainerWorkerSandboxBackend` traitait inconditionnellement `System.getProperty("java.home")` (la JVM qui exécute MINOS elle-même — par exemple le JDK sélectionné par une configuration d'exécution IntelliJ) comme une racine de lecture à accorder à l'AppContainer. Un JDK hors d'un emplacement possédé par l'utilisateur (typiquement sous `Program Files`) rendait le `icacls /grant` requis impossible sans élévation, forçant de fait un usage administrateur pour indexer. Corrigé : le JVM hôte n'est plus jamais utilisé comme racine de sandbox ; toute racine candidate (root managé, valeur de toolchain déclarée) qui ne peut pas être accordée sans élévation fait désormais échouer le plan proprement (`isAclGrantable`/`requireAclGrantable`) avant tout appel PowerShell/`icacls`, avec un diagnostic explicite plutôt qu'une erreur `icacls` cryptique. Porte également le passage du runner Windows `scip-java` d'une découverte de `mvnw`/`mvn` hôte (injoignable depuis l'intérieur de la sandbox) vers un Maven géré par MINOS.
- **PR #246** : la mise à jour du backend Docker MCP (`docker → docker`, changement de version) restait bloquée indéfiniment sur l'invite interactive `docker compose` *« Recreate (data will be lost)? »* — le volume `minos-provider-tools` porte des labels qui changent à chaque build, donc Compose voyait un désaccord de configuration à chaque mise à jour, et cette invite héritait d'un stdin d'installateur réel-mais-inutilisable, bloquant pour toujours. Corrigé en forçant les invocations `docker compose` concernées sur un stdin non-interactif garanti (échec rapide au lieu d'un blocage). Porte également plusieurs correctifs déjà écrits mais jamais ouverts en PR : le profil PostgreSQL/Ollama managé qui atteint réellement un état fonctionnel de bout en bout, et la compatibilité PowerShell 5.1 (le shell réellement utilisé par l'installateur, distinct de `pwsh` 7 utilisé lors des vérifications précédentes).
- **PR #247 / promotion #248** : régression introduite par #242 — la sonde `isAclGrantable` vérifiait `WRITE_DAC` en lisant l'ACL complète d'un fichier puis en réécrivant exactement la même liste (`AclFileAttributeView.setAcl(view.getAcl())`), une opération de remplacement intégral non sûre sous concurrence. Une course avec l'`icacls /grant`/`/remove:g` d'un autre processus sur la même ressource partagée pouvait capturer un état d'ACL transitoire et incomplet, puis le rendre permanent. Observé en conditions réelles : le script de lancement AppContainer partagé s'est retrouvé avec une **DACL entièrement vide**, illisible et non réparable par son propre propriétaire sans élévation. Corrigé en remplaçant la sonde par les mêmes opérations additives et ciblées sur une seule identité (`icacls /grant` puis `/remove:g`) déjà utilisées et sûres dans le lanceur réel.

Les trois corrections ont été vérifiées directement sur un poste Windows réel, en utilisateur standard non élevé : installation, mise à jour d'un backend Docker déjà configuré, et 15 invocations CLI concurrentes sans corruption d'ACL.

## État produit

- **C0 → M30** : terminés et intégrés.
- **M29 issue #107** : **CLOSED** ; **M29 PR #108** intégrée.
- **M30 PR #110** intégrée ; **M30 promotion PR #111** intégrée.
- **hardening PR #113** intégré ; **M28 Windows CI PR #117** intégré.
- **#98 sandbox OS réelle** : **IMPLÉMENTÉE + QUALIFIÉE** sur Linux et Windows dans la campagne de convergence.
- **PR #224** : traversées projet/NEXUS et couverture ciblée intégrées.
- **PR #225** : confinement workspace provider/discovery/ignore rules intégré.
- **PR #226** : provenance launcher IntelliJ et derniers walkers provider intégrés.
- **PR #227** : provider egress, provenance `CommandLocator`, reparse private storage et contrat de fallback confinement intégrés.
- **PR #228** : composition managed-local-provider et provenance des autorités de sandbox **intégrées** au HEAD qualifié `1a551ff72f95db4e14e8a9597d897491b9c1589a`, merge `a042e97ac5e3e2ab7207fa603d85563ea1f71712`.
- **remédiation quota/readiness post-#228 (PR #229)** : **intégrée**.
- **PR #230–#241** : refactors de maintenabilité, moteur de mise à jour transactionnelle Windows, hardening MCP/installateur, ligne **1.1.0-SNAPSHOT** ouverte — **intégrés**.
- **PR #242/#243, #246, #247/#248** (Windows non-admin, blocage mise à jour Docker MCP, course ACL) : **intégrées** dans `develop` et promues sur `main`.

## Release 1.0.1

La release **MINOS v1.0.1** a été publiée le **9 août 2026** après validation utilisateur réelle du setup Windows.

Tag autoritatif :

```text
v1.0.1 → f762025d66e33c40324c811079f1527d122f90f9
```

La release **v1.0.1 est PUBLIÉE et immuable**.

- URL : https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1 ;
- publication : **10 assets**, soit **5 paires** artefact/checksum ;
- workflow de publication : `31288322126` ;
- setup Windows, distribution et plugin IntelliJ restent soumis aux gates de provenance, OSV et Plugin Verifier applicables.

La ligne de développement courante est **1.1.0-SNAPSHOT** (PR #237). Aucune release 1.1.0 n'est publiée à ce jour.

## Garanties structurantes courantes

- snapshots structurés autoritatifs et promotions fail-closed ;
- API/CLI/MCP/NEXUS/IntelliJ au-dessus du métier sans autorité concurrente ;
- providers locaux exécutés depuis une copie éphémère bornée avec réseau OS-enforced et job boundary agrégé ;
- qualification provider locale supervisée strictement distincte de toute claim hostile ;
- workers distants/hostiles fail-closed sans hard filesystem quota `OS_ENFORCED` ;
- egress provider `DENY` par défaut ;
- exécutables qui créent la sandbox résolus depuis des autorités système canoniques ;
- environnement provider allowlisté ;
- stockage privé AppContainer inclus dans la frontière de write containment ;
- local storage owner-only, symlink/junction/reparse refusés avant mutation ;
- indexation Windows sans droits administrateur : la JVM hôte n'est jamais traitée comme runtime provider, et toute racine non accordable sans élévation fait échouer le plan proprement plutôt que d'atteindre `icacls` ;
- mutations d'ACL AppContainer toujours additives et ciblées sur une seule identité, jamais un remplacement intégral de liste sous concurrence ;
- mise à jour du backend Docker MCP non-interactive et fail-fast (jamais bloquée sur une invite `docker compose`) ;
- Git distant, PostgreSQL, hosted control plane, MCP et Ollama conservent leurs frontières fail-closed déjà qualifiées ;
- supply-chain CI et release épinglées à des références immuables lorsqu'une telle garantie est revendiquée.

## Qualification de toute nouvelle remédiation

Une correction ne sera déclarée intégrée qu'après succès exact-head des workflows applicables sur son HEAD final. Aucun merge n'est autorisé par ce document : l'intégration exige toujours une décision explicite après qualification.
