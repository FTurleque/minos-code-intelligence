# M3 — Dérivation des dépendances directes

Statut : **validé localement**
Date : **23 juillet 2026**

## Règle

`DependencyDerivationService` produit une relation `DEPENDS_ON` directe depuis
un fait relationnel explicite qui matérialise une dépendance de code : import,
référence, relation de type, implémentation, appel, lecture/écriture,
instanciation, injection, paramètre ou retour.

Les liens de navigation `DEFINITION` et les relations déjà dérivées ne sont pas
réinjectés. Les auto-dépendances sont ignorées.

## Explicabilité

Une seule dépendance est produite par paire source/cible. Plusieurs faits entre
la même paire sont coalescés et deviennent plusieurs preuves
`DERIVATION_PATH`, dans un ordre stable. La relation produite porte :

- `InformationNature.DERIVED` ;
- une confiance `1.0`, car la règle est déterministe à partir de faits directs ;
- une origine `minos / RELATIONSHIP_DERIVATION / M3` ;
- `OriginType.DERIVED_BY_MINOS` ;
- la résolution et la cible résolue ou non résolue du fait source.

Les relations factuelles d'origine sont conservées à côté de cette vue. Une
requête peut donc filtrer ou expliquer les deux niveaux sans perte.

## Mesure réelle

| Dataset TypeScript | Faits SCIP | Dépendances dérivées |
|---|---:|---:|
| `typescript-simple` | 3 | 2 |
| `typescript-inheritance` | 14 | 11 |
| `typescript-modules` | 6 | 4 |
| `typescript-unresolved` | 3 | 2 |
| **Total** | **26** | **19** |

Les 19 dépendances correspondent aux 19 paires source/cible décrites par les
19 messages fournisseur. Les messages qui portent à la fois `REFERENCES` et
`IMPLEMENTS` restent deux faits mais une seule dépendance avec deux preuves.
