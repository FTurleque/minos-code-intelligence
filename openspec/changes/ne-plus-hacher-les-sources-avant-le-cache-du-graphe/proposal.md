# Proposal

## Why

**AUD-PERF-07** (moyenne, confiance « plausible », sprint 4 de l'audit du 10 octobre 2026). `ProgramGraphService` calcule la clé du fournisseur **avant** de consulter son cache (`:115`, puis `:122`). Quand le snapshot n'a pas d'empreinte exacte de l'arbre source, cette clé est obtenue en hachant **toutes les sources Java** du projet (`FingerprintConstrainedJavaProgramGraphProvider.java:61-63` → `JavaProgramGraphEngine.java:25-31` → `JavaSourceWorkspace.java:97-119`). Le coût est donc payé à chaque appel, **même quand le graphe est en cache**.

Ce cas n'est pas marginal : l'empreinte n'est promue que si l'arbre est resté stable pendant l'indexation (`LocalAutonomousIndexOperations.java:200-208`). Un fichier modifié pendant un `minos index`, situation courante dans un IDE, laisse le snapshot sans empreinte, et chaque appel de `minos_program_graph`, `minos_impact_v2` et `minos_security_paths` relit alors toutes les sources.

Le constat est établi à la lecture du code ; **il n'a pas été mesuré**. Ce changement commence donc par la mesure.

## What Changes

- **Mesure d'abord** : le temps de calcul de la clé est déjà exposé (`cacheStats().providerKeyNanos()`) ; il est relevé avec et sans empreinte exacte, sur un dépôt réel.
- **Un succès de cache ne relit plus les sources** quand le snapshot n'a pas d'empreinte exacte. Deux façons d'y parvenir, à trancher par le propriétaire (voir `design.md`, D1) ; la recommandation est de **déclarer une limitation** plutôt que de deviner la fraîcheur par une heuristique.
- **La garde de fraîcheur est conservée** : un graphe périmé n'est jamais servi comme frais.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `program-graph-et-architecture` : deux exigences ajoutées (un succès de cache ne relit pas les sources ; un snapshot sans empreinte exacte est déclaré, jamais présenté comme frais).

## Hors périmètre

- Changer la règle de promotion de l'empreinte à l'indexation (`LocalAutonomousIndexOperations`), ou réindexer automatiquement : relève de la fraîcheur hors MCP (ADR 0052, « Proposed »).
- Une invalidation par taille et date de modification seule : heuristique explicitement rejetée dans `LocalSourceReader.java:133-135` ; écartée ici pour la même raison (D1).
- Les index d'arêtes d'impact (AUD-PERF-11) et les autres constats du sprint.

## Impact

- **Modules du reactor** : `minos-application` (`program/analysis/ProgramGraphService`, `FingerprintConstrainedJavaProgramGraphProvider`, `JavaProgramGraphEngine`, `JavaSourceWorkspace`) ; `minos-bootstrap` (test de qualification `ProgramGraphPerformanceQualificationTest`).
- **Surfaces publiques** : selon D1. Option recommandée : une **limitation additive** dans les sorties de `minos_program_graph`, `minos_impact_v2` et `minos_security_paths` (MCP, CLI, API Java) ; aucun champ retiré. Le plugin IntelliJ lit ces sorties : à vérifier (champ inconnu toléré).
- **ADR** : aucun nouvel ADR attendu ; si l'option « limitation » est retenue, elle s'inscrit dans le principe capability-honest et dans l'ADR 0052 (note de mise en œuvre).
- **Gates à rejouer** : `python scripts/quality/check-advanced-provider-consistency.py` (`:96` exige `provider.cacheKey(project, snapshot)`), `python scripts/remediation/check-mnd.py` (`:101-104`), `check-mne.py` (`:98-101`), `ProgramGraphPerformanceQualificationTest` (`:76` affirme `providerKeyNanos() > 0`), scope JaCoCo `advanced-impact-security`, goldens de `minos-app`.
- **Plateformes** : Windows et Linux, sans différence attendue.
