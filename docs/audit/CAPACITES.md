# Carte des capacités — audit MINOS 2026-10

Découpage du dépôt en capacités auditables. Une capacité est une promesse fonctionnelle
vérifiable, pas un module Maven : `minos-engine` n'est pas une capacité, « reprise d'une
indexation interrompue » en est une.

Cette carte découpe tout le reste de l'audit. Elle est à valider avant la phase 2.

## Comment elle a été construite

Trois sources croisées : les 50 ADR de [`docs/adr/`](../adr/README.md) et leur statut, les
garanties énoncées dans [`STATUS.md`](../STATUS.md), et les 14 modules du reactor
(`pom.xml`).

**Limites à connaître avant de s'en servir.** Elle n'a pas été vérifiée contre le code : les
outils `minos_architecture` et `minos_index_status` du serveur MCP ont échoué au moment de la
rédaction (« MINOS tool execution failed »), donc aucune arête de dépendance n'a été confirmée
par l'index. La colonne *poids* est une appréciation qualitative issue des tailles de fichiers
et du nombre d'ADR concernés, pas une mesure. La phase 1 doit confirmer les deux : rejouer
`minos_project_structure` et `minos_architecture` pour obtenir les volumes réels et les arêtes
effectives, et corriger cette carte si elle se révèle fausse.

## Priorités

- **1** — critique en sécurité, exposé publiquement sous contrat, ou identifié comme fragile
  par un ADR non encore implémenté. À auditer d'abord, intégralement.
- **2** — important, mais soit déjà couvert par un chantier en cours, soit sans exposition
  directe.
- **3** — à auditer si le temps le permet. Le découpage assume que ces capacités puissent ne
  pas être traitées dans cette campagne.

## Tableau de pilotage

| # | Capacité | Chemin de spec | Modules porteurs | Surfaces | ADR de référence | Poids | Prio |
|---|----------|----------------|------------------|----------|------------------|-------|------|
| 1 | Confinement du code non fiable | `confinement-code-non-fiable` | minos-runtime-local, minos-engine (`runtime`) | aucune (interne) | 0036 *Proposed*, 0038, 0041 | élevé | **1** |
| 2 | Plan de contrôle tenant hébergé | `controle-tenant-heberge` | minos-engine (`hosted`) | API Java, MCP | 0035 | élevé | **1** |
| 3 | Stockage privé et secrets | `stockage-prive-et-secrets` | minos-engine (`io`), minos-storage-local | aucune (interne) | — (garanties `STATUS.md`) | moyen | **1** |
| 4 | Indexation distante de code non fiable | `indexation-distante` | minos-engine (`remote`), minos-integration-git | CLI | 0033, 0041 | élevé | **1** |
| 5 | Surfaces publiques versionnées | `surfaces-publiques` | minos-cli, minos-api, minos-mcp, minos-nexus | CLI, API Java, MCP, NEXUS | 0016, 0017, 0018, 0020, 0027 | élevé | **1** |
| 6 | Cycle de vie de l'indexation | `cycle-de-vie-indexation` | minos-engine (`orchestration`, `incremental`) | CLI, API Java | 0006, 0014, 0039 | très élevé | **1** |
| 7 | Persistance des snapshots | `persistance-snapshots` | minos-storage-local, minos-storage-postgresql, minos-engine (`store`) | aucune (interne) | 0003, 0023, 0024, 0025, 0046, 0047 *Proposed*, 0055–0056 *à implémenter* | élevé | 2 |
| 8 | Racine de composition et frontières | `racine-composition-et-frontieres` | minos-bootstrap, minos-app, minos-application | aucune (structurelle) | 0022, 0042, 0044, 0045, 0057 | élevé | 2 |
| 9 | Runtime d'indexation et distribution | `runtime-et-distribution` | minos-runtime-local, packaging, docker | CLI | 0021 *partiellement remplacé*, 0037 *parité en attente*, 0040 | moyen | 2 |
| 10 | Program graph et intelligence d'architecture | `program-graph-et-architecture` | minos-engine (`query`, architecture) | API Java, MCP | 0013, 0028, 0036 *Proposed* | moyen | 2 |
| 11 | Providers SCIP polyglottes | `providers-scip` | minos-provider-scip | CLI | 0002, 0030, 0032 | élevé | 2 |
| 12 | Client IntelliJ | `client-intellij` | minos-intellij (hors reactor) | protocole CLI JSON | 0027 | moyen | 2 |
| 13 | Modèle de connaissance du code | `modele-de-connaissance` | minos-domain | API Java | 0009, 0010, 0019 | moyen | 3 |
| 14 | Découverte et négociation des indexeurs | `decouverte-et-negociation` | minos-engine (`discovery`, `orchestration`) | CLI | 0008, 0026 | moyen | 3 |
| 15 | Requêtes bornées | `requetes-bornees` | minos-engine (`query`) | CLI, API Java, MCP | 0011 | moyen | 3 |
| 16 | Couche sémantique optionnelle | `couche-semantique` | minos-engine (semantic) | MCP | 0029, 0031 | moyen | 3 |
| 17 | Tests liés et analyse d'impact | `tests-lies-et-impact` | minos-engine (`query`) | CLI, API Java, MCP | 0012, 0015 | moyen | 3 |
| 18 | Observations runtime partielles | `observations-runtime` | minos-engine (`dynamic`) | MCP | 0034 | faible | 3 |

