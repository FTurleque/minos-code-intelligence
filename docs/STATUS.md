# État courant — MINOS

Dernière mise à jour : **22 juillet 2026**

Ce document est le tableau de bord opérationnel de MINOS. La feuille de route
décrit les jalons, l'issue GitHub #3 conserve la checklist détaillée et les
rapports `docs/m0/` portent les preuves expérimentales.

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉE TECHNIQUEMENT
PR #4 — Livraison M0                BLOQUÉE PAR LA CI
M1 — Découverte et orchestration     NON DÉMARRÉ
M2 à M13 — Jalons produit           NON DÉMARRÉS
```

Le verdict M0 est **ADOPTER_AVEC_CONTRAINTES**. Les baselines SCIP,
multi-langages et mémoire, la mesure Glean C1 et la comparaison E2 sont
obtenues. Le chemin du MVP reste MINOS léger ; Glean est différé comme backend
avancé optionnel.

La faisabilité technique est clôturée. La porte active est la livraison : la
PR #4 reste Draft tant que la stratégie GitHub Actions suivie dans #5 n'est pas
satisfaite ou remplacée par une dérogation explicite du propriétaire.

Les implémentations expérimentales de `find_symbol` et `find_usages` valident
M0 ; elles ne signifient pas que les jalons produit M2 et M3 sont commencés.

## Avancement M0

| Expérience | État | Résultat confirmé |
|---|---|---|
| A — SCIP Java | Validée avec limitations | Java 24, fixture contrôlée, dépôt Ariane, Maven multi-module et compilation partielle qualifiés |
| B — SCIP vers MINOS | Baseline fonctionnelle | Huit index réels ingérés sans fuite des types fournisseur dans le cœur |
| C — Glean | Validée avec limitations | C1 fonctionne sous WSL2 après conversion de plages ; incompatibilité SCIP moderne et coûts élevés |
| D — TypeScript | Validée avec limitations | Second écosystème qualifié sans modification de `domain`, `store` ou `query` |
| E1 — Backend mémoire | Mesurée | Deux campagnes reproductibles, 48/48 digests identiques et p95 sous les objectifs |
| E2 — Comparaison des backends | Exécutée | Backend MINOS léger retenu par défaut ; Glean reste optionnel |
| Décision M0 | Terminée | `ADOPTER_AVEC_CONTRAINTES` ; M1 autorisé architecturalement mais non démarré |

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
- décision consolidée dans `docs/m0/DECISION_M0.md` ;
- PR #4 maintenue en Draft ; la CI GitHub Actions n'est pas déclarée validée.

## Porte active — livraison M0 et CI

Glean C1 a fourni le résultat décisionnel attendu :

```text
index.scip moderne          ingestion Glean 0.2.0.1 en échec
copie de plages historiques ingestion et requêtes réussies
valeur MVP supplémentaire  non démontrée
décision backend            MINOS léger par défaut, Glean optionnel
```

Le run #212 du head `55df7f1` confirme un échec avant tout step :

```text
steps        null / endpoint direct vide
logs_url     null
job logs     404 BlobNotFound
artifacts    aucun
```

Le build Java n'est pas atteint. La stratégie et les vérifications manuelles
requises sont dans `docs/m0/STRATEGIE_VALIDATION_CI_M0.md`.

## Reste à faire avant la fusion et M1

1. relever dans l'interface GitHub le message du run #212 ;
2. vérifier permissions Actions, politique de pinning et usage/facturation ;
3. obtenir un run avec steps/logs et `verify` vert, ou décider explicitement
   une dérogation documentée ;
4. revoir puis fusionner la PR #4 ;
5. créer la branche M1 seulement après intégration.

## Blocages et décisions

| Sujet | Effet |
|---|---|
| GitHub Actions sans steps ni logs | Bloque la fusion de la PR #4, pas les expériences locales |
| `scip lint` / `snapshot` sur plages typées | Limitation SCIP CLI 0.7.1 documentée, pas un échec du code MINOS |
| Kinds et appels incomplets selon les fournisseurs | Doivent rester des capacités explicites, pas être sur-déclarés |
| `qualifiedName` non canonique dans tous les cas | Accepté pour M0/M1 ; requalification ciblée en M2 |

## Prochaines portes

```text
M0 technique terminé — ADOPTER_AVEC_CONTRAINTES
        ↓
Stratégie CI satisfaite + PR #4 fusionnée
        ↓
Début de M1 : découverte et orchestration
```

## Sources de vérité

- feuille de route : `docs/ROADMAP.md` ;
- protocole M0 : `docs/M0_PLAN_EXPERIMENTATIONS.md` ;
- métriques : `docs/METRIQUES_VALIDATION.md` ;
- rapports et preuves : `docs/m0/` ;
- décision M0 : `docs/m0/DECISION_M0.md` ;
- stratégie CI : `docs/m0/STRATEGIE_VALIDATION_CI_M0.md` ;
- checklist de travail : issue GitHub #3 ;
- infrastructure CI : issue GitHub #5 ;
- livraison M0 : PR GitHub #4.

Ce tableau de bord doit être mis à jour après chaque résultat expérimental ou
décision de porte, sans recopier les mesures détaillées des rapports.
