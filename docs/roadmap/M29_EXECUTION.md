# M29 — Autonomous Docker Runtime & Native Parity

Statut : **PLANIFIÉ — démarrage prévu le 3 août 2026**  
Issue : **#107 — M29 — Autonomous Docker Runtime & Native Parity**

## Objectif produit

Faire du runtime Docker MINOS un **backend autonome de premier rang**, fonctionnellement équivalent au runtime natif pour :

- administration ;
- découverte et enregistrement de projets/workspaces ;
- providers et indexation ;
- snapshots structurés ;
- persistance et récupération ;
- vector store sémantique ;
- recherche structurée, sémantique et hybride ;
- architecture, impact, related tests et ProgramGraph ;
- MCP ;
- intégrations Copilot / Claude / Codex ;
- install / upgrade / switching / uninstall.

À la fin de M29, choisir **Natif Windows** ou **Docker isolé** doit changer le lieu d'exécution, pas les capacités métier disponibles ni les résultats attendus.

## État de départ autoritatif

Le Docker release actuel sait :

- construire une image MINOS à partir du JAR de release ;
- démarrer un conteneur durci ;
- monter les projets en lecture seule sous `/workspace/projects` ;
- persister un home Docker séparé sous `/var/lib/minos` ;
- fonctionner avec `network_mode: none` ;
- exposer une session MCP STDIO via `docker exec -i`.

Mais il n'est **pas encore autonome ni équivalent au natif** :

- les clients IA sont configurés vers le MCP natif ;
- le home Docker est distinct de `%LOCALAPPDATA%\MINOS\data` ;
- le registre projet persiste actuellement des chemins physiques absolus ;
- les chemins Windows et Linux ne sont pas abstraits par un mapping runtime ;
- l'image Docker n'embarque pas encore l'ensemble du plan d'administration et des runtimes/providers nécessaires à l'indexation autonome ;
- les gates Docker historiques valident le runtime/configuration mais pas une parité métier complète native/Docker.

## Données et vector store

MINOS possède déjà un **vector store sémantique persistant v2** :

```text
index-v2.bin
float32 vector components
```

Les snapshots structurés restent la source d'autorité et les résultats vectoriels restent `HEURISTIC`.

M29 **ne crée pas une nouvelle base vectorielle externe** par défaut. Il doit :

- rendre les stores existants portables et cohérents côté Docker ;
- préserver l'identité provider/model/dimensions/stableKey/checksum ;
- reconstruire ou migrer de façon déterministe lorsque nécessaire ;
- conserver le scan exact actuel tant qu'une nouvelle mesure ne justifie pas ANN ;
- ne pas introduire HNSW/Lucene/une vector DB tierce sans gate `measure before optimize`.

## Principes non négociables

1. **Docker autonome** : aucun index natif préalable requis.
2. **Parité métier** : pas de sous-ensemble fonctionnel Docker présenté comme équivalent.
3. **Identités stables** : UUID projet/workspace indépendants du runtime.
4. **Chemins portables** : le chemin physique n'est plus une identité portable.
5. **Snapshots autoritatifs** : le vectoriel ne remplace pas le modèle structuré.
6. **Runtime offline** : préparation des providers pendant build/install ; pas de téléchargement implicite en RUN.
7. **Sécurité Docker conservée** : `network_mode: none`, projets read-only pour le MCP, filesystem read-only quand possible, `cap_drop: ALL`, `no-new-privileges`.
8. **MCP read-only** pour les agents ; administration/indexation via un plan explicite séparé si nécessaire.
9. **Clients IA backend-agnostic** : Copilot/Claude/Codex ne doivent pas être reconfigurés manuellement pour changer de backend.
10. **Claim de parité interdit sans preuve comparative exact-head**.

## Architecture cible

```text
Copilot / Claude / Codex
          |
          v
     minos.exe mcp
          |
    backend selection
       /       \
      /         \
 native         docker
   |              |
MCP Java     docker exec -i
                 |
             MCP Java
```

Le point d'entrée client reste stable. MINOS possède le routage et l'ownership du backend.

## Avancement

| Sous-étape | Objet | État |
|---|---|---|
| M29-S1 | Backend contract & ADR | ⬜ |
| M29-S2 | Project identity, path mapping & portable persistence | ⬜ |
| M29-S3 | Autonomous Docker administration plane | ⬜ |
| M29-S4 | Provider-complete Docker image | ⬜ |
| M29-S5 | Autonomous indexing & vector lifecycle | ⬜ |
| M29-S6 | Backend-agnostic MCP client integration | ⬜ |
| M29-S7 | Installer, switching & lifecycle | ⬜ |
| M29-S8 | Native/Docker parity qualification | ⬜ |

---

