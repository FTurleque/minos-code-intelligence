# ADR-0025 — Gouverner l’évolution du backend par des mesures reproductibles

Statut : **Accepted**

Date : 2026-07-26

Origine : **M16 — Scalabilité et performance à grande échelle**

## Contexte

M15 a stabilisé un backend local fondé sur des snapshots fichiers versionnés et des indexes mémoire reconstruisibles. Cette architecture est simple, locale, déterministe et ne crée pas de dépendance runtime vers un moteur de base de données ou de recherche.

M16 doit déterminer si cette architecture reste acceptable à plus grande échelle. Plusieurs alternatives sont techniquement possibles — SQLite, moteur de recherche spécialisé, clé/valeur embarqué ou architecture mixte — mais les introduire avant d’avoir identifié un goulot mesuré augmenterait immédiatement la surface de maintenance, de migration, de packaging et de diagnostic.

## Décision

MINOS adopte une règle de **promotion de backend conditionnée par des mesures**.

L’ordre de décision est obligatoire :

```text
backend production M15
        ↓
benchmark STANDARD versionné
        ↓
seuil produit en échec ? ── non ──> conserver le backend actuel
        │
       oui
        ↓
identifier le goulot exact
        ↓
prototype alternatif ciblé
        ↓
même dataset + même seed + mêmes opérations
        ↓
gain prouvé sans perte de déterminisme/exactitude ?
        ├── non → conserver/corriger le backend actuel
        └── oui → nouvelle décision ADR avant intégration runtime
```

Le backend de production reste donc :

```text
snapshots fichiers versionnés
        +
SnapshotQueryView bornée
        +
indexes mémoire reconstruisibles
```

tant que la campagne M16 STANDARD respecte les seuils produits.

## Comparateur SQLite M16

M16 fournit un comparateur SQLite via la bibliothèque standard Python pour mesurer certaines clés d’accès structurées sur les mêmes cardinalités synthétiques.

Ce comparateur :

- n’est pas une dépendance Maven ;
- n’est pas empaqueté dans MINOS ;
- ne change aucun format de snapshot ;
- ne constitue pas une décision d’adoption ;
- sert uniquement à quantifier le coût/gain potentiel d’un backend persistant indexé.

Si M16 met en évidence un seuil produit non atteint et qu’une alternative corrige objectivement ce goulot, une ADR ultérieure doit définir la migration et les conséquences avant tout changement runtime.

## Seuil de preuve

La fermeture M16 exige au minimum le profil `STANDARD` versionné dans `scripts/m16/datasets.json` et les mesures définies par `docs/roadmap/M16_EXECUTION.md`.

Une optimisation n’est promue que si :

1. le scénario avant/après est identique ;
2. le dataset et le seed sont identiques ;
3. l’exactitude et le déterminisme restent inchangés ;
4. le gain concerne un seuil produit ou un coût mémoire/disque réellement observé ;
5. le résultat est reproductible sur un SHA exact.

## Conséquences

### Positives

- aucune complexité de stockage n’est introduite par préférence technologique ;
- les décisions M16 sont falsifiables et reproductibles ;
- le format de snapshot reste indépendant du backend de requête ;
- les futurs backends peuvent être comparés avec le même harness ;
- le packaging PROD reste inchangé tant qu’aucune alternative n’est ratifiée.

### Limites

- le profil STANDARD n’est pas une preuve d’échelle infinie ;
- une machine plus grande ou un profil EXTENDED/STRESS peut révéler un goulot absent du gate STANDARD ;
- les benchmarks micro/meso ne remplacent pas les replays providers et les tests fonctionnels ;
- une future migration de backend nécessitera sa propre ADR et une stratégie de compatibilité explicite.

## Références

- `docs/roadmap/M16_EXECUTION.md`
- `scripts/m16/datasets.json`
- `scripts/m16/M16ScaleBenchmark.java`
- `scripts/m16/sqlite-backend-probe.py`
- `scripts/m16/evaluate-backend.py`
- issue #63
