# Feuille de route — MINOS

Statut au **27 août 2026** : **C0 → M30 terminés et intégrés ; MINOS 1.0.1 publiée ; hardening #113–#248 intégré ; ligne de développement 1.1.0-SNAPSHOT ouverte.**

La remédiation quota/readiness issue du réaudit de #228 est **intégrée** (PR #229), tout comme le moteur de mise à jour transactionnelle Windows (#231), le hardening MCP/installateur qui a suivi (#232–#241), et les trois corrections Windows non-admin / Docker MCP de fin août (#242/#243, #246, #247/#248).

La version historique détaillée antérieure à la réconciliation post-#226 est conservée intégralement dans [`history/reconciliations/ROADMAP-pre-post226-audit-20260821.md`](history/reconciliations/ROADMAP-pre-post226-audit-20260821.md) ; la version courante au 21 août 2026 (remédiation post-#228 encore en qualification) dans [`history/reconciliations/ROADMAP-pre-post243-audit-20260827.md`](history/reconciliations/ROADMAP-pre-post243-audit-20260827.md). L'état opérationnel courant est dans [`STATUS.md`](STATUS.md).

## Principes durables

- une capacité n'est acquise qu'avec une preuve reproductible ;
- facts, dérivations, heuristiques et observations partielles restent distincts ;
- les snapshots structurés restent autoritatifs ;
- les providers absents ou non qualifiés ne sont jamais extrapolés ;
- CLI, API, MCP, NEXUS et IntelliJ restent des surfaces au-dessus du métier ;
- remote/hosted/sandbox restent fail-closed lorsqu'une garantie n'est pas prouvée ;
- le contrat managed-local-provider reste distinct d'une claim hostile/untrusted ;
- l'accès réseau d'un provider est `DENY` par défaut ;
- les exécutables qui constituent l'autorité de sandbox ne sont pas choisis dans un PATH utilisateur ;
- une release publiée est immuable ;
- le runtime packagé doit être testé, pas seulement le JAR ;
- une publication est bloquée par les vulnérabilités connues ou l'absence de qualification exacte du candidat ;
- l'indexation Windows ne requiert jamais de droits administrateur, y compris pendant le développement (IDE) et pendant une mise à jour de backend déjà configuré.

## Trajectoire livrée

| Jalon | Résultat principal | État |
|---|---|---|
| C0 | cadrage fonctionnel et architectural | ✅ terminé |
| M0–M8 | discovery, indexation, symboles, relations, recherche, architecture, incrémental, impact | ✅ terminé |
| M9–M14 | CLI, MCP, API, multi-repo/Git, export NEXUS, installation PROD Windows | ✅ terminé |
| M15–M20 | reactor, scalabilité, provider platform, IntelliJ, advanced intelligence, semantic hybrid | ✅ terminé |
| M21–M28 | Production Integrity → Production Convergence | ✅ terminé |
| M29 | Autonomous Docker Runtime & Native Parity | ✅ M29 issue #107 CLOSED / M29 PR #108 intégrée |
| M30 | Advanced Installer, Ollama Docker & PostgreSQL/pgvector | ✅ M30 PR #110 + M30 promotion PR #111 |
| Hardening release/installer | supply-chain, Windows CI, sécurité release | ✅ #113 ; M28 Windows CI PR #117 |
| #98 Real OS worker sandbox | bubblewrap/cgroup + AppContainer/Job Object | ✅ implémenté et qualifié |
| #224–#227 | confinement filesystem/provider, provenance, egress | ✅ intégrés |
| #228 | composition managed-local-provider + provenance des autorités de sandbox | ✅ intégrée ; head qualifié `1a551ff72f95db4e14e8a9597d897491b9c1589a`, merge `a042e97ac5e3e2ab7207fa603d85563ea1f71712` |
| Réaudit post-#228 (#229) | exhaustivité quota provider + qualification Windows `READY` + gates/docs | ✅ intégrée |
| #230 | refactors de maintenabilité + décomposition CI M23/M25/M26/M27 | ✅ intégrée |
| #231–#232 | moteur de mise à jour transactionnelle Windows + revue de sécurité à 8 angles | ✅ intégrées |
| #233–#236 | hardening test/doc des clients MCP + capstone de sécurité | ✅ intégrées |
| #237 | ouverture de la ligne **1.1.0-SNAPSHOT** | ✅ intégrée |
| #238–#241 | détection MSIX Claude Desktop, UX assistant MCP, cache image provider, verrouillage checkbox | ✅ intégrées |
| #242/#243 | indexation Windows sans droits administrateur (JVM hôte jamais traitée comme runtime provider) | ✅ intégrée ; promue sur `main` |
| #246 | fin du blocage `docker compose` en mise à jour de backend Docker MCP | ✅ intégrée |
| #247/#248 | correction de la course sur la sonde ACL (`isAclGrantable`) | ✅ intégrée ; promue sur `main` |

## Ligne de sécurité après #228

### Deux niveaux de qualification sandbox

Le worker distant/hostile conserve le niveau fort : process/memory/CPU/descendants et filesystem bytes+entries doivent satisfaire le contrat hostile, notamment un quota filesystem `OS_ENFORCED`. Tant que le hard quota disque n'existe pas, ce chemin reste fail-closed.