## M29-S1 — Backend contract & ADR

### Implémenter

- définir un contrat explicite `native | docker` ;
- persister le backend sélectionné dans la configuration MINOS ;
- conserver `minos.exe mcp` comme point d'entrée stable ;
- définir démarrage, erreurs, timeout, shutdown, interruption et exit codes ;
- interdire le fallback silencieux Docker -> natif ;
- définir la bascule transactionnelle de backend ;
- produire l'ADR de référence.

### Gate

- tests de configuration valide/invalide ;
- backend Docker absent/daemon arrêté = erreur explicite ;
- aucun fallback non demandé ;
- docs + ADR alignées.

---

## M29-S2 — Project identity, path mapping & portable persistence

### Problème à résoudre

Le registre actuel persiste un `rootPath` physique. Exemple :

```text
Windows : N:\workspace-dev\minos-code-intelligence
Docker  : /workspace/projects/minos-code-intelligence
```

Ces chemins représentent le même projet logique mais ne sont pas interchangeables.

### Implémenter

- séparer identité logique et localisation physique ;
- préserver UUID projet/workspace ;
- introduire un mapping host/container explicite ;
- qualifier les migrations de registres existants ;
- rendre portables registre, workspace metadata, index state, snapshots et métadonnées de provenance ;
- qualifier toutes les surfaces dépendantes d'un chemin : `get_source`, inspection, Git, architecture, impact, ProgramGraph, etc. ;
- définir une représentation canonique des références documentaires dans les snapshots/vector stores.

### Vector store

- réutiliser `index-v2.bin` ;
- préserver float32 et identité provider/model/dimensions ;
- garantir cohérence des stable keys/checksums après mapping ;
- rebuild déterministe lorsqu'une migration directe n'est pas sûre.

### Gate

```text
même projectId
même workspaceId
même snapshot logique
chemin Windows != chemin Docker
résolution métier équivalente
```

Migration idempotente, rollback sûr, aucune dépendance à un chemin Windows absolu dans l'état déclaré portable.

---

## M29-S3 — Autonomous Docker administration plane

Docker doit pouvoir administrer MINOS sans état natif préparé.

### Surface minimale

```text
minos doctor
minos tools list
minos tools verify
minos project add
minos project list
minos project inspect
minos index
minos index-status
minos semantic/hybrid status
minos mcp
```

### Implémenter

- entrypoint/commande d'administration distincte du serveur MCP si nécessaire ;
- accès écriture contrôlé à `/var/lib/minos` pour administration/indexation ;
- MCP client toujours read-only ;
- persistance durable après restart/recreate ;
- diagnostics exploitables depuis Windows.

### Gate

Projet neuf -> `project add` -> `index` -> `READY` **sans aucune étape native**.

Restart/recreate -> registre, snapshots, vector store et index state toujours disponibles.

---

## M29-S4 — Provider-complete Docker image

### Implémenter

Inventorier les runtimes/providers réellement nécessaires aux capacités revendiquées :

- Java ;
- TypeScript / JavaScript ;
- C / C++ ;
- C# ;
- Go ;
- Rust ;
- autres providers effectivement supportés par la ligne produit.

Préparer les outils pendant BUILD/install :

```text
network autorisé pendant préparation contrôlée
           ↓
providers/runtimes vérifiés + provenance
           ↓
image finale
           ↓
network_mode: none en RUN
```

### Exigences

- versions verrouillées ;
- hashes/provenance ;
- SBOM ;
- aucun `apt/npm/coursier/...` opportuniste pendant une indexation isolée ;
- `doctor` reflète exactement les capacités réellement disponibles.

### Gate

Chaque provider revendiqué doit exécuter un fixture réel dans Docker. Provider absent = capability absente, jamais extrapolée.

---

## M29-S5 — Autonomous indexing & vector lifecycle

### Implémenter

- discovery/indexation depuis les projets montés ;
- fingerprints et invalidation ;
- `NONE | FULL | INCREMENTAL` uniquement selon capabilities provider ;
- staging et promotion atomique ;
- conservation du snapshot précédent sur échec ;
- recovery ;
- vector store v2 Docker ;
- embeddings locaux conformes aux frontières M23 ;
- semantic/hybrid search ;
- cohérence après restart et upgrade.

### Gate

```text
projet vierge
→ FULL
→ SUCCEEDED / READY
→ vector store disponible
→ semantic/hybrid query PASS
→ deuxième run NONE si qualifié
```

Échec provider -> ancien snapshot préservé -> recovery -> `READY`.

---

## M29-S6 — Backend-agnostic MCP client integration

### Objectif

Les clients IA ne doivent plus connaître le détail natif/Docker.

### Clients

