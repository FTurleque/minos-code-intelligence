# Design

## Contexte

`minos-intellij` est un client externe du protocole CLI (Gradle 9.9.0, Java 21, hors du reactor Maven, aucune
dépendance `com.minos:*`). Ses tests sont du JUnit simple, sans fixture de plateforme. Motivation : voir
`proposal.md` (Why).

## État vérifié au HEAD

HEAD `89be3fb4`. Lecture du code ; aucun build Gradle exécuté.

| Fait | Preuve |
|---|---|
| 29 fichiers de production, 24 fichiers de test | décompte de `minos-intellij/src/main` et `src/test` |
| Huit classes jamais nommées dans `src/test` | recherche par mot entier : 0 occurrence pour chacune |
| Panneau : collecte récursive des emplacements, test « est un emplacement » | `minos-intellij/src/main/java/com/minos/intellij/ui/MinosToolWindowPanel.java:293-318` |
| Panneau : texte de connexion, résumés d'architecture et de module, lecture de champs avec repli | `MinosToolWindowPanel.java:333-386` |
| La collecte écrit dans un `DefaultListModel` (type Swing) | `MinosToolWindowPanel.java:293` |
| Actions avancées : le champ requis délègue déjà à `MinosJsonFields.required`, testé (champ absent, vide, objet nul) | `minos-intellij/src/main/java/com/minos/intellij/actions/MinosAdvancedAnalysisActions.java:219-221` ; `minos-intellij/src/test/java/com/minos/intellij/protocol/MinosJsonFieldsTest.java:30-36` |
| Réglages : la chaîne saisie est analysée en entier ; hors bornes ou non numérique, c'est la valeur de **repli** qui est rendue, pas la borne | `minos-intellij/src/main/java/com/minos/intellij/settings/MinosSettingsConfigurable.java:111-122` |
| Les mêmes bornes existent déjà pour un entier, avec le même repli | `minos-intellij/src/main/java/com/minos/intellij/settings/MinosSettingsRules.java:20-26` |
| Classes déjà extraites et gardées | `minos-intellij/src/test/java/com/minos/intellij/PlatformFreeLogicTest.java:20-24` (`MinosSymbolRanking`, `MinosJsonFields`, `MinosRegistryNoticeText`, `MinosSettingsRules`) |
| Planchers et règle « mesure Linux moins 2 points, arrondie au centième inférieur » | `minos-intellij/build.gradle.kts:140-155` |
| Mesure du 2026-10-15 : lignes 637/1776, branches 336/940 | `minos-intellij/build.gradle.kts:140` |
| PIT du plugin : 661 mutants sans couverture, surtout dans les classes d'interface et d'action | `docs/developer/quality/code-audit-couverture.md` (§ 5.2, lecture des exécutions Ubuntu) |

Non mesuré : la couverture actuelle du plugin (elle a pu bouger depuis le 15/10) et celle d'après extraction.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit propose des tests de plateforme légers (`BasePlatformTestCase`) ; une exigence en vigueur dit que les tests du plugin ne doivent pas exiger de fixture de plateforme | `findings.json`, AUD-TST-08, action | `openspec/specs/client-intellij/spec.md:274-275` | D1 : extraction seule ; la fixture reste une décision du propriétaire |
| « Sans aucune référence dans les tests » est présenté comme un défaut ; cinq de ces classes sont déclarées non couvertes par construction, conformément à la même exigence | `findings.json`, AUD-TST-08, titre | `docs/user/intellij-plugin.md:335` | Le constat est confirmé en fait, partiel en défaut : seule la logique extractible est due |
| Le critère de sortie du sprint (« les classes UI du plugin ont un test ») n'est pas atteignable sans fixture pour `MinosToolWindowFactory`, `MinosUiController`, `MinosNavigation`, `MinosRegistryNotice` | `sprints.md`, sprint 6 | exigence citée | Critère tenu pour la logique du panneau et de l'écran de réglages ; les autres restent nommées (D2) |
| L'audit compte `MinosAdvancedAnalysisActions` parmi la logique à extraire ; son champ requis est déjà délégué et testé, le reste est du câblage d'actions | `findings.json`, AUD-TST-08, action | `MinosAdvancedAnalysisActions.java:219-221` | Aucune extraction ; la classe reste nommée dans la documentation |
| `MinosRegistryNotice` est comptée « sans test » alors que son texte est testé | constat | `minos-intellij/src/test/java/com/minos/intellij/protocol/MinosRegistryNoticeTextTest.java` | Rien à faire : 24 lignes d'appel à la plateforme |

