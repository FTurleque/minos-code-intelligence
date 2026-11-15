# Design

## Contexte

`RelatedTestDerivationService` (`minos-engine`, package `com.minos.query`) rapproche tests et code de production par
convention de nom. Motivation : voir `proposal.md` (Why). Une méthode privée change ; aucune surface publique.

## État vérifié au HEAD

HEAD `89be3fb4`. Lecture du code, aucun test exécuté.

| Fait | Preuve |
|---|---|
| `normalizedName` : `null` → `""`, sinon `toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "")` | `minos-engine/src/main/java/com/minos/query/RelatedTestDerivationService.java:451-456` |
| Appel par symbole de production, clé `ScopedNormalizedName` | `RelatedTestDerivationService.java:157-164` |
| Deux candidats par symbole de test — son nom, et le nom de son fichier, suffixes de test retirés ; le nom vide est retiré des candidats | `RelatedTestDerivationService.java:169-171` |
| Tests existants de la classe | `minos-engine/src/test/java/com/minos/query/RelatedTestDerivationServiceTest.java`, `RelatedTestHeuristicsTest.java` |
| Aucun gate ne cite la classe ni la méthode | recherche de `RelatedTestDerivationService` et `normalizedName` dans `scripts/` : aucune occurrence pertinente |

Non mesuré : le temps passé dans cette méthode sur un index réel.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit propose « une boucle sur les caractères **ou** un `Pattern` statique » comme équivalents. Une boucle qui filtre les caractères ASCII **avant** la mise en minuscules ne l'est pas : `toLowerCase(Locale.ROOT)` rend `k` pour U+212A et `i` suivi d'un diacritique pour U+0130, que le filtre actuel conserve | `findings.json`, AUD-PERF-11, action | `RelatedTestDerivationService.java:455` | Fixer la règle par un test de caractérisation avant toute réécriture (D1) |
| La règle de normalisation n'a ni exigence ni test dédié | `openspec/specs/tests-lies-et-impact/spec.md` | code | Exigence ajoutée par ce changement |

## Exigences couvertes

- tests-lies-et-impact › Les noms comparés par la dérivation des tests liés sont normalisés par une règle stable — D1.

## Objectifs et non-objectifs

Non applicable : périmètre d'une méthode, voir la proposition.

## Responsabilités et frontières des composants

Non applicable : un seul module, aucune dépendance changée (ARCH-0068 sans objet).

## Modèle de domaine et invariants

Invariant : pour toute chaîne `s`, la nouvelle normalisation rend la même valeur que
`s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "")`.

## Traitements et interactions

Non applicable : aucun déroulé modifié.

## États et transitions

Non applicable.

## Contrats d'interface

Non applicable : méthode privée ; sorties CLI, API et MCP inchangées.

## Persistance et intégrité

Non applicable : les relations dérivées sont identiques, donc les snapshots aussi.

## Transactions, concurrence et idempotence

Non applicable : un `Pattern` compilé est immuable et partageable entre fils.

## Sécurité et isolation

Non applicable : aucune E/S, aucun processus. `Locale.ROOT` conservé (règle de sécurité transversale).

## Erreurs et reprise

Non applicable.

## Observabilité

Non applicable.

## Performance et mesure

Aucun seuil. Stratégie : le test de caractérisation prouve l'identité ; le gain n'est affirmé par aucun chiffre.
Mesure facultative : durée de la dérivation sur l'index de ce dépôt, avant et après, si un index réel est disponible.

## Compatibilité, migration et retour arrière

Aucune migration. Retour arrière : commit précédent.

## Diagrammes

Non applicable : aucune structure ni interaction nouvelle à illustrer.

## Décisions

### D1 — `Pattern` statique, précédé d'un test de caractérisation

[Proposition] Remplacer `replaceAll` par un `Pattern` compilé une fois (`private static final`), appliqué après
`toLowerCase(Locale.ROOT)`. Alternative : boucle sur les caractères **après** mise en minuscules — admise si le test
de caractérisation passe ; une boucle avant mise en minuscules est écartée (écart ci-dessus). La méthode est
privée : le test passe par l'API publique de dérivation et fixe les quatre scénarios de l'exigence (nom mixte avec
séparateur, U+212A, langue `tr-TR`, nom et nom de fichier sans lettre ni chiffre ASCII), verts au HEAD.

## Qualification des capacités

`tests-lies-et-impact` : qualification inchangée. L'exigence ajoutée est **qualifiée** par test unitaire sous Windows
et Linux.

## Risques et compromis

- [Risque] Réécriture non équivalente → test de caractérisation écrit et vert **avant** la correction, puis rejoué.

## Vérification

- `RelatedTestDerivationServiceTest` (scénarios ajoutés), `RelatedTestHeuristicsTest`.
- `./mvnw -B -ntp -pl minos-engine -am test "-Dtest=RelatedTest*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` ;
  goldens : `./mvnw -B -ntp -pl minos-app -am test` vert, sans golden régénéré (la suite compare les sorties aux
  goldens de `minos-app/src/test/resources/characterization/`).
- `python .claude/scripts/run_gates.py --fast`.

## Documentation durable à mettre à jour

`docs/architecture/diagrams/capacites/tests-lies-et-impact.md` (traçabilité de l'exigence ajoutée).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| tests-lies-et-impact › Les noms comparés par la dérivation des tests liés sont normalisés par une règle stable | Nom mixte | D1 | 1.1 | `RelatedTestDerivationServiceTest` — à compléter |
| idem | Caractère non ASCII dont la minuscule est une lettre ASCII | D1, Écarts | 1.1 | à écrire |
| idem | Langue de l'hôte turque | D1 | 1.1 | à écrire |
| idem | Nom sans lettre ni chiffre ASCII | D1 | 1.1 | à écrire |
| idem | Sorties inchangées | Vérification | 1.3 | tests de caractérisation de `minos-app` — existants |

## Questions ouvertes

Aucune.
