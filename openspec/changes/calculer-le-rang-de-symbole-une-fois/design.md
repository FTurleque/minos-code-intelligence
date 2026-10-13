# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-PERF-10**. HEAD analysé : `949e1569` (`develop`). `STORE` désigne `minos-engine/src/main/java/com/minos/store/InMemoryCodeKnowledgeStore.java`.

## État vérifié au HEAD

### AUD-PERF-10 — confirmé à la lecture, **non mesuré**

- `STORE:112-138` `findSymbols` : `:127` filtre `matchRank(symbol, normalizedQuery) < Integer.MAX_VALUE` ; `:128-135` tri dont la première clé (`:129`) rappelle `matchRank` à chaque comparaison.
- `STORE:322-347` `matchRank` : rangs 0 (nom qualifié égal), 1 (nom égal), 2 et 3 (préfixes), 4 et 5 (contient), **6** (la clé de symbole contient la requête, `:344-346`), puis `Integer.MAX_VALUE`.
- `STORE:394-406` : `equalsIgnoreCase`, `startsWithIgnoreCase` et `containsIgnoreCase` appellent chacun `toLowerCase(Locale.ROOT)` sur la valeur. Un calcul de rang qui va jusqu'au bout fait 7 conversions.
- `STORE:286` : sans assez de correspondances exactes (`:282`), les candidats sont tous les symboles du projet.
- `STORE:450` : `SymbolIndexes` est un record **privé** de la classe.
- Tests existants de la classe : `minos-storage-local/src/test/java/com/minos/storage/local/store/InMemoryCodeKnowledgeStoreIndexTest.java` (dans un autre module que la classe testée).

**Non mesuré** : durée et allocations d'un appel ; aucun build, test ni gate exécuté pendant cette analyse. **Non vérifié** : si un test ou un golden encode le rang 6 ou l'ordre exact des ex æquo (tâche 1.1).

### Écarts avec l'action proposée par l'audit

1. **« Supprimer le rang 6 » n'est pas une optimisation.** Un symbole trouvé seulement par sa clé disparaîtrait des résultats de `find-symbols` et de `minos_find_symbols` : sortie publique changée, donc décision (D3), séparée de ce changement.
2. **Le précalcul des minuscules a un coût mémoire, et il interagit avec un changement actif.** `ramener-le-poids-des-vues-a-la-mesure` exige que le poids estimé d'une vue reste supérieur ou égal au tas qu'elle retient, et `scripts/remediation/check-mne.py:86` fige `QUERY_VIEW_PERSISTED_AMPLIFICATION`. Retenir deux ou trois chaînes de plus par symbole déplace cette mesure. Le rang calculé une fois par candidat, lui, ne retient rien.
3. **Le décompte « 2 × n·log n » de l'audit** suppose deux appels par comparaison ; à la lecture, le comparateur extrait la clé pour chaque opérande. L'ordre de grandeur tient ; le chiffre exact n'est pas repris.
4. **Recouvrements non cités.** `scripts/quality/check-jacoco.py:19` (scope `persistence-cache-indexes`) ; `scripts/ci/test_plugin_gate.py:38` (chemin littéral du fichier) ; le changement actif `indexer-les-aretes-d-impact-avec-la-vue` modifie la même classe.

## Goals / Non-Goals

**Goals** : au plus un calcul de rang par candidat ; aucune conversion répétée d'un même champ dans un calcul ; résultats identiques, ordre compris.

**Non-Goals** : changer le classement ; alourdir la vue sans mesure ; indexer la recherche par sous-chaîne.

## Decisions

### D1. Paire rang/symbole, calculée après les filtres bon marché

Les filtres de projet, de nature, de module et de nom qualifié (`:122-126`) restent en tête. Le rang est ensuite calculé une fois par candidat restant, porté dans une paire locale, filtré (`< Integer.MAX_VALUE`) puis trié par ce rang et par les clés suivantes inchangées (`:130-135`). Dans le calcul, le nom, le nom qualifié et la clé sont mis en minuscules au plus une fois chacun, et la clé seulement si aucun rang plus fort n'a été trouvé. Aucun objet n'est retenu après l'appel.

### D2. Précalcul des minuscules dans la vue — **décision en attente du propriétaire**

Recommandation : **ne pas l'adopter dans ce changement** ; le mesurer (tâche 3.1 : durée d'une requête courte avant D1, après D1, et avec précalcul ; tas retenu de la vue avec et sans). Il ne se justifie que si D1 laisse un coût notable, et il doit passer **après** `ramener-le-poids-des-vues-a-la-mesure`, dont le test de poids doit rester vert.

Alternative : précalculer dès maintenant dans `SymbolIndexes` (action de l'audit). Gain supérieur par requête, tas résident plus grand, gate `check-mne.py` et facteur d'amplification à revoir dans la même tâche.

### D3. Rang 6 (clé de symbole) — **décision en attente du propriétaire**, hors de ce changement

Recommandation : le garder tant qu'un relevé n'a pas montré qui s'en sert, et trancher dans un changement propre, additif ou versionné. Alternative : le retirer (MINOS-AUD-F13), avec note de version et goldens mis à jour. Ce changement garantit seulement que le rang 6 se comporte **comme avant**.

### D4. Rendre le nombre de calculs observable

`matchRank` est privé et statique : rien ne le compte aujourd'hui. Le calcul passe par une fonction de rang que la classe reçoit par un constructeur de visibilité package ; le test, placé dans `minos-engine` dans le même package (`com.minos.store`), fournit une fonction qui compte et délègue. Aucun type public nouveau.

## Direction des dépendances

Changement interne à `minos-engine` ; aucune arête nouvelle (ADR 0022). Le test neuf vit dans le module de la classe (ADR 0044 : un package, un module).

## Qualification des capacités

- Recherche de symboles par texte : qualifiée, **inchangée** en contenu et en ordre.
- Gain de performance : **non qualifié** tant que la tâche 3.1 ne l'a pas mesuré.

## Windows et Linux

Aucune différence : `Locale.ROOT` est déjà utilisé pour toutes les conversions (`:394-406`), et le reste.

## Risks / Trade-offs

- Le tri d'une liste de paires doit rester **stable** et garder exactement les clés secondaires actuelles : c'est ce que fige le test de caractérisation, ex æquo compris.
- Conflit de fusion probable avec `indexer-les-aretes-d-impact-avec-la-vue` (même classe) : ce changement passe après lui.
- Le code modifié compte comme nouveau code pour SonarCloud (80 % dans `minos-engine`) : le test de D4 vit dans ce module pour cette raison, les tests existants étant dans `minos-storage-local`.
- Si la mesure montre un gain négligeable, le dire dans le suivi : D1 reste une simplification sans coût.