- GitHub Copilot — JetBrains / IntelliJ ;
- GitHub Copilot CLI ;
- Claude Code ;
- Claude Desktop ;
- OpenAI Codex CLI/Desktop.

### Implémenter

- conserver le serveur client `minos` ;
- conserver `app\minos.exe mcp` comme commande stable ;
- router vers natif ou Docker ;
- qualifier `docker exec -i` comme transport STDIO ;
- préserver preflight, ownership, backups et uninstall sélectif ;
- bascule de backend sans édition manuelle des configurations tierces.

### Gate par client et par backend

```text
initialize
notifications/initialized
tools/list
requête réelle MINOS
réponse attendue
shutdown/cleanup
```

---

## M29-S7 — Installer, switching & lifecycle

### Wizard cible une fois la parité acquise

```text
Mode MCP
( ) MCP natif Windows — recommandé
( ) MCP Docker — isolation renforcée
( ) Ne pas configurer le MCP maintenant

Clients IA
[ ] Copilot JetBrains
[ ] Copilot CLI
[ ] Claude Code
[ ] Claude Desktop
[ ] Codex
```

### Implémenter

- choix backend exclusif ;
- détection Docker Desktop/daemon ;
- racine projets Docker ;
- préparation et validation du backend avant activation ;
- switch `native -> docker` transactionnel ;
- switch `docker -> native` transactionnel ;
- rollback si nouveau backend non READY/handshake KO ;
- update/repair/uninstall ;
- purge `%LOCALAPPDATA%\MINOS` explicite, **Non** par défaut ;
- aucune suppression de configuration tierce non détenue.

### Gate

- install natif ;
- install Docker ;
- switch dans les deux sens ;
- upgrade ;
- repair ;
- uninstall conserve données ;
- uninstall purge données ;
- clients IA toujours valides après switch.

---

## M29-S8 — Native/Docker parity qualification

Utiliser les mêmes fixtures/corpus et comparer les deux runtimes.

### Comparer au minimum

- projets/workspaces ;
- index states ;
- snapshots ;
- symboles/occurrences/relations ;
- structured search ;
- `get_source` ;
- architecture ;
- impact ;
- related tests ;
- ProgramGraph ;
- semantic search ;
- hybrid search/context ;
- vector store identity/contents selon tolérances définies ;
- MCP `tools/list` ;
- réponses MCP représentatives ;
- restart/recovery ;
- temps d'indexation ;
- latence de requête ;
- mémoire/disque.

### Rapport

Un rapport machine-readable doit identifier :

```text
fixture
backend
provider set
snapshot id
vector model identity
result digest
allowed path/provenance differences
performance measurements
PASS/FAIL
```

### Gate final

```text
native result == docker result
```

aux seules différences explicitement autorisées de chemin/provenance/runtime près.

Aucune UI, documentation ou release ne peut présenter Docker comme équivalent avant ce gate exact-head.

---

## Relation avec 1.0.1

Le correctif Windows 1.0.1 ne doit **pas** prétendre que Docker est déjà un backend équivalent tant que M29 n'est pas terminé.

Avant M29 :

- MCP natif = parcours client IA complet/recommandé ;
- Docker = runtime isolé avancé, non déclaré paritaire.

Après M29 qualifié, le Wizard pourra honnêtement offrir un choix backend exclusif natif/Docker avec la même page clients IA.

## Hors périmètre

- **#98** reste indépendante : sandbox OS réelle des workers distants ;
- pas de SaaS/hosted implicite ;
- pas de nouvelle base vectorielle externe sans décision mesurée ;
- pas d'ANN/HNSW/Lucene sans preuve qu'un scan exact n'est plus suffisant.

## Définition de terminé

M29 est terminé uniquement lorsque :

1. Docker indexe un projet neuf sans état natif ;
2. Docker restaure son état après restart ;
3. registre/snapshots/vector store sont cohérents et portables ;
4. le mapping de chemins est qualifié ;
5. tous les providers revendiqués fonctionnent sans réseau en RUN ;
6. tous les clients MCP supportés peuvent utiliser Docker ;
7. le même point d'entrée client route proprement vers les deux backends ;
8. les résultats natif/Docker passent le rapport de parité ;
9. install/switch/update/uninstall sont qualifiés ;
10. docs, ADR et guides sont alignés ;
11. aucune claim de parité n'est publiée avant preuve exact-head.

## Démarrage

Démarrage prévu : **3 août 2026**.

Au démarrage :

1. créer la branche dédiée M29 depuis la base autoritative choisie ;
2. vérifier l'état de 1.0.1 et décider explicitement si M29 est post-release ou intégré avant publication ;
3. créer l'ADR/contrat S1 avant d'écrire le routage Docker ;
4. ne pas déclencher de CI sans autorisation explicite du mainteneur.
