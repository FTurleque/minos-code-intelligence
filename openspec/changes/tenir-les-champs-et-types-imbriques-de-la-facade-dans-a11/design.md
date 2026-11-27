# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement d'outillage : une règle d'un garde Python, son auto-test, sa documentation. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité ; les autres sections du modèle sont non applicables à un garde qui lit du texte.

## État vérifié au HEAD

Relu au commit `a2988f693`, le 2026-11-27.

| Fait | Preuve |
|---|---|
| `public_members` ne rend que les en-têtes suivis de `(` : un champ ou un type à accolade est ignoré | `scripts/architecture/module_boundary_ratchets.py:199` |
| Un `record` imbriqué est écarté explicitement | `module_boundary_ratchets.py:202` |
| La façade a douze champs publics, tous `static final String` | `MinosApplication.java:72-83` |
| Seul type imbriqué public : `Builder` ; `Stores` et `Indexing` sont privés au package, `Queries` et `Semantic` privés | `MinosApplication.java:108-149`, `:461` |
| Un cas de l'auto-test accepte un `record`, un enum et une annotation imbriqués publics | `test_check_module_boundaries_ratchets.py:496-504` |

### Sonde

Arbres temporaires, `check_minos_application_accessors` avec une table vide. Tous les cas sont **acceptés** : champ simple, `static final`, générique, tableau à deux déclarateurs, champ typé `MinosApplicationComposer`, `record` imbriqué, méthode d'une classe imbriquée, méthode implicitement publique d'une interface imbriquée, `extends` d'un type du moteur, champ d'un enum imbriqué, annotation imbriquée.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'exigence « La façade ne rend ni type du moteur ni racine de composition » nomme les méthodes et les constructeurs seulement | spec courante | demande du propriétaire | exigences ajoutées ici, l'énoncé existant restant inchangé |
| Le scénario « Déclarations sans type du moteur » du changement prédécesseur accepte un `record` imbriqué public | `lire-toutes-les-ecritures-de-la-facade-dans-a11` | D2 | exigence en `MODIFIED` ici |
| ARCH-0087 § 2 ne parle que des méthodes | ADR | cette spécification, plus stricte | ligne ajoutée à la tâche manuelle |

## Décisions

### D1 — Un champ est jugé sur son type déclaré [Proposition, recommandation]

- (a) **Recommandé.** Le texte du type (en-tête privé du nom déclaré), arguments génériques compris, est lu comme un retour de méthode ; `MinosApplicationComposer` compte. L'initialiseur n'est pas lu, comme le corps d'une méthode.
- (b) Refuser tout champ public non constant : change la forme de la façade sans lien avec le moteur.

### D2 — Liste fermée des types imbriqués publics [Proposition, recommandation]

- (a) **Recommandé.** `Builder` est le seul admis ; tout autre type imbriqué public échoue, quel que soit son contenu. La lecture ne porte que sur les déclarations de premier niveau, qui écrivent toujours `public` dans une classe : elle est complète et simple. Un nouveau type public de la façade devient une décision relue.
- (b) Lire les membres des types imbriqués publics et refuser ceux qui nomment le moteur : demande de reconnaître les membres implicitement publics (interface, accesseurs et constructeur canonique d'un `record`, constantes d'enum), les supertypes et `permits`, soit un analyseur de Java dans un garde textuel. Une forme oubliée passe en silence.
- (c) Règle sur le bytecode : voit ces membres sans analyse lexicale, mais tourne dans les jobs `Verify` seulement ; comparée dans le design du changement prédécesseur (D1).

Compromis de (a) : un type imbriqué public sans aucun type du moteur est refusé aussi. La façade n'en déclare pas.

### D3 — Modifier l'exigence du prédécesseur plutôt que la réécrire [Proposition, recommandation]

Le bloc entier de « L'écriture d'une déclaration de la façade ne contourne pas A11 » est repris en `MODIFIED`, un seul scénario changé. Il n'entre dans la spec de référence qu'à l'archivage du prédécesseur : l'ordre d'archivage est impératif.

## Vérification

- Rouge : les nouveaux cas de l'auto-test échouent avant la correction.
- Correction : auto-test vert, garde vert sur le dépôt, mutations de `MinosApplication` sur des copies (un champ typé moteur, un `record` imbriqué public) rendant 1.
- `python .claude/scripts/run_gates.py` et `openspec validate --all --strict` verts.

## Documentation durable à mettre à jour

`docs/developer/quality-gates.md` (puce A11 des accesseurs), docstring de `check_minos_application_accessors` (anglais), `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (lignes des exigences, à l'archivage).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception | Tâche | Test ou preuve |
|---|---|---|---|---|
| frontieres-de-modules › La façade n'a aucun champ public typé moteur | Refus, racine de composition, acceptés, HEAD | D1 | 1.1 à 1.3 | à écrire |
| frontieres-de-modules › Le `Builder` est le seul type imbriqué public de la façade | Refus, acceptés | D2 | 1.1 à 1.3 | à écrire |
| frontieres-de-modules › L'écriture d'une déclaration de la façade ne contourne pas A11 | Déclarations sans type du moteur | D3 | 1.1 | à écrire |
