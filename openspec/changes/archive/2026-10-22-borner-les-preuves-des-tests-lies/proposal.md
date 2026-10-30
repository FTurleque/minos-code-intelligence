# Proposal

## Why

**AUD-PERF-09** (faible, confiance « plausible », sprint 9 de l'audit du 10 octobre 2026). La dérivation des tests liés ajoute **une preuve par emplacement** de référence d'un fichier de test vers un symbole de production (`minos-engine/src/main/java/com/minos/query/RelatedTestDerivationService.java:218-225`). Le dédoublonnage ne porte que sur le triplet type, description, emplacement (`:615-631`) : un test qui référence cent fois une classe produit cent preuves `DIRECT_REFERENCE` dans la même relation `RELATED_TEST`. Le codec les persiste toutes (`minos-storage-local/src/main/java/com/minos/storage/local/store/SnapshotBinaryCodecSupport.java:559-565`), sous un plafond de lecture d'un million par relation (`:80`).

Chaque preuve porte une description, deux références et un emplacement : la taille des relations `RELATED_TEST` croît donc avec le nombre de références des tests, et pèse sur la taille du snapshot, sur son plafond et sur le tas relu.

L'ampleur **n'a pas été mesurée**. Ce changement commence par la mesure et **s'arrête** si elle est négligeable.

## What Changes

- **Mesure d'abord** : part des preuves `RELATED_TEST` dans le snapshot de ce dépôt, et distribution du nombre de preuves par relation.
- **Seuil d'abandon** : sous un seuil décidé par le propriétaire, rien n'est modifié et le constat est requalifié.
- **Au-delà** : au plus N emplacements conservés par type de preuve et par relation, dans un ordre déterministe, avec le nombre d'emplacements omis écrit dans une preuve de synthèse. Aucun changement de format de snapshot.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `tests-lies-et-impact` : deux exigences ajoutées (les preuves d'une relation de test lié sont bornées et le nombre omis est déclaré ; sous le plafond, les sorties sont inchangées à l'octet).

## Hors périmètre

- Le plafond de lecture du codec (`MAX_EVIDENCE`) et le format de snapshot (V3, ADR 0046) : inchangés.
- Le score et la sélection des tests liés : le plafond ne doit pas les modifier (preuve en tâche).
- Les preuves des autres relations et les autres postes de taille du snapshot (AUD-PERF-03, ADR 0047).
- La réécriture des snapshots existants : le plafond ne s'applique qu'aux indexations suivantes.

## Impact

- **Modules du reactor** : `minos-engine` (`query/RelatedTestDerivationService`) ; `minos-provider-scip` l'appelle à l'ingestion, sans changement ; `minos-bootstrap` (banc `SnapshotScalabilityBenchmark`) pour la mesure.
- **Surfaces publiques** : les preuves sortent en public (CLI, API Java, MCP ; `DIRECT_REFERENCE` figure dans trois goldens de `minos-app/src/test/resources/characterization/`). Sous le plafond, aucune sortie ne change. Au-delà, la liste de preuves d'une relation est **raccourcie** et gagne une preuve de synthèse : c'est un changement de sortie, donc une **décision en attente** (`design.md`, D2). IntelliJ et NEXUS lisent ces sorties : à vérifier.
- **ADR** : aucun.
- **Gates à rejouer** : aucun `check-*.py` ni scope de `scripts/quality/check-jacoco.py` ne cite `RelatedTestDerivationService` au HEAD (recherche dans `scripts/`) ; restent les goldens de `minos-app`, `python .claude/scripts/run_gates.py --fast` et `python scripts/docs/check-current-docs.py`.
- **Plateformes** : Windows et Linux, sans différence ; l'ordre de sélection ne dépend pas de l'hôte.