Le provider local géré possède un contrat distinct. Il exige réseau OS-enforced, job boundary agrégé OS, timeout, quotas filesystem appliqués pendant l'exécution et reclamation scratch. `SUPERVISED_HARD_KILL` reste accepté uniquement pour ce niveau local ; il ne modifie jamais `supportsUntrustedCode()`.

### Remédiation quota/readiness post-#228 — intégrée

Le réaudit du merge #228 a montré que la garantie supervisée devait couvrir non seulement les paths explicitement accordés par MINOS mais aussi les sinks implicites de la sandbox. La PR #229 a intégré :

- Linux/Java : une perte réelle de visibilité d'un writable root est désormais un breach ; les suppressions concurrentes normales restent tolérées ;
- Windows : le budget historique **8 GiB / 400 000 entrées** est partitionné de façon conservative entre roots explicites (**7 GiB / 350 000**) et stockage fichier privé AppContainer (**1 GiB / 50 000**) ;
- les mutations du registre privé AppContainer sont refusées avant le démarrage effectif du child ;
- le superviseur privé est armé avant `ResumeThread` ;
- la découverte Windows exécute un probe réel AppContainer/Job Object avant d'autoriser le backend ;
- l'état `READY` des providers gérés dépend du sandbox réellement utilisé en production, sans deuxième autorité ownership-only inutilisée.

### Indexation Windows sans droits administrateur (#242/#243, #247/#248)

Deux défauts distincts ont pu forcer un usage administrateur pour indexer sur Windows, tous deux corrigés fin août 2026 :

- la JVM qui exécute MINOS elle-même (`System.getProperty("java.home")`, par exemple le JDK sélectionné par une configuration d'exécution IntelliJ sous `Program Files`) était traitée inconditionnellement comme une racine de lecture à accorder à l'AppContainer, ce qui exige un `icacls` impossible sans élévation sur un chemin non possédé par l'utilisateur. La JVM hôte n'est plus jamais une racine de sandbox ; toute racine candidate non accordable sans élévation fait échouer le plan proprement (`isAclGrantable`/`requireAclGrantable`) avant tout appel `icacls`, avec un diagnostic explicite ;
- la sonde de vérification `isAclGrantable` elle-même, ajoutée par la correction précédente, remplaçait l'intégralité de la DACL d'un fichier par une copie de ce qu'elle venait de lire — une opération non sûre sous concurrence qui a produit, en conditions réelles, un fichier de lancement AppContainer partagé avec une **DACL entièrement vide**. Remplacée par les mêmes opérations additives et ciblées sur une seule identité (`icacls /grant` puis `/remove:g`) déjà utilisées par le lanceur réel, avec un test de régression qui stresse directement cette course.

### Mise à jour du backend Docker MCP sans blocage (#246)

La mise à jour d'un backend Docker MCP déjà configuré vers une nouvelle version pouvait bloquer indéfiniment sur l'invite interactive `docker compose` *« Recreate (data will be lost)? »* (le volume `minos-provider-tools` porte des labels qui changent à chaque build). Les invocations `docker compose` concernées passent désormais systématiquement par un stdin non-interactif garanti, échouant vite au lieu de bloquer pour toujours. La PR porte également plusieurs correctifs du profil PostgreSQL/Ollama managé (réseau de provisioning Ollama dédié, service `minos-data-bootstrap` présent dans le profil connecté, scratch dir pour le plan requête) et la compatibilité PowerShell 5.1 (le shell réellement utilisé par l'installateur).

### Provenance des commandes de sécurité

Les protections de #228 restent inchangées : `bwrap`, `prlimit`, `sh`, `systemctl` et `systemd-run` proviennent de racines système Linux canoniques root-owned et non group/world-writable. PowerShell et `cmd.exe` sont ancrés à `SystemRoot\System32` ; `ComSpec` n'est pas une autorité de confiance.

### Gates

Le workflow **Post-228 Hardening Invariants** est exact-head sur Linux et Windows. Il exécute :

- le gate documentaire courant ;
- les invariants statiques quota/readiness ;
- `mvn verify` ;
- les tests réels AppContainer/Job Object sous Windows ;
- un seuil JaCoCo ciblé sur `ProviderWriteQuotaSupervisor`.

Cette barrière complète les workflows historiques sans diminuer leurs seuils ni supprimer leurs contrôles.

## Release 1.0.1 — publiée

La **Release 1.0.1 est publiée** et reste immuable :

- tag/release : `v1.0.1` ;
- commit : `f762025d66e33c40324c811079f1527d122f90f9` ;
- URL : https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1 ;
- **10 assets** publiés ;
- qualification incluant OSV, packaging et **Plugin Verifier**.

La ligne de développement courante est **1.1.0-SNAPSHOT** (PR #237). Aucune release 1.1.0 n'est publiée à ce jour.

## Suite

Aucun nouveau jalon fonctionnel n'est ouvert. La dette durable reste le hard filesystem quota : si une exécution distante réellement hostile doit être activée, elle devra obtenir une primitive qui refuse l'écriture avant dépassement au lieu de reclassifier une supervision périodique.

Vérification manuelle encore ouverte : reproduire la transition d'image Docker MCP d'une version à l'autre (pas seulement un rafraîchissement à l'identique) pour confirmer que #246 couvre aussi ce chemin précis — la seule occurrence observée en conditions réelles n'a pas pu être rejouée faute d'avoir conservé le journal `backend-switch.log` de l'échec avant réinstallation.