Chemin complet d'une spec : `openspec/specs/<chemin de spec>/spec.md`.

---

## Priorité 1 — à auditer intégralement

### 1. Confinement du code non fiable

**Ce qui est promis.** Un provider local s'exécute dans une copie éphémère bornée, sous
containment OS, avec une frontière de job agrégée ; un worker hostile reste fail-closed quand
le quota d'écriture dur de l'OS n'est pas disponible ; l'egress provider est `DENY` par défaut ;
l'environnement provider est sur liste blanche.

**Pourquoi priorité 1.** C'est la garantie la plus forte du produit et la plus coûteuse à
tenir. L'ADR-0036, qui interdit les claims sandbox non qualifiés, est encore **Proposed** —
donc la règle de capability-honesty s'applique à une capacité dont le cadre n'est pas clos.
L'ADR-0041 acte que la qualification du code non fiable a été **refusée** par décision : le
quota d'écriture est supervisé, pas imposé par l'OS. L'audit doit vérifier que cette limite est
dite partout où la capacité est exposée, et pas seulement dans l'ADR.

**À vérifier en particulier.** Que chaque chemin d'exécution d'un provider passe par le
containment ; qu'aucun mode dégradé n'exécute du code non fiable ; que les primitives Linux
(bubblewrap, cgroup v2) et Windows (AppContainer, Job Object) refusent de la même façon ;
que `minos doctor` expose le refus.

**Déjà suivi.** `SEC-SUIVI.md`, `AUDIT-2026-09.md` (constats S*, A1), `S15-REGRESSION-RUNTIME-GRANT.md`,
`S23-SUIVI.md`.

### 2. Plan de contrôle tenant hébergé

**Ce qui est promis.** Opt-in, chiffré AES-256-GCM avec AAD, audité par chaîne, alimenté par
des clés externes ; clés dérivées en HMAC-SHA-256 avec nettoyage des buffers ; écriture
atomique durable ; limites de taille.

**Pourquoi priorité 1.** Authentification, autorisation, rétention et audit dans un même
périmètre, exposés par l'API Java et MCP. Un défaut ici est exploitable, pas seulement gênant.

**À vérifier en particulier.** Escalade de rôle, saturation de la chaîne d'audit, mutation
avant validation, throttle de refus, et la couverture de tests de ces quatre scénarios —
l'audit de septembre notait qu'aucun test ne couvrait S1, S2, S3 ni Q1.

