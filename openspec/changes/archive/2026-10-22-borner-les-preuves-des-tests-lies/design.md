# Design

## Context

Sprint 9 de l'audit du 10 octobre 2026 : **AUD-PERF-09**. HEAD analysé : `949e1569` (`develop`). `RTD` = `minos-engine/src/main/java/com/minos/query/RelatedTestDerivationService.java` ; `CODEC` = `minos-storage-local/src/main/java/com/minos/storage/local/store/SnapshotBinaryCodecSupport.java`.

## État vérifié au HEAD

### AUD-PERF-09 — confirmé en structure, ampleur **non mesurée**

- `RTD:218-225` : pour chaque occurrence résolue d'un fichier de test vers un symbole de production, `candidate(...).add(EvidenceType.DIRECT_REFERENCE, ...)` avec l'emplacement de l'occurrence ; les occurrences sont parcourues dans un ordre trié (fichier, ligne, colonne, identifiant, `:205-208`).
- `RTD:615-631` : `Candidate.add` dédoublonne sur type, description et `fichier:ligne:colonne` ; le poids du signal est le **maximum** par type (`:629`), il ne dépend donc pas du nombre de preuves.
- `CODEC:559-565` : toutes les preuves d'une relation sont triées puis écrites ; `CODEC:80` : `MAX_EVIDENCE = 1_000_000` à la lecture.
- La dérivation est appelée à l'ingestion (`minos-provider-scip/src/main/java/com/minos/adapter/scip/ingestion/ScipIngestionAdapter.java:48-49`).
- Sorties publiques : `DIRECT_REFERENCE` apparaît dans `minos-app/src/test/resources/characterization/cli-json.golden`, `minos-app/src/test/resources/characterization/mcp.golden` et `minos-app/src/test/resources/characterization/mcp-all-tools.golden`.
- Banc disponible : `benchmarks/scalability/README.md` et `SnapshotScalabilityBenchmark` (`minos-bootstrap`).

**Non mesuré** : la part des preuves `RELATED_TEST` dans le snapshot de ce dépôt, le nombre de preuves par relation, l'effet sur le tas. **Non vérifié** : aucun build ni test exécuté ; que la confiance d'une relation ne dépende d'aucune façon du nombre de preuves (seul le poids par type a été relu) ; ce que le plugin IntelliJ et NEXUS font de la liste de preuves.

### Écarts avec l'action proposée par l'audit

1. **Le constat est « plausible » et non chiffré.** Modifier une sortie publique pour un gain inconnu n'est pas justifié : la mesure vient d'abord, avec un seuil d'abandon (D1).
2. **Les preuves sont une sortie publique.** Un plafond raccourcit la liste de preuves d'une relation au-delà de N : ce n'est pas additif pour ces relations. L'exigence « Les sorties d'impact restent additives et reproductibles » de `tests-lies-et-impact` interdit de retirer une valeur existante des sorties d'impact ; si les preuves de tests liés y figurent, D2 est une décision de surface.
3. **« Un compteur dans la description » a deux lectures.** Modifier la description d'une preuve conservée change sa clé de dédoublonnage (`RTD:625-626`) et le texte de toutes les preuves ; une preuve de synthèse distincte, sans emplacement, laisse les preuves conservées identiques. Aucune des deux ne change le format du snapshot.
4. **L'ordre doit être celui de la persistance.** « Les N premiers » n'a de sens que dans un ordre total et stable : celui du parcours (`:205-208`), indépendant de l'hôte.
5. **Les snapshots existants ne sont pas réécrits** : le gain n'apparaît qu'après réindexation ; un snapshot antérieur reste lisible tel quel.

### Mesure du 2026-10-21 (tâches 1.1, 1.2, 3.4)

Corpus : `benchmarks/scalability/prepare-corpus.ps1` lancé depuis PowerShell 7 sur `1492113e` (`develop`), scip-java 0.13.1 géré, SCIP de 37 919 098 octets (sha256 `c927268f…`), 214 s. Import par `scipArtifactImporter` dans le magasin de capture du banc (`SnapshotScalabilityBenchmark.InMemorySnapshotStore`), tailles par `SnapshotCodecV3.encodedSize`. Programme de mesure hors dépôt.

| Mesure | Avant plafond | Après plafond (N = 5) |
|---|---|---|
| Relations `RELATED_TEST` / total | 10 979 / 22 277 | 10 979 / 22 277 |
| Preuves `RELATED_TEST` | 42 054 (dont `DIRECT_REFERENCE` 26 434) | 37 394 (`DIRECT_REFERENCE` 21 774, synthèses comprises) |
| Preuves par relation : médiane / p95 / max | 3 / 10 / 80 | 3 / 7 / 9 |
| Relations > 5 preuves / > 100 preuves | 1 609 / 0 | — / 0 |
| Taille V3 du snapshot | 306 661 262 octets | 304 817 599 octets (−1 843 663, −0,60 %) |
| Relations `RELATED_TEST` entières / taille | 7,41 % | 6,84 % |
| Preuves au-delà de la première / taille | 4,04 % | 3,46 % |

