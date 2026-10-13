# Design

## Context

Sprint 4 de l'audit du 10 octobre 2026 : **AUD-PERF-07**. HEAD analysé : `2d1cc214` (`develop`). `APP` = `minos-application/src/main/java/com/minos`.

## État vérifié au HEAD

### AUD-PERF-07 — confirmé à la lecture, **non mesuré**

- `APP/program/analysis/ProgramGraphService.java:115` : `providerKey(project, snapshot)` ; `:122` : `cache.get(key)`.
- `FingerprintConstrainedJavaProgramGraphProvider.java:61-63` : sans empreinte exacte, délégation à `delegate.cacheKey`.
- `JavaProgramGraphEngine.java:25-31` puis `JavaSourceWorkspace.java:97-119` : `stateFingerprint` calcule un SHA-256 de chaque source Java.
- `minos-cli/src/main/java/com/minos/cli/operations/LocalAutonomousIndexOperations.java:200-208` : `fingerprintStore.promote` seulement si l'arbre est resté stable pendant le run.
- La statistique `cacheStats().providerKeyNanos()` existe déjà.

**Non mesuré** : la fréquence réelle des snapshots sans empreinte, et le temps de calcul de la clé sur un dépôt réel. La confiance du constat est « plausible ». La tâche 1.1 le mesure avant toute modification ; si le coût est négligeable, le changement se réduit à documenter la mesure.

### Écarts avec l'action proposée par l'audit

1. **La clé calculée avant le cache est une garde, pas une maladresse.** Sans empreinte du snapshot, hacher les sources est le **seul** moyen qu'a le service de savoir que le graphe en cache correspond encore aux fichiers. « Chercher d'abord en cache » sans autre précaution servirait un graphe périmé.
2. **L'invalidation « taille + date de modification » proposée par l'audit est une heuristique déjà rejetée dans ce dépôt** (`LocalSourceReader.java:133-135` : un fichier peut changer sans changer de taille ni, selon le système de fichiers, de date observable). L'adopter ici contredirait une décision écrite.
3. **L'autre option de l'audit (limitation `PROGRAM_GRAPH_FINGERPRINT_UNAVAILABLE` qui désactive l'analyse Java) change une sortie publique** : elle doit être additive, et « désactiver » n'est pas la seule lecture possible (voir D1).
4. **Des gates et un test de qualification encodent le chemin actuel** : `check-advanced-provider-consistency.py:96` exige `provider.cacheKey(project, snapshot)` ; `check-mnd.py:101-104`, `check-mne.py:98-101` ; `ProgramGraphPerformanceQualificationTest.java:76` affirme `providerKeyNanos() > 0`.

## Goals / Non-Goals

**Goals** : connaître le coût réel ; ne plus relire toutes les sources à chaque appel servi par le cache ; ne jamais présenter comme frais un graphe dont la fraîcheur n'est pas prouvée.

**Non-Goals** : réindexer ; changer la promotion de l'empreinte ; introduire une heuristique de fraîcheur.

## Decisions

### D1. Que faire quand le snapshot n'a pas d'empreinte exacte — **décision en attente du propriétaire**

**Option A (recommandée) : servir le graphe du snapshot et le déclarer.** Sans empreinte exacte, la clé de cache est dérivée de l'identité du snapshot (aucune lecture de source) ; le graphe est construit depuis l'état décrit par le snapshot et la réponse porte une limitation additive (par exemple `PROGRAM_GRAPH_SOURCE_STATE_UNVERIFIED`) : « l'arbre source a pu changer depuis l'indexation ; relancer `minos index` ». C'est capability-honest : rien n'est présenté comme frais, rien n'est recalculé à chaque appel. À établir en tâche 1.2 : le moteur Java lit-il les sources courantes pour **construire** le graphe ? Si oui, le graphe construit reflète le disque au moment de la construction, et la limitation le dit.

**Option B : hacher une fois, puis ne rehacher qu'après un délai borné.** La clé coûteuse est mémorisée par (projet, snapshot) pour une durée courte et configurable ; pendant ce délai le cache est servi sans relecture, avec la même limitation. Garde plus de fraîcheur que A, au prix d'un paramètre et d'un comportement dépendant du temps (plus difficile à tester).

**Option C (variante de l'audit) : désactiver l'analyse Java** sans empreinte. Le plus strict ; il retire une capacité dans une situation courante en IDE. Non recommandé.

**Écartée** : taille + date de modification (écart 2).

### D2. La mesure précède la décision

`providerKeyNanos` est relevé sur ce dépôt, avec empreinte exacte puis sans (indexation pendant laquelle un fichier est modifié), sur un succès de cache. Le résultat est écrit dans `design.md` et dans `SUIVI.md` ; il fixe la confiance du constat (« confirmé » ou « non reproduit »).

### D3. La limitation est additive et commune aux trois outils

Si A ou B est retenue : une seule constante de limitation, émise par le service, relayée telle quelle par `minos_program_graph`, `minos_impact_v2` et `minos_security_paths` (MCP, CLI, API Java). Aucun champ existant n'est modifié ; les goldens gagnent la limitation seulement dans les cas sans empreinte.

### D4. Le test de qualification suit

`ProgramGraphPerformanceQualificationTest` affirme aujourd'hui `providerKeyNanos() > 0`. Il est réécrit pour affirmer ce qui compte : avec empreinte exacte, la clé ne lit aucune source ; sans empreinte, un second appel ne relit aucune source et porte la limitation.

## Direction des dépendances

Changement interne à `minos-application` ; le test de qualification reste dans `minos-bootstrap`. Aucune arête nouvelle.

## Qualification des capacités

- Graphe de programme Java avec empreinte exacte : qualifié, inchangé.
- Graphe de programme Java sans empreinte exacte : aujourd'hui servi sans mention ; devient **partiel et déclaré** (option A ou B).

## Windows et Linux

Aucune différence de comportement. La précision des dates de modification diffère entre systèmes de fichiers : une raison de plus d'écarter l'heuristique.

## Risks / Trade-offs

- Option A : un utilisateur qui modifie un fichier sans réindexer obtient un graphe de l'état indexé, avec une limitation qu'il peut ne pas lire. C'est déjà le contrat des autres outils fondés sur le snapshot.
- Une limitation nouvelle peut surprendre un client strict : additive, documentée, et le plugin IntelliJ est vérifié (tâche 3.3).
- Si la mesure montre un coût négligeable, ne rien changer est une issue valable et doit être dite.