**Déjà suivi.** `SEC-SUIVI.md`, `AUDIT-2026-09.md` (S1, S2, S3, Q1).

### 3. Stockage privé et secrets

**Ce qui est promis.** Chemins de secrets relatifs confinés physiquement à `MINOS_HOME` ;
lecture sous plafond d'octets avec décodeur UTF-8 strict qui refuse au lieu de remplacer ;
symlink, junction et reparse refusés aux frontières sensibles ; scratch privé pour PostgreSQL ;
politique TLS qualifiée exigée pour un PostgreSQL distant.

**Pourquoi priorité 1.** Peu de code, conséquence maximale, et c'est la frontière que
franchirait un provider compromis.

**À vérifier en particulier.** Que le refus est systématique et non conditionnel ; qu'une
lecture seule n'écrit pas — l'audit de septembre a mesuré l'inverse (constat R12 :
`MinosApplication.open` crée le squelette de stockage, et lire le registre prend un verrou).

**Déjà suivi.** `SEC-SUIVI.md`, `FIAB-SUIVI.md` (R9, R12).

### 4. Indexation distante de code non fiable

**Ce qui est promis.** Sources distantes épinglées par révision immuable ; artefacts worker
bornés, vérifiés et concordants ; Git distant en HTTPS avec host, ref, SHA et path validés ;
`remote index` fail-closed sur tous les OS, refus journalisé.

**Pourquoi priorité 1.** C'est le point d'entrée du code non fiable dans le système, et la
capacité dont la qualification a été explicitement refusée.

**À vérifier en particulier.** Que les douze conditions de réutilisation d'un artefact sont
toujours combinées en ET et qu'un écart annule la reprise entière ; que le bail couvre toute
l'exécution ; qu'une matérialisation distante ne fuit pas sur échec d'acquisition de lease (ce
défaut a été corrigé en 1.2.0 — vérifier qu'il n'est pas revenu ailleurs).

**Déjà suivi.** `SEC-SUIVI.md`, `AUDIT-2026-09.md` (A1), `FIAB-SUIVI.md`.

### 5. Surfaces publiques versionnées

**Ce qui est promis.** CLI stable et versionnée, API Java publique indépendante des modèles
internes, MCP STDIO en lecture seule, NEXUS par contrat JSON local versionné, protocole CLI
JSON négocié avec IntelliJ. Toutes additives : pas de rupture de contrat.

**Pourquoi priorité 1.** C'est ce que les utilisateurs et les clients externes voient. Une
rupture ici casse des intégrations hors du dépôt, et ne se rattrape pas par un correctif.

**À vérifier en particulier.** Qu'aucune surface n'expose un modèle interne ; que MCP n'écrit
rien, sur aucun chemin ; que les codes de sortie de la CLI sont tenus et documentés ; que les
messages d'erreur publics ne recopient pas de fragment de fichier lu (constat Q27, ouvert) ;
que `listWorkspaces` et `findWorkspace` restent cohérents avec les autres commandes face à une
entrée abîmée (Q26, ouvert).

**Déjà suivi.** `CLI-SUIVI.md`, `Q25-Q26-SUIVI.md`, `AUDIT-2026-09.md` (Q24 à Q27).

### 6. Cycle de vie de l'indexation

**Ce qui est promis.** Promotion atomique et fail-closed des index ; indexation incrémentale
seulement sous preuve explicite de capacité ; reprise d'une indexation interrompue au lieu
d'une réindexation complète, avec douze conditions de réutilisation et une durée de vie unique.

**Pourquoi priorité 1.** C'est le cœur fonctionnel, le plus gros volume de code
(`IndexingRunExecutor` fait à lui seul 43 ko), et l'ADR-0039 est récent — donc peu de recul.