## Exigences couvertes

- client-intellij › La présentation des réponses du CLI par le plugin est testée hors des classes d'interface — D2.
- client-intellij › Les planchers de couverture du plugin suivent sa mesure — D3.

## Objectifs et non-objectifs

Objectif : rendre testable ce qui ne dépend pas de l'IDE. Non-objectif : tester Swing ou la plateforme.

## Responsabilités et frontières des composants

| Classe extraite (proposée) | Package | Reçoit de |
|---|---|---|
| présentation des résultats (texte de connexion, résumés, lecture de champs avec repli, collecte d'emplacements rendue en liste Java) | `com.minos.intellij.ui` | `MinosToolWindowPanel` |
| analyse de la chaîne saisie d'un réglage entier (espaces retirés, repli si non numérique), puis bornes existantes | `com.minos.intellij.settings`, dans `MinosSettingsRules` qui porte déjà les bornes | `MinosSettingsConfigurable` |

`MinosAdvancedAnalysisActions` ne reçoit aucune extraction : son seul calcul sans interface est déjà dans
`MinosJsonFields`.

Les classes d'interface gardent le câblage Swing et délèguent. Aucun cycle de packages nouveau : les classes
extraites sont des feuilles (elles ne nomment ni `ui` Swing ni `actions`). Gson reste la seule dépendance.

## Modèle de domaine et invariants

Invariant : pour une même réponse JSON, texte et liste produits sont identiques avant et après extraction (tests de
caractérisation écrits sur le comportement relu au HEAD, pas sur une intention).

## Traitements et interactions

Non applicable : délégation simple.

## États et transitions

Non applicable.

## Contrats d'interface

Protocole CLI inchangé. Aucune action ni réglage ajouté à `plugin.xml`.

## Persistance et intégrité

Non applicable.

## Transactions, concurrence et idempotence

Non applicable : fonctions pures ; le fil d'appel (EDT ou tâche de fond) reste celui d'aujourd'hui.

## Sécurité et isolation

Aucun processus lancé, aucune E/S ajoutée. Les textes affichés restent ceux du CLI : pas de chemin absolu ajouté par
le plugin. La garde « aucun `com.minos:*` » est rejouée.

## Erreurs et reprise

Champ absent : texte de repli ou `MinosProtocolException` existants, relus au HEAD et fixés par test.

## Observabilité

Non applicable.

## Performance et mesure

Non applicable.

## Compatibilité, migration et retour arrière

Refactor interne ; compatibilité IntelliJ Platform 2026.1 inchangée. Retour arrière : commit précédent, planchers
compris.

## Diagrammes

Non applicable : le tableau des responsabilités suffit ; la vue de séquence du dossier `client-intellij.md` nomme
déjà `MinosToolWindowPanel` et sera complétée en tâche.

## Décisions

### D1 — Pas de fixture de plateforme

[Proposition — décision du propriétaire attendue]

- **A (recommandée)** : extraction et JUnit simple. Conforme à l'exigence en vigueur, sans dépendance de test
  nouvelle (`testFramework` de la plateforme), sans allongement du job `IntelliJ plugin (gate)`.
- **B (action de l'audit)** : ajouter `BasePlatformTestCase`. Exige de modifier l'exigence « La logique du plugin est
  testable sans la plateforme IntelliJ », une dépendance de test verrouillée et vérifiée (lockfile, métadonnées de
  vérification), et un temps de CI supplémentaire. À ouvrir comme changement distinct si elle est retenue.

### D2 — Ce qui est extrait, ce qui reste nommé

[Proposition] Extraire la logique des deux classes porteuses (tableau ci-dessus). Restent sans test unitaire et
nommées dans `docs/user/intellij-plugin.md` : `MinosToolWindowFactory`, `MinosUiController`, `MinosNavigation`,
`MinosRegistryNotice`, `MinosAdvancedAnalysisActions`, le câblage Swing du panneau et de l'écran de réglages, et la
partie PSI de `MinosProjectService`.

### D3 — Planchers relevés dans le même changement

[Proposition] Après extraction et tests, mesurer sous Linux (job de la PR ou WSL), puis écrire mesure, date et
système dans `build.gradle.kts` et relever les quatre planchers selon la règle existante. Le paquet `protocol` est
relevé si sa mesure a bougé.

## Qualification des capacités

`client-intellij` : exigences ajoutées **qualifiées** sous Linux (job exigé) ; sous Windows, le rapport est publié
sans plancher jugé (limite déjà déclarée, `build.gradle.kts:134-135`). Couverture de l'interface Swing : **non
supportée** par des tests automatiques, déclarée.

## Risques et compromis

- [Risque] Caractérisation écrite d'après l'intention → chaque test part d'une réponse JSON du protocole CLI et
  du texte relu au HEAD, jamais d'un texte « attendu » réécrit.
- [Risque] Mutation locale du plugin lancée « pour vérifier » → interdite sur le poste (un mutant y a déjà réduit
  l'ACL du profil utilisateur) ; mutation par `code-audit.yml` seulement.
- [Risque] Classes instrumentées obsolètes qui faussent JaCoCo → `./gradlew clean` du plugin avant la mesure.

## Vérification

- Tests nouveaux de présentation, `PlatformFreeLogicTest` étendu.
- `./gradlew --no-daemon test jacocoTestReport jacocoTestCoverageVerification` dans `minos-intellij`, par le job
  `IntelliJ plugin (gate)` ou sous WSL.
- Non vérifiable localement sous Windows : le verdict des planchers (jugé sous Linux).

## Documentation durable à mettre à jour

`docs/user/intellij-plugin.md` (l. 335 : classes extraites, classes non couvertes, planchers et mesure),
`docs/architecture/diagrams/capacites/client-intellij.md`.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| client-intellij › La présentation des réponses du CLI par le plugin est testée hors des classes d'interface | Emplacements collectés dans une réponse imbriquée | D2 | 1.1, 1.2 | `MinosResultPresentationTest#locationsOfANestedResponseAreCollectedInDocumentOrderWithTheirPath`, `MinosResultPresentationTest#aMalformedLocationIsSkippedWithoutHidingTheOthers` |
| idem | Champ absent | D2 | 1.1, 1.2 | `MinosResultPresentationTest#theConnectionLineNamesTheProjectItsIndexStateAndProvider`, `MinosResultPresentationTest#theArchitectureSummaryShowsItsCountsAndFallsBackOnMissingFields`, `MinosResultPresentationTest#theModuleSummaryShowsItsFieldsAndFallsBackOnMissingOnes` |
| idem | Champ requis absent d'une action avancée | État vérifié | — | `MinosJsonFieldsTest` — existant |
| idem | Réglage hors bornes ou non numérique | D2 | 1.3 | `MinosSettingsRulesTest#aTypedTimeoutIsReadTrimmedAndFallsBackWhenOutOfBoundsOrNotANumber`, `#aTypedGraphSizeIsReadTrimmedAndFallsBackWhenOutOfBoundsOrNotANumber` |
| idem | Réglage saisi avec des espaces | D2 | 1.3 | `MinosSettingsRulesTest` (valeurs `"  45 "`, `" 200"`) |
| idem | Dépendance à l'interface réintroduite | D2 | 1.2 | `PlatformFreeLogicTest` (liste étendue, refus de `javax.swing`) |
| client-intellij › Les planchers de couverture du plugin suivent sa mesure | Tests de présentation supprimés | D3 | 2.1 | calcul sur copie du rapport Linux du run 44107036156 : 0,363 < 0,37 et 0,370 < 0,38 (tâche 2.1) ; non rejoué par Gradle |
| idem | Dépôt réel après le changement | D3 | 2.1 | job `IntelliJ plugin (gate)` de la PR #774 — à relire après relèvement |
| idem | Mesure non consignée | D3 | 2.1 | relecture de `build.gradle.kts` |

## Questions ouvertes

- D1 : option A ou B (recommandation : A).
