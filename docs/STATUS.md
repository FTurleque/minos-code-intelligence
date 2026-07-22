# État courant — MINOS

Dernière mise à jour : **22 juillet 2026**

Ce document est le tableau de bord opérationnel de MINOS. La feuille de route
conserve la séquence des jalons, les issues GitHub portent les checklists de
travail et les rapports de jalon conservent les preuves détaillées.

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET FUSIONNÉ
PR #4 — Livraison M0                FUSIONNÉE
M1 — Découverte et orchestration     EN COURS
M2 à M13 — Jalons produit           NON DÉMARRÉS
```

M0 est livré avec le verdict **ADOPTER_AVEC_CONTRAINTES**. La PR #4 a été
fusionnée dans `main` au commit `6d8376bcfc16dd5ba1c6b691535aa3d8e57cc49a`
après validation locale manuelle verte sur son head final.

M1 est suivi dans l'issue #6 et développé sur la branche
`m1/project-discovery-orchestration`.

## Résultats acquis de M0

- Java 24.0.1, Maven Wrapper 3.3.4 et Maven 3.9.16 validés localement ;
- 35 sources principales et 14 sources de test compilées en `release 24` ;
- 27 tests JUnit réussis sur le head final M0 ;
- `scip-java 0.13.1` qualifié sur fixtures et dépôt Java réel ;
- `scip-typescript 0.4.0` qualifié sur les fixtures TypeScript ;
- huit index réels ingérés par la baseline SCIP vers MINOS ;
- backend mémoire mesuré et déterministe ;
- Glean 0.2.0.1 qualifié sous WSL2 mais non retenu pour le chemin MVP par défaut ;
- frontière fournisseur vérifiée dans le cœur MINOS ;
- promotion atomique des index décidée ;
- backend MINOS léger retenu par défaut.

Preuve finale de livraison M0 :

```text
commit validé  2e0b3f19e160d0621898641d0d9cad71bbccb86f
MINOS tests    27 réussis, 0 échec, 0 erreur
java-24-smoke  BUILD SUCCESS
runner         Manual CI: SUCCESS
merge main     6d8376bcfc16dd5ba1c6b691535aa3d8e57cc49a
```

GitHub Actions reste volontairement hors de la porte courante ; l'anomalie
historique est suivie séparément dans #5.

## M1 — porte active

Objectif : détecter un projet local et sélectionner ensuite les fournisseurs
d'indexation adaptés sans coupler les contrats MINOS aux fournisseurs.

Premier incrément en cours : **baseline de découverte factuelle**.

### Implémenté sur la branche M1

- contrat immuable `ProjectDiscovery` ;
- absence volontaire d'identifiant métier dérivé du seul chemin local ;
- détection Java via les racines Maven conventionnelles ;
- détection TypeScript via la présence réelle de fichiers `.ts` / `.tsx` ;
- détection Maven via `pom.xml` ;
- détection npm via `package.json` ;
- découverte des modules par marqueurs de build ;
- racines source/test relatives au projet ;
- tri déterministe des résultats ;
- exclusion technique initiale de `.git`, `.idea`, `.minos-m0`, `node_modules`,
  `target`, `dist` et `out` ;
- tests sur les fixtures multi-modules Java et TypeScript ;
- extension du test d'architecture aux packages `discovery` et `orchestration`.

Documentation : `docs/m1/PROJECT_DISCOVERY.md`.

### Validation requise pour ce premier incrément

```powershell
.\mvnw.cmd clean verify
```

La PR M1 doit rester en Draft tant que cette commande n'est pas verte sur son
head courant.

## Reste du périmètre M1

```text
registre local des projets
concept de workspace
.gitignore / .minosignore
IndexerRegistry
négociation des capacités
cycle de vie de l'indexation
état de l'index
```

Les systèmes de build supplémentaires (Gradle, pnpm, yarn, etc.) ne sont pas
présumés supportés : ils seront ajoutés lorsqu'un incrément M1 les qualifiera.

## Blocages et décisions

| Sujet | Effet |
|---|---|
| GitHub Actions sans steps ni logs | Issue #5 en pause ; aucun blocage de la validation locale |
| `scip lint` / `snapshot` sur plages typées | Limitation SCIP CLI 0.7.1 documentée |
| Kinds et appels incomplets selon les fournisseurs | Capacités à déclarer explicitement, jamais à inventer |
| `qualifiedName` non canonique dans tous les cas | Accepté pour M1 ; requalification ciblée en M2 |
| Identité projet | Le chemin local seul ne constitue pas l'identité métier |

## Prochaines portes

```text
M0 fusionné — ADOPTER_AVEC_CONTRAINTES
        ↓
M1.1 découverte locale factuelle + tests verts
        ↓
M1.2 ignore policy et registre local
        ↓
M1.3 IndexerRegistry + négociation de capacités
        ↓
M1.4 cycle de vie / état d'index + validation M1
```

## Sources de vérité

- feuille de route : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- décision M0 : `docs/m0/DECISION_M0.md` ;
- preuves M0 : `docs/m0/` ;
- suivi M0 clôturé : issue #3 ;
- infrastructure CI : issue #5 ;
- suivi M1 : issue #6 ;
- baseline découverte M1 : `docs/m1/PROJECT_DISCOVERY.md`.

Ce tableau de bord doit être mis à jour après chaque résultat expérimental ou
décision de porte, sans recopier les mesures détaillées des rapports.