**À vérifier en particulier.** L'ordre de prise des verrous **L1 < L3 < L2 < L4 < M**, écrit à
quatre endroits : vérifier qu'il l'est encore partout ; qu'une lecture ne mute pas (constat
R10, ouvert : l'ouverture du magasin migre les runs de format historique sans bail) ; que le
verrou de rétention n'est pas le seul sans délai ni couche JVM (R9, ouvert) ; que les cgroups
marqués par un build antérieur sont documentés comme non récupérables (R11).

**Déjà suivi.** `FIAB-SUIVI.md` (R4 à R12), `AUDIT-2026-09.md`, ADR-0039.

---

## Priorité 2

| Capacité | Ce qui motive la priorité 2 | Point d'attention principal | Déjà suivi |
|----------|------------------------------|------------------------------|------------|
| 7. Persistance des snapshots | Chantier en cours : ADR-0055 et 0056 acceptés mais **non implémentés**, 0047 encore *Proposed*. Auditer l'état actuel reste utile, mais une partie des constats sera déjà dans le plan. | Plafond de 256 MiB imposé pendant l'I/O et non seulement en contrôle de taille ; repli V2 sur surrogates isolés ; reconstruction en mémoire des indexes du snapshot actif. | `roadmap/storage-hexagonal-2026-10/`, ADR-0046, 0047 |
| 8. Racine de composition et frontières | Quatre ADR récents (0042, 0044, 0045, 0057) et un suivi de 228 ko. Le gros du travail est fait ; l'audit cherche les résidus. | Direction `domain -> engine -> runtime/storage -> adapters -> application -> surfaces` vérifiée **par l'index**, pas par les `pom.xml` ; aucun module créé pour reproduire un package ; constructeur unique et point d'entrée nommé tenus. | `ARCHI-SUIVI.md` (228 ko), ADR-0022, 0042, 0044, 0045, 0057 |
| 9. Runtime d'indexation et distribution | ADR-0037 est « Accepted — **parity pending** » : l'écart natif/Docker est connu et assumé. L'audit doit le qualifier, pas le découvrir. | Ce que le backend natif ne fait pas et que Docker fait, et réciproquement ; indexeurs réellement embarqués dans le paquet (0040) ; mise à jour Docker MCP non interactive et fail-fast. | ADR-0037, 0040, `docker-release-validation.yml` |
| 10. Program graph et intelligence d'architecture | ADR-0036 est *Proposed* : le cadre de ce qui peut être affirmé n'est pas clos. Forte exposition (API, MCP). | Séparation stricte des faits et de leur interprétation ; bornes de toutes les analyses avancées ; aucune capacité présentée comme acquise sans mesure. | ADR-0013, 0028, 0036 |
| 11. Providers SCIP polyglottes | Volume élevé, et un scope JaCoCo (`m24-polyglot-provider-platform`) rouge hors régression sur poste Windows — donc une zone où le signal de qualité est déjà brouillé. | Preuves de capability, identité, provenance et plateforme exigées avant d'accepter un provider ; limites du provider Java AST explicites. | ADR-0030, 0032, `check-jacoco.py` |
| 12. Client IntelliJ | Hors du reactor Maven, qualifié par son propre workflow, donc souvent oublié d'un audit du cœur. | Aucune dépendance d'implémentation `com.minos:*` ; négociation de version du protocole CLI JSON dans les deux sens ; Java 21 et Gradle, pas la toolchain du cœur. | ADR-0027, `intellij-plugin.yml` |

---

## Priorité 3

| Capacité | Ce qui motive la priorité 3 |
|----------|------------------------------|
| 13. Modèle de connaissance du code | `minos-domain` est petit, stable, et l'audit de septembre n'y a trouvé que 7 classes de test — la couverture est le vrai sujet, plus que la correction. |
| 14. Découverte et négociation des indexeurs | Cadré par SPI et capacités explicites depuis l'ADR-0026, sans incident connu. |
| 15. Requêtes bornées | Bornes établies depuis l'ADR-0011, exposées mais simples. |
| 16. Couche sémantique optionnelle | Optionnelle et reconstruisible par conception : une défaillance dégrade sans casser. ANN reste derrière une décision mesurée. |
| 17. Tests liés et analyse d'impact | `RelatedTestDerivationService` fait 25 ko et mérite un regard, mais la capacité est explicitement une estimation : elle ne promet pas l'exhaustivité. |
| 18. Observations runtime partielles | Faible volume, garanties volontairement modestes, corrélation à un snapshot exact déjà cadrée par l'ADR-0034. |