Requalification : constat **confirmé** (les preuves au-delà de la première pèsent 4,04 % > 2 %), d'**ampleur faible** (aucune relation au-delà de 100 preuves ; gain du plafond 0,6 %). **Constat annexe, hors périmètre** : le snapshot de ce dépôt (306,7 Mo encodés) dépasse la limite de persistance de 256 Mio et est refusé par `FileSymbolSnapshotStore.publish` avant comme après ; il relève d'AUD-PERF-03 / ADR 0047, le plafond ne suffit pas à le rendre persistable.

Tâche 1.2 : la confiance (`confidence(signalWeights)`, produit sur les poids maximaux par type) et la sélection (`candidates.values()`) ne lisent jamais le nombre de preuves ; l'ordre des relations non plus. Les preuves sortent par la CLI (`related-tests`), le MCP (`minos_related_tests`) et l'API Java (`Relationship.evidence()`). Les trois goldens portent au total 5, 5 et 4 preuves `DIRECT_REFERENCE` : aucune relation n'y dépasse 5.

### Décisions du propriétaire (2026-10-21)

- **D1** : seuil retenu tel que proposé (abandon si < 2 % **et** aucune relation > 100 preuves). Mesure : 4,04 % → **seuil non atteint, poursuite**.
- **D2** : **option A**, **N = 5** (point de départ confirmé par la distribution : médiane 3, p95 10 ; les goldens restent sous le plafond).

## Goals / Non-Goals

**Goals** : connaître le coût réel ; s'il est notable, borner les preuves sans changer le score, l'ordre, ni les sorties des relations sous le plafond ; déclarer ce qui est omis.

**Non-Goals** : changer le format ou le codec ; changer la sélection des tests liés ; borner les preuves des autres relations.

## Decisions

### D1. Mesure et seuil d'abandon — **décision en attente du propriétaire**

La tâche 1.1 relève, sur le snapshot de ce dépôt : le nombre de relations `RELATED_TEST`, le nombre total de preuves, la distribution par relation (médiane, 95e centile, maximum) et la taille encodée des preuves rapportée à la taille persistée du snapshot.

Seuil proposé : **abandon si les preuves `RELATED_TEST` pèsent moins de 2 % de la taille persistée et si aucune relation ne dépasse 100 preuves.** Dans ce cas rien n'est modifié ; la mesure est consignée et le constat est requalifié « non reproduit à cette échelle ». Le propriétaire fixe le seuil ; une autre valeur est légitime.

### D2. Forme du plafond — **décision en attente du propriétaire**

Recommandation (option A) : par relation et par type de preuve, conserver les **N premiers emplacements** dans l'ordre du parcours ; s'il en reste, ajouter **une** preuve de synthèse du même type, sans emplacement, dont la description donne le nombre d'emplacements omis. Les preuves conservées sont identiques à celles d'aujourd'hui ; une relation sous le plafond est inchangée à l'octet. N est fixé d'après la distribution mesurée (proposition de départ : 5).

Option B : conserver les N premiers et écrire le compteur dans la description de la dernière preuve conservée. Pas d'élément supplémentaire, mais une preuve dont le texte diffère des autres, et un client qui regroupe par description est trompé.

Option C : ne rien retirer et dédoublonner seulement par (type, fichier). Moins de gain, aucune omission à déclarer.

Dans les trois cas : aucun changement de format (la preuve de synthèse est une preuve ordinaire sans emplacement, déjà représentable).

### D3. Le score ne bouge pas

Le poids par type est un maximum (`RTD:629`) : le plafond ne doit changer ni la confiance, ni l'ordre, ni l'ensemble des tests liés. Un test compare, avant et après, les relations dérivées d'un corpus où des relations dépassent le plafond : mêmes paires, mêmes confiances, mêmes rangs.

### D4. La limite est déclarée

L'omission est lisible dans la sortie (preuve de synthèse) ; elle est documentée dans la référence utilisateur des tests liés. Rien n'est présenté comme exhaustif quand il ne l'est plus.

## Direction des dépendances

Changement interne à `minos-engine` (`query`) ; aucune arête nouvelle. La mesure utilise le banc de `minos-bootstrap`, déjà en aval.

## Qualification des capacités

- Tests liés : qualifiés, sélection et score inchangés.
- Liste exhaustive des emplacements de référence d'un test vers un symbole : fournie aujourd'hui ; devient **partielle et déclarée** au-delà de N si D2-A ou B est retenue.

## Windows et Linux

Aucune différence : l'ordre de sélection est celui d'un tri sur des identifiants et des positions, indépendant de l'hôte (les goldens ne doivent pas dépendre de l'outillage).

## Risks / Trade-offs

- Un client qui comptait les preuves comme un nombre de références obtient un nombre borné : la preuve de synthèse porte le reste ; à vérifier pour IntelliJ et NEXUS (tâche 3.3).
- Si les fixtures des goldens contiennent une relation au-dessus de N, les goldens changent : N est choisi au-dessus des fixtures, ou le changement de golden est montré dans la décision.
- Si la mesure est négligeable, ne rien changer est l'issue attendue, et elle doit être dite dans le suivi.
- Le gain ne vaut que pour les snapshots produits après le changement.
