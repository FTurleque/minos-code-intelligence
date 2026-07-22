# État courant — MINOS

Dernière mise à jour : **22 juillet 2026**

Ce document est le tableau de bord opérationnel de MINOS. La feuille de route
décrit les jalons, l'issue GitHub #3 conserve la checklist détaillée et les
rapports `docs/m0/` portent les preuves expérimentales.

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          EN COURS
M1 — Découverte et orchestration     NON DÉMARRÉ
M2 à M13 — Jalons produit           NON DÉMARRÉS
```

MINOS est dans la clôture de M0. Les baselines SCIP, multi-langages et mémoire,
la mesure Glean C1 et la comparaison E2 sont obtenues. Le chemin du MVP reste
MINOS léger ; Glean est différé comme backend avancé optionnel. La porte active
est maintenant le verdict M0 et la stratégie de validation CI avant le passage
à M1.

Les implémentations expérimentales de `find_symbol` et `find_usages` valident
M0 ; elles ne signifient pas que les jalons produit M2 et M3 sont commencés.

## Avancement M0

| Expérience | État | Résultat confirmé |
|---|---|---|
| A — SCIP Java | Validée avec limitations | Java 24, fixture contrôlée, dépôt Ariane, Maven multi-module et compilation partielle qualifiés |
| B — SCIP vers MINOS | Baseline fonctionnelle | Huit index réels ingérés sans fuite des types fournisseur dans le cœur |
| C — Glean | Validée avec limitations | C1 fonctionne sous WSL2 après conversion de plages ; incompatibilité SCIP moderne et coût élevés |
| D — TypeScript | Validée avec limitations | Second écosystème qualifié sans modification de `domain`, `store` ou `query` |
| E1 — Backend mémoire | Mesurée | Deux campagnes reproductibles, 48/48 digests identiques et p95 sous les objectifs |
| E2 — Comparaison des backends | Exécutée | Backend MINOS léger retenu par défaut ; Glean reste optionnel |
| Décision M0 | En clôture | Verdict final et passage à M1 restent à publier après stratégie CI |

## Résultats acquis

- Java 24.0.1, Maven Wrapper 3.3.4 et Maven 3.9.16 validés localement ;
- 35 sources principales et 14 sources de test compilées en `release 24` ;
- 27 tests JUnit réussis localement ;
- `scip-java 0.13.1` qualifié sur fixtures et dépôt réel ;
- `scip-typescript 0.4.0` qualifié sur quatre index finaux ;
- baseline SCIP vers MINOS exécutée sur quatre index Java et quatre index
  TypeScript ;
- isolation du domaine vis-à-vis de SCIP, Protobuf et Glean vérifiée ;
- backend mémoire E1 mesuré sur les huit index ;
- Glean 0.2.0.1 installé sous WSL2 et C1 exécutée sur `java-simple` ;
- comparaison E2 conclue en faveur du chemin MINOS léger par défaut ;
- PR #4 maintenue en Draft ; la CI GitHub Actions n'est pas déclarée validée.

## Porte active — clôture M0

Glean C1 a fourni le résultat décisionnel attendu :

```text
index.scip moderne          ingestion Glean 0.2.0.1 en échec
copie de plages historiques ingestion et requêtes réussies
valeur MVP supplémentaire  non démontrée
décision backend            MINOS léger par défaut, Glean optionnel
```

Les éléments restant à fermer pour sortir de M0 sont :

1. publier le verdict M0 consolidé et le profil de limitations ;
2. statuer sur la stratégie de validation de la PR #4 malgré l'anomalie
   GitHub Actions suivie dans #5 ;
3. décider explicitement le passage à M1 sans déclarer la CI validée.

## Reste à faire avant la sortie M0

1. consolider le verdict M0 et le profil de capacités ;
2. fermer les lacunes décisives de précision des références et d'identité
   canonique ;
3. satisfaire ou remplacer explicitement la stratégie de validation CI suivie
   dans l'issue #5 ;
4. publier le verdict M0 et décider explicitement du passage à M1.

## Blocages et décisions

| Sujet | Effet |
|---|---|
| GitHub Actions sans steps ni logs | Bloque la fusion de la PR #4, pas les expériences locales |
| `scip lint` / `snapshot` sur plages typées | Limitation SCIP CLI 0.7.1 documentée, pas un échec du code MINOS |
| Kinds et appels incomplets selon les fournisseurs | Doivent rester des capacités explicites, pas être sur-déclarés |

## Prochaines portes

```text
Glean C1 + comparaison E2 terminées
        ↓
Stratégie CI explicite + verdict M0
        ↓
Passage éventuel à M1
```

## Sources de vérité

- feuille de route : `docs/ROADMAP.md` ;
- protocole M0 : `docs/M0_PLAN_EXPERIMENTATIONS.md` ;
- métriques : `docs/METRIQUES_VALIDATION.md` ;
- rapports et preuves : `docs/m0/` ;
- checklist de travail : issue GitHub #3 ;
- infrastructure CI : issue GitHub #5 ;
- livraison M0 : PR GitHub #4.

Ce tableau de bord doit être mis à jour après chaque résultat expérimental ou
décision de porte, sans recopier les mesures détaillées des rapports.
