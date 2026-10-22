# Proposal

## Why

**AUD-PERF-10** (faible, sprint 11 de l'audit du 10 octobre 2026, connu sous MINOS-AUD-F13 ; `docs/project/audits/2026-10-10/findings.md:514`). La recherche de symboles par texte de la vue en mémoire calcule le rang de correspondance de chaque candidat **dans le filtre**, puis le **recalcule pour les deux opérandes de chaque comparaison du tri** (`minos-engine/src/main/java/com/minos/store/InMemoryCodeKnowledgeStore.java:185` et `:187`). Un calcul de rang (`:380-406`) fait jusqu'à **7** conversions en minuscules (`:455-465`) : trois sur le nom, trois sur le nom qualifié, une sur la clé de symbole. Dès que les correspondances exactes ne remplissent pas la limite demandée (`:340`), les candidats sont **tous les symboles du projet** (`:344`).

Pour une requête courte sur un projet de quelques dizaines de milliers de symboles, cela fait de l'ordre de n + 2·n·log₂ n calculs de rang et jusqu'à sept fois plus de chaînes temporaires par appel de `find-symbol`, de `search-code`, de `minos_find_symbols` ou de `minos_search_code`. **Non mesuré** : aucune durée ni aucune allocation n'a été relevée.

## What Changes

- **Un rang par candidat** : le rang est calculé une fois, porté avec le symbole (paire rang/symbole), et réutilisé par le filtre et par le tri. Une recherche sans texte ne calcule aucun rang. Dans un calcul de rang, chaque champ est mis en minuscules au plus une fois.
- **Ordre garanti inchangé** : un test de caractérisation fige l'ordre et le contenu des résultats au HEAD et les compare après la modification ; les goldens de `minos-app` restent inchangés.
- **Mesure** du gain, et du coût mémoire d'un précalcul des formes minuscules dans la vue, avant toute décision de l'adopter.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `persistance-snapshots` : deux exigences ajoutées sur la vue de requête en mémoire (le rang de correspondance est calculé au plus une fois par candidat ; l'optimisation ne change ni le contenu ni l'ordre des résultats).

## Hors périmètre

- **Supprimer le rang 6** (correspondance sur la clé de symbole, `:402-404`) : changement de comportement observable de `find-symbol`, `search-code`, `minos_find_symbols` et `minos_search_code`, donc une décision de surface publique (D3 du design), pas une optimisation.
- **Précalculer les formes minuscules dans `SymbolIndexes`** (`:609-655`) : option mesurée ici (D2), adoptée seulement sur décision et dans un changement propre ; elle alourdit la vue et déplace la mesure de `QueryViewWeightTest`.
- Changer le classement, le départage des ex æquo ou la limite des résultats ; indexer la recherche par sous-chaîne.
- La recherche de code (`CodeSearchService`) au-delà de ce qu'elle délègue à `findSymbols`.
- Le script historique `scripts/m15/run-final.ps1`, qui lit un chemin absent de la classe (`:88`) : il n'est ni réparé ni aggravé ici.

## Impact

- **Modules du reactor** : `minos-engine` seul (`com.minos.store.InMemoryCodeKnowledgeStore` et un test dans `minos-engine/src/test/java/com/minos/store/`). Aucune dépendance ajoutée, aucun package nouveau (ADR 0022, ADR 0044, règle A14 de `scripts/architecture/check-module-boundaries.py`).
- **Surfaces publiques** : aucune sortie ne change — CLI `find-symbol` et `search-code`, API Java (`MinosApi.findSymbols`), MCP `minos_find_symbols` et `minos_search_code`, plugin IntelliJ (qui appelle `find-symbol`, `minos-intellij/src/main/java/com/minos/intellij/service/MinosProjectService.java:51`). NEXUS : sans effet. Aucun type public nouveau (règle A11 inchangée).
- **ADR** : aucun nouveau, aucun amendé.
- **Gates à rejouer** : scope JaCoCo `persistence-cache-indexes` (`scripts/quality/check-jacoco.py:54-60`, qui nomme la classe) ; `python scripts/ci/test_plugin_gate.py` (`:49` cite le chemin littéral du fichier, qui ne bouge pas) ; `python scripts/architecture/check-module-boundaries.py` ; `python scripts/remediation/check-mne.py` (`:85-88`, seulement si D2 est retenue plus tard) ; `python .claude/scripts/run_gates.py --fast` ; SpotBugs de `code-audit.yml` contre `quality/spotbugs-baseline/minos-engine.xml` (aucune alerte nouvelle).
- **Plateformes** : Windows et Linux, sans différence de comportement.
