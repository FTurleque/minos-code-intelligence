# Décision de clôture M6 — Intelligence d’architecture

Date : **23 juillet 2026**

Statut : **M6 TERMINÉ ET VALIDÉ LOCALEMENT — CONSOLIDATION DOCUMENTAIRE EN COURS**

Suivi : issue #13.

## Verdict

Le périmètre métier de M6 est terminé et validé localement.

MINOS expose désormais une intelligence d’architecture fournisseur-neutre qui :

- reconstruit la topologie des modules et namespaces ;
- agrège uniquement les dépendances inter-modules explicitement persistées ;
- mesure leur concentration sans transformer une mesure en interprétation ;
- classe séparément les centralités entrante et sortante sans seuil absolu ;
- expose les technologies réellement qualifiées par la découverte ;
- compose ces signaux dans une vue cohérente associée à un unique projet/snapshot ;
- fournit un contexte architectural compact ciblé sur un module.

Aucune nouvelle sémantique architecturale n’est inventée à la clôture.

## Porte de sortie

| Critère roadmap M6 | Résultat |
|---|---|
| Topologie des modules | satisfaite |
| Topologie des packages/namespaces | satisfaite |
| Composants centraux | satisfaits comme classement relatif directionnel |
| Concentration des dépendances | satisfaite avec métriques HHI et parts maximales |
| Technologies détectées | satisfaites pour les faits qualifiés `JAVA`, `TYPESCRIPT`, `MAVEN`, `NPM` |
| `get_architecture_overview` métier | satisfait par `ProjectArchitectureQuery.getArchitectureOverview(...)` et la vue composée |
| `get_module_context` métier | satisfait par `ProjectArchitectureQuery.getModuleContext(...)` |
| Distinction faits / inférences | satisfaite via `InformationNature` et les preuves structurées |
| Cohérence projet/snapshot | satisfaite par la composition M6.7 |
| Replay réel multi-module | satisfait sur la fixture TypeScript versionnée |

## Livraisons M6

| Incrément | PR | Head validé | Merge | Tests |
|---|---:|---|---|---:|
| M6.1 — topologie modules/namespaces | #14 | `2198b3c7ee35b20fc2a4872c2312502d7e33185b` | `b509734738f9943f3eb57b3876ea1aad487adf44` | 144/144 |
| M6.2 — dépendances inter-modules | #15 | `634694242b6a4322594d687175f05c9956e74c0d` | `db5c4ed8e8106c38b56289b3908767458dcf4056` | 148/148 |
| M6.3 — concentration | #16 | `0f8edf19fbf6234f8025cc8f36f1b8f738252914` | `398e2b9a97ba2434ad8e644c71292714461cb34f` | 152/152 |
| M6.4 — calibration centralité | #17 | `b7864aa9f739dbf652fca54be2580115c40f6cf7` | `612e0907850219376cdc5bd6c6d5401831e96450` | 155/155 |
| M6.5 — classement composants centraux | #18 | `e14ec523412c95da5ce790c62eff5c7968589606` | `d6f280204cf283f3af3d98b30def358c0722acda` | 158/158 |
| M6.6 — technologies factuelles | #19 | `3053030ba827210e1894885ae246269037952fcc` | `a991d4ef1b350310d23021a1f96104f9d88f7cff` | 161/161 |
| M6.7 — vue composée + module context | #20 | `ba744f41b974432fe33eb617a866ef4c8dcb0ead` | `f10449681a9010079cc9fe0400aac867dea497d9` | 162/162 |

## Validation locale finale du code M6

La dernière porte fonctionnelle a été exécutée le **23 juillet 2026** sur le head exact M6.7 :

```text
.\mvnw.cmd clean verify
116 sources main compilées en release 24
58 sources test compilées en release 24
162 tests exécutés
0 failure
0 error
0 skipped
BUILD SUCCESS
```

Le warning `sun.misc.Unsafe` émis par `protobuf-java 4.34.2` sous Java 24 reste non bloquant et identique aux validations précédentes.

## Replay réel TypeScript

La fixture :

```text
fixtures/typescript/typescript-modules
```

relit son index SCIP versionné et confirme cumulativement les acquisitions M6 :

```text
modules = 3
DEPENDS_ON = 4
inter = 4
module edges = 1

packages/api
  incomingDependencyCount = 4
  incomingRank = 1
  technologies = [TYPESCRIPT]

packages/app
  outgoingDependencyCount = 4
  outgoingRank = 1
  technologies = [TYPESCRIPT]

module racine
  incomingRank = 0
  outgoingRank = 0
  technologies = [NPM]
```

La vue M6.7 confirme également que toutes les sous-vues appartiennent au même `projectId` et au même `snapshotId`.

## Principes consolidés

### Faits versus dérivations

- modules et technologies observées : `FACTUAL` lorsqu’ils proviennent directement de la découverte qualifiée ;
- namespaces, graphe inter-modules, concentration, centralité, vue composée et contexte de module : `DERIVED` ;
- aucune règle M6 ne transforme un rang ou une concentration en rôle métier absolu.

### Centralité

M6 ne définit pas de seuil universel `central / non central`.

La calibration a montré que les distributions entrantes et sortantes portent des informations différentes. MINOS conserve donc deux rangs relatifs denses indépendants :

```text
incomingRank
outgoingRank
```

Un module sans signal obtient le rang `0`.

### Technologies

M6 expose uniquement les technologies déjà qualifiées factuellement par M1 :

```text
LANGUAGE     JAVA
LANGUAGE     TYPESCRIPT
BUILD_SYSTEM MAVEN
BUILD_SYSTEM NPM
```

Frameworks, runtimes, versions, bibliothèques, bases de données et outils annexes ne sont pas inventés par convention.

## Limites assumées

- les dépendances inter-modules reposent sur les `DEPENDS_ON` persistés disponibles ;
- les métriques de concentration sont descriptives, pas des verdicts architecturaux ;
- les rangs de centralité sont relatifs au graphe observé ;
- la détection technologique reste limitée aux faits qualifiés par `ProjectDiscovery` ;
- `getModuleContext` résout par ID, chemin relatif ou nom unique et refuse l’ambiguïté ;
- la CLI `minos architecture`, le serveur MCP et l’API restent des couches d’exposition de jalons ultérieurs ;
- GitHub Actions reste hors de la porte locale courante, conformément au suivi #5.

## Consolidation documentaire

La branche de clôture M6 ne modifie pas le code métier. Elle aligne :

- `README.md` ;
- `docs/ROADMAP.md` ;
- `docs/STATUS.md` ;
- les statuts documentaires M6 ;
- la présente décision.

Cette branche doit néanmoins passer `clean verify` sur son SHA exact avant fusion.

## Suite

Après fusion de la consolidation documentaire et clôture de l’issue #13, le prochain jalon de la roadmap est :

**M7 — Indexation incrémentale**.
