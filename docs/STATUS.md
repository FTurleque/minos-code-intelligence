# État courant — MINOS

Dernière mise à jour : **24 juillet 2026**

Ce document est le tableau de bord opérationnel compact de MINOS. Les preuves détaillées restent dans les documents de jalon, les décisions et les issues GitHub.

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET LIVRÉ
M1 — Découverte et orchestration    TERMINÉ ET LIVRÉ
M2 — Intelligence des symboles      TERMINÉ ET LIVRÉ
M3 — Intelligence des relations     TERMINÉ ET LIVRÉ
M4 — Recherche et contexte compact  TERMINÉ ET LIVRÉ
M5 — Tests liés et dérivations      TERMINÉ ET LIVRÉ
M6 — Intelligence d’architecture    TERMINÉ, VALIDÉ ET LIVRÉ
M7 — Indexation incrémentale        TERMINÉ, VALIDÉ ET LIVRÉ
M8 — Analyse d’impact               TERMINÉ, VALIDÉ ET LIVRÉ
M9 — CLI stabilisée                 TERMINÉ, VALIDÉ ET LIVRÉ
M10 — Serveur MCP                   TERMINÉ, VALIDÉ ET LIVRÉ
M11 — API publique                  IMPLÉMENTÉ — PORTE FINALE EN ATTENTE
M12 à M13                           NON DÉMARRÉS
```

GitHub Actions reste hors de la porte locale courante ; l’anomalie historique est suivie séparément dans #5.

## Portes acquises

```text
M2   86 tests   BUILD SUCCESS
M3  115 tests   BUILD SUCCESS
M4  131 tests   BUILD SUCCESS
M5  140 tests   BUILD SUCCESS
M6  162 tests   BUILD SUCCESS
M7  196 tests   BUILD SUCCESS
M8  203 tests   BUILD SUCCESS
M9  207 tests   BUILD SUCCESS
M10 210 tests   BUILD SUCCESS
```

## M8 — Analyse d’impact — LIVRÉ

Issue #27 clôturée. PR #28.

```text
head validé   08bbdeab18873a2209f02b58bc8d7e547443ea0f
merge         8147db5c246c7bad92c9b6ab21be81084dc64f59
sources       143 main / 72 test
tests         203/203 PASS
```

Replay :

```text
M8 typescript-modules impact: root=GreetingPort, impacts=2, tests=1, max-depth=2, limitations=[DYNAMIC_DISPATCH_NOT_PROVEN, REFLECTION_NOT_PROVEN, RUNTIME_CONFIGURATION_NOT_PROVEN]
```

Décision : `docs/m8/DECISION_M8.md`.

## M9 — CLI stabilisée — LIVRÉ

Issue #29 clôturée. PR #30.

```text
head validé   ae82f24897ea925f04f450f793541b39d13b6d47
merge         22afe31339dc3a75dc51c491a725330c6d433ecc
sources       150 main / 75 test
tests         207/207 PASS
```

Replay :

```text
M9 stable CLI: project=<uuid>, snapshot=scip-7f41649a3cdad442a3235c0a, architecture-modules=3, impact-root=GreetingPort
```

Décision : `docs/m9/DECISION_M9.md`.

## M10 — Serveur MCP — LIVRÉ

Issue #31 clôturée. PR #32 fusionnée.

```text
head validé   3f3657a6e5c1a783993348c892f97138d990feff
merge         eb042852a936ad2e62e337ee35ed8a349096e794
sources       152 main / 77 test
tests         210/210 PASS
```

Choix techniques :

```text
SDK MCP Java officiel   2.0.0
Transport               STDIO
API serveur             synchrone
Framework web           aucun
Tools                    15 read-only
```

Replay :

```text
M10 MCP stdio: tools=15, project=<uuid>, snapshot=<snapshot>, architecture-modules=3, impact-root=GreetingPort
```

Décision : `docs/m10/DECISION_M10.md`.

## M11 — API publique — IMPLÉMENTÉ

Suivi : issue #33.

PR Draft : #34.

Branche :

```text
m11/public-api
```

### Contrat

```text
com.minos.api.MinosApi
com.minos.api.LocalMinosApi
CONTRACT_VERSION = 1
```

La surface publique couvre :

```text
projets : add / list / inspect
index : import SCIP explicite + statut
symboles
usages
relations
architecture
contexte module
impact
```

### Frontière publique

Les signatures de `MinosApi` utilisent uniquement :

- des types JDK ;
- des DTO/requêtes définis par `MinosApi` ;
- `MinosApiException` et `ErrorCode`.

Aucun type SCIP/Glean, store, CLI/MCP ou modèle interne n’est exposé au consommateur.

`LocalMinosApi` délègue aux capacités déjà qualifiées M1–M9 ; aucune intelligence métier n’est réimplémentée dans `com.minos.api`.

### DTO et sémantique

Les DTO M11 conservent notamment :

- identité et provenance des symboles ;
- localisation et résolution des usages ;
- nature, confiance et preuves des relations ;
- agrégats d’architecture et contexte module ;
- chemins explicatifs, confiance, tests potentiels et limitations M8.

Les valeurs d’enums métier traversent la frontière sous forme de chaînes pour éviter un couplage binaire aux enums internes.

### Erreurs publiques

```text
INVALID_REQUEST
UNAVAILABLE
IO_FAILURE
EXECUTION_FAILURE
```

### Qualification ajoutée

`MinosApiContractTest` vérifie par réflexion qu’aucun type interne interdit ne fuite dans les méthodes ou composants de records publics.

`LocalMinosApiIntegrationTest` rejoue :

```text
fixtures/typescript/typescript-modules
```

et couvre :

```text
project add/list/inspect
SCIP import + READY
GreetingPort
IMPLEMENTS entrant
architecture = 3 modules
module context = packages/api
impact = 2
potential tests = 1
invalid enum -> INVALID_REQUEST
```

Replay attendu :

```text
M11 public API: version=1, project=<uuid>, snapshot=<snapshot>, modules=3, impact=2, tests=1
```

### Contrôles GitHub actuels

SonarQube Cloud sur PR #34 : **Quality Gate passed**, 0 Security Hotspots, 0.0 % duplication sur nouveau code. Trois issues non bloquantes restent signalées par Sonar.

Aucun workflow GitHub Actions n’est lancé pour la PR ; la preuve finale reste donc locale.

## Porte active — finale M11

```powershell
.\mvnw.cmd clean verify
```

Volumes attendus sur le code actuellement ajouté :

```text
154 sources main
79 sources test
214 tests
```

La PR #34 reste Draft jusqu’à validation locale du **head exact final**.

Après porte verte et fusion explicitement autorisée :

- issue #33 → `completed` ;
- M11 → terminé, validé et livré ;
- M12 — Multi-dépôts et intelligence Git → prochain jalon.

## Sources de vérité

- roadmap : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- suivi M11 : issue #33 ;
- PR M11 : #34 ;
- M9 : `docs/m9/CLI.md`, `docs/m9/DECISION_M9.md` ;
- M10 : `docs/m10/MCP_SERVER.md`, `docs/m10/DECISION_M10.md` ;
- M11 : `docs/m11/API.md`, `docs/m11/DECISION_M11.md`.
