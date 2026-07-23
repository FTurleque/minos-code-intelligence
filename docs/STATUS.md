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
M10 — Serveur MCP                   FONCTIONNELLEMENT COMPLET — PORTE FINALE EN ATTENTE
M11 à M13                           NON DÉMARRÉS
```

GitHub Actions reste volontairement hors de la porte locale courante ; l’anomalie historique est suivie séparément dans #5.

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
```

## M7 — Indexation incrémentale — LIVRÉ

Issue #22 clôturée. Merge final :

```text
c66382705880158b9ccac63b5662b81bf2d8d255
```

Head validé : `ab9367dd532891ba5d5099a7bc9fa7d0ef5074f7`.

Porte : **134 sources main / 69 test / 196 PASS**.

Décision : `docs/m7/DECISION_M7.md`.

## M8 — Analyse d’impact — LIVRÉ

Issue #27 clôturée `completed`.

PR finale : #28.

Head exact validé :

```text
08bbdeab18873a2209f02b58bc8d7e547443ea0f
```

Merge final :

```text
8147db5c246c7bad92c9b6ab21be81084dc64f59
```

Porte finale :

```text
143 sources main
72 sources test
203/203 tests PASS
BUILD SUCCESS
```

Replay réel :

```text
M8 typescript-modules impact: root=GreetingPort, impacts=2, tests=1, max-depth=2, limitations=[DYNAMIC_DISPATCH_NOT_PROVEN, REFLECTION_NOT_PROVEN, RUNTIME_CONFIGURATION_NOT_PROVEN]
```

Décision : `docs/m8/DECISION_M8.md`.

## M9 — CLI stabilisée — LIVRÉ

Issue #29 clôturée `completed`.

PR finale : #30.

Head exact validé :

```text
ae82f24897ea925f04f450f793541b39d13b6d47
```

Merge final :

```text
22afe31339dc3a75dc51c491a725330c6d433ecc
```

Porte finale :

```text
150 sources main
75 sources test
207/207 tests PASS
BUILD SUCCESS
```

Replay réel :

```text
M9 stable CLI: project=<uuid>, snapshot=scip-7f41649a3cdad442a3235c0a, architecture-modules=3, impact-root=GreetingPort
```

Acquis M9 :

- surface CLI administration/recherche/relations/architecture/impact ;
- formats `text` et `json` ;
- codes de sortie `0 / 1 / 2` ;
- erreurs sur stderr ;
- aide lazy ;
- import SCIP explicite sans runner externe inventé ;
- statut aligné sur un snapshot réellement relisible ;
- replay end-to-end réel.

Décision : `docs/m9/DECISION_M9.md`.

## M10 — Serveur MCP — IMPLÉMENTÉ

Suivi : issue #31.

Branche :

```text
m10/mcp-server
```

### Choix techniques

```text
SDK MCP Java officiel   2.0.0
Transport               STDIO
API                     synchrone
Framework web           aucun
Tools                    15 read-only
```

### Tools M10

```text
minos_project_structure
minos_index_status
minos_search_code
minos_find_symbols
minos_find_usages
minos_find_implementations
minos_find_callers
minos_find_callees
minos_dependencies
minos_dependents
minos_related_tests
minos_symbol_context
minos_module_context
minos_architecture
minos_impact
```

### Frontière

Les handlers MCP traduisent les arguments vers la surface JSON M9 puis délèguent à `MinosLauncher.run(...)`.

Aucune intelligence M1–M8 n’est réimplémentée dans le protocole.

Le serveur est read-only : aucune indexation, mutation de registre ou écriture projet n’est exposée par MCP.

### Sécurité contractuelle

- JSON Schemas bornés ;
- `additionalProperties=false` ;
- validation d’entrée du SDK conservée ;
- erreur tool structurée pour les échecs CLI ;
- aucune sortie applicative sur stdout hors protocole ;
- transport local process-based uniquement.

### Packaging

Le build conserve le JAR CLI et ajoute :

```text
target/minos-code-intelligence-0.1.0-SNAPSHOT-all.jar
```

Le Shade Plugin fusionne `META-INF/services` pour les fournisseurs chargés par `ServiceLoader`.

### Qualification

`MinosMcpToolsTest` couvre catalogue/traduction/erreurs.

`MinosMcpServerIntegrationTest` lance un serveur enfant réel via STDIO avec le client du SDK officiel et vérifie :

```text
15 tools
architecture modules = 3
impact GreetingPort = 2
related impacted tests = 1
schema depth=99 rejeté
```

Replay attendu :

```text
M10 MCP stdio: tools=15, project=<uuid>, snapshot=<snapshot>, architecture-modules=3, impact-root=GreetingPort
```

Documents :

- `docs/m10/MCP_SERVER.md` ;
- `docs/m10/DECISION_M10.md`.

## Porte active — finale M10

```powershell
.\mvnw.cmd clean verify
```

Volumes attendus si le head ne change plus :

```text
152 sources main
77 sources test
210 tests
```

La PR M10 doit rester Draft jusqu’à validation locale du **head exact**.

Après porte verte et fusion explicitement autorisée :

- issue #31 → `completed` ;
- M10 → terminé, validé et livré ;
- M11 — API → prochain jalon.

## Sources de vérité

- roadmap : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- suivi M10 : issue #31 ;
- M8 : `docs/m8/IMPACT_ANALYSIS.md`, `docs/m8/DECISION_M8.md` ;
- M9 : `docs/m9/CLI.md`, `docs/m9/DECISION_M9.md` ;
- M10 : `docs/m10/MCP_SERVER.md`, `docs/m10/DECISION_M10.md`.
