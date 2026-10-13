# Proposal

## Why

**AUD-PERF-10** (faible, sprint 11 de l'audit du 10 octobre 2026, connu sous MINOS-AUD-F13). La recherche de symboles par texte calcule le rang de correspondance de chaque candidat **dans le filtre**, puis le **recalcule à chaque comparaison du tri** (`minos-engine/src/main/java/com/minos/store/InMemoryCodeKnowledgeStore.java:127` et `:129`). Un calcul de rang (`:322-347`) fait jusqu'à **7** conversions en minuscules (`:394-406`) : trois sur le nom, trois sur le nom qualifié, une sur la clé de symbole. Dès que les correspondances exactes ne remplissent pas la limite demandée, les candidats sont **tous les symboles du projet** (`:286`).

Pour une requête courte sur un projet de quelques dizaines de milliers de symboles, cela fait de l'ordre de n + n·log n calculs de rang et autant de chaînes temporaires par appel de `find-symbols` ou de `minos_find_symbols`. **Non mesuré** : aucune durée ni aucune allocation n'a été relevée.

## What Changes

- **Un rang par candidat** : le rang est calculé une fois, porté avec le symbole (paire rang/symbole), et réutilisé par le filtre et par le tri. Dans un calcul de rang, chaque champ est mis en minuscules au plus une fois.
- **Ordre garanti inchangé** : un test de caractérisation fige l'ordre et le contenu des résultats avant la modification et les compare après.
- **Mesure** du gain et du coût mémoire d'un précalcul des formes minuscules dans la vue, avant toute décision de l'adopter.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `persistance-snapshots` : deux exigences ajoutées sur la vue de requête en mémoire (le rang de correspondance est calculé une fois par candidat ; l'optimisation ne change ni le contenu ni l'ordre des résultats).

## Hors périmètre

- **Supprimer le rang 6** (correspondance sur la clé de symbole, `:344-346`) : c'est un changement de comportement observable de `find-symbols` et de `minos_find_symbols`, donc une décision de surface publique (D3 du design), pas une optimisation.
- **Précalculer les formes minuscules dans `SymbolIndexes`** : option mesurée ici (D2), adoptée seulement sur décision ; elle alourdit la vue et dépend du changement actif `ramener-le-poids-des-vues-a-la-mesure`.
- Les index d'arêtes d'impact (AUD-PERF-11, changement actif `indexer-les-aretes-d-impact-avec-la-vue`, qui touche la même classe et passe avant).
- La recherche de code (`minos_search_code`) au-delà de ce qu'elle délègue à `findSymbols`.

## Impact

- **Modules du reactor** : `minos-engine` (`store/InMemoryCodeKnowledgeStore`). Aucune dépendance ajoutée (ADR 0022).
- **Surfaces publiques** : aucune sortie ne change (CLI `find-symbols`, API Java, MCP `minos_find_symbols`) ; c'est l'objet de la seconde exigence. IntelliJ et NEXUS : sans effet.
- **ADR** : aucun.
- **Gates à rejouer** : scope JaCoCo `persistence-cache-indexes` (`scripts/quality/check-jacoco.py:19` nomme la classe) ; `python scripts/ci/test_plugin_gate.py` (`:38` cite le chemin littéral du fichier, qui ne bouge pas) ; `python scripts/remediation/check-mne.py` (`:86`, si D2 est retenue) ; `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : Windows et Linux, sans différence de comportement.
