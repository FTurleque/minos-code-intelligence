# État courant — MINOS

Dernière mise à jour : **22 juillet 2026**

Ce document est le tableau de bord opérationnel de MINOS. La feuille de route
conserve la séquence des jalons, les issues GitHub portent les checklists de
travail et les rapports de jalon conservent les preuves détaillées.

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET FUSIONNÉ
M1 — Découverte et orchestration     EN COURS
  M1.1 — découverte locale           VALIDÉ ET FUSIONNÉ
  M1.2 — ignore + registre           EN COURS
M2 à M13 — Jalons produit           NON DÉMARRÉS
```

M0 est livré avec le verdict **ADOPTER_AVEC_CONTRAINTES**. La PR #4 a été
fusionnée dans `main` au commit `6d8376bcfc16dd5ba1c6b691535aa3d8e57cc49a`
après validation locale manuelle verte sur son head final.

M1 est suivi dans l'issue #6.

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

Preuve finale M0 :

```text
commit validé  2e0b3f19e160d0621898641d0d9cad71bbccb86f
MINOS tests    27 réussis, 0 échec, 0 erreur
java-24-smoke  BUILD SUCCESS
runner         Manual CI: SUCCESS
merge main     6d8376bcfc16dd5ba1c6b691535aa3d8e57cc49a
```

GitHub Actions reste volontairement hors de la porte courante ; l'anomalie
historique est suivie séparément dans #5.

## M1.1 — découverte locale factuelle

La PR #7 a été validée localement sur le head
`be6ac6872cb289022db671f28094ecb996c8fe71` :

```text
37 sources main
15 sources test
30 tests réussis
0 échec
0 erreur
BUILD SUCCESS
```

Elle a été fusionnée dans `main` au commit
`fb1ee4b648f5ebee6b9fcac7369ce7574f449877`.

Acquis M1.1 :

- contrat immuable `ProjectDiscovery` ;
- aucune identité métier dérivée du seul chemin ;
- Java détecté uniquement avec de vrais fichiers `.java` ;
- TypeScript détecté uniquement avec de vrais `.ts` / `.tsx` ;
- Maven via `pom.xml` ;
- npm via `package-lock.json` ;
- `package.json` comme marqueur de module Node sans présumer le gestionnaire ;
- modules et racines source/test relatifs ;
- résultats déterministes ;
- première frontière fournisseur sur `discovery` / `orchestration`.

Documentation : `docs/m1/PROJECT_DISCOVERY.md`.

## M1.2 — porte active

Branche :

```text
m1/ignore-policy-project-registry
```

### Implémenté

- `ProjectIgnorePolicy` ;
- exclusions techniques non ré-includables ;
- lecture du `.gitignore` racine ;
- lecture du `.minosignore` racine ;
- glob `*`, `**`, `?`, classes simples, ancrage, règles répertoire et négation ;
- `.minosignore` peut resserrer mais pas contourner `.gitignore` ;
- intégration de la politique aux modules, builds, fichiers preuve et racines source/test ;
- `RegisteredProject` ;
- `RegisteredWorkspace` ;
- `LocalProjectRegistry` file-backed ;
- UUID projet/workspace attribués puis persistés, jamais dérivés du chemin ;
- enregistrement d'une même racine rendu idempotent par chemin canonique ;
- affectation workspace stockée dans le projet et liste workspace dérivée ;
- écritures via fichier temporaire + `ATOMIC_MOVE` lorsque disponible ;
- test de frontière fournisseur étendu au package `registry` ;
- ADR-0007 proposé.

Documentation : `docs/m1/IGNORE_AND_REGISTRY.md`.

### Limites explicites M1.2

- `.gitignore` imbriqués non interprétés ;
- aucune réconciliation automatique d'un projet déplacé ;
- aucun verrouillage multi-processus du registre ;
- aucune base de données ;
- aucun indexeur n'est encore sélectionné ou lancé.

### Validation requise

```powershell
.\mvnw.cmd clean verify
```

L'incrément doit rester en Draft tant que cette commande n'est pas verte sur son
head courant.

## Reste du périmètre M1

```text
M1.3 IndexerRegistry + négociation de capacités
M1.4 cycle de vie de l'indexation + état de l'index
validation finale M1
```

Les systèmes de build supplémentaires (Gradle, pnpm, yarn, etc.) ne sont pas
présumés supportés : ils seront ajoutés lorsqu'un incrément les qualifiera.

## Blocages et décisions

| Sujet | Effet |
|---|---|
| GitHub Actions sans steps ni logs | Issue #5 en pause ; aucun blocage de la validation locale |
| `scip lint` / `snapshot` sur plages typées | Limitation SCIP CLI 0.7.1 documentée |
| Kinds et appels incomplets selon les fournisseurs | Capacités à déclarer explicitement, jamais à inventer |
| `qualifiedName` non canonique dans tous les cas | Accepté pour M1 ; requalification ciblée en M2 |
| Identité projet | UUID persistant du registre ; le chemin n'est qu'une localisation/clé de rapprochement |
| Ignore imbriqué | Limite M1.2 documentée, à lever seulement si les mesures le justifient |

## Prochaines portes

```text
M0 fusionné — ADOPTER_AVEC_CONTRAINTES
        ↓
M1.1 découverte locale — validée et fusionnée
        ↓
M1.2 ignore policy + registre local — validation en cours
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
- découverte M1.1 : `docs/m1/PROJECT_DISCOVERY.md` ;
- ignore et registre M1.2 : `docs/m1/IGNORE_AND_REGISTRY.md` ;
- décision d'identité proposée : ADR-0007.

Ce tableau de bord doit être mis à jour après chaque résultat expérimental ou
décision de porte, sans recopier les mesures détaillées des rapports.