---

## Ne pas redécouvrir ce qui est déjà suivi

`docs/audit/` contient environ 1,5 Mo de suivi d'audit. Avant d'ouvrir un constat, vérifier
qu'il n'est pas déjà tracé — un résidu connu **se référence**, il ne se renumérote pas.

| Document | Taille | Ce qu'il couvre |
|----------|-------:|-----------------|
| `AUDIT-2026-09.md` | 92 ko | L'audit maître de septembre : constats A*, C*, G*, Q*, R*, S*, T* et leur verdict |
| `ARCHI-SUIVI.md` | 228 ko | Chantier architecture : frontières de modules, composition root, un package un module |
| `S23-SUIVI.md` | 173 ko | Sprint 23 |
| `SPRINT-2-SUIVI.md` | 181 ko | Sprint 2 |
| `FIAB-SUIVI.md` | 161 ko | Axe fiabilité : verrous, reprise, rétention (R4 à R12) |
| `CODE-SUIVI.md` | 101 ko | Qualité de code |
| `SEC-SUIVI.md` | 76 ko | Axe sécurité |
| `CI-HYGIENE-SUIVI.md` | 39 ko | Hygiène CI (C1, C2, G3) |
| `RESIDUS-SPRINT-1-SUIVI.md` | 33 ko | Résidus du sprint 1 |
| `SPRINT-1-SUIVI.md` | 31 ko | Sprint 1 |
| `CLI-SUIVI.md` | 27 ko | Surface CLI |
| `Q25-Q26-SUIVI.md` | 27 ko | Constats Q25 et Q26 |
| `S15-REGRESSION-RUNTIME-GRANT.md` | 8 ko | Régression de grant runtime |

**Constats connus encore ouverts au 1er octobre 2026**, à reprendre par référence et non à
rouvrir : `Q25` (scripts d'historique traitant tout code non nul comme un échec), `Q26`
(`listWorkspaces` et `findWorkspace` strictes), `Q27` (message brut du JDK recopiant un
fragment de fichier lu), `R9` (verrou de rétention sans délai ni couche JVM), `R10` (une
lecture peut muter par migration de format historique), `R11` (cgroups d'un build antérieur
non récupérés), `R12` (une commande de lecture ne tourne pas sur un `MINOS_HOME` en lecture
seule), `T1` à `T5` (seuils et répartition de couverture), `C2` (gates exécutés plusieurs
fois), `S1` à `S3` et `Q1` (non couverts par les tests).

Cette liste vient de `AUDIT-2026-09.md` tel qu'il était lu le 1er octobre 2026 : la phase 0 de
l'audit doit la reconstruire depuis les documents courants, qui ont pu bouger depuis.

---

## Ce que cette carte ne couvre pas

Volontairement hors périmètre des capacités ci-dessus, parce que ce sont des moyens et non des
promesses produit — à auditer séparément si tu le souhaites :

- la CI elle-même et les gates (`scripts/`, `.github/workflows/`), déjà traités par
  `CI-HYGIENE-SUIVI.md` ;
- la chaîne de release et la supply-chain (épinglage, provenance, OSV, Plugin Verifier) ;
- les benchmarks de `benchmarks/` et la gouvernance par mesures de l'ADR-0025 ;
- la documentation en tant que telle, hors divergence avec le code ;
- la couverture de tests comme objectif global, qui est un axe transverse (`T1` à `T5`) plutôt
  qu'une capacité.
