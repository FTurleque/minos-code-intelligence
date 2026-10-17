# Tasks

Ordre : preuve de bout en bout, décision, puis (seulement si nécessaire) rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Les exigences de `specs/` valent quelle que soit l'option de D2 ; seule leur mise en œuvre change.

## 1. Preuve de bout en bout (AUD-SEC-09)

- [x] 1.1 [AUD-SEC-09] Dans `MinosMcpServerIntegrationTest` (transport STDIO réel) : appeler un outil à argument `limit` avec `limt`, puis avec un argument inconnu dont la valeur est un chemin absolu. Relever : refus ou non ; forme du refus (erreur de protocole ou résultat d'outil en erreur) ; contenu de stderr. Lancer `./mvnw -B -ntp -pl minos-app -am verify`. **Résultat attendu inconnu : le test tranche entre « confirmé » (argument servi par défaut) et « non reproduit » (refus du SDK).**
- [x] 1.2 [AUD-SEC-09] Vérifier dans le jar distribué qu'une implémentation du validateur de schéma du SDK 2.0.1 est présente sur le classpath et résolue (et non absente en silence) ; relire `MinosMcpServer.java:56-61`. Consigner 1.1 et 1.2 dans `design.md` et requalifier la confiance du constat.
- [x] 1.3 [AUD-SEC-09] Confronter le journal relevé en 1.1 à l'exigence « Un échec d'outil MCP est diagnostiquable par l'opérateur sans exposer son message » de `openspec/specs/surfaces-publiques/spec.md` : le WARN du SDK contient-il une valeur d'argument ? Consigner dans `design.md` (D3).
- [x] 1.4 [AUD-SEC-09] **Décision à obtenir** : D2 (A, recommandé : défense dans `MinosMcpTools` en plus du SDK ; B : SDK seul, exclu si 1.1 est rouge ; C : validation MINOS seule, si 1.3 l'impose). Consigner dans `design.md`.

## 2. Refus garanti par MINOS (AUD-SEC-09) — si D2 = A ou C, ou si 1.1 est rouge

- [x] 2.1 [AUD-SEC-09] Rouge : dans `minos-mcp`, test sur le catalogue entier — chaque outil, appelé directement avec ses arguments requis et une clé inconnue, n'exécute pas son opération (backend de test qui compte ses appels) ; clé de 10 000 caractères : réponse bornée ; clé à forme de chemin absolu : erreur générique. **Preuve du rouge : au HEAD, l'opération est exécutée (`MinosMcpTools.java:435-437`).**
- [x] 2.2 [AUD-SEC-09] `MinosMcpTools` : rejet des clés absentes des propriétés du schéma, propriétés dérivées du schéma une fois à la construction du catalogue, refus par `clientError` avec la clé bornée. Conserver les littéraux exigés par les gates. Preuve : tests verts ; `python scripts/remediation/check-mnd.py`, `python scripts/remediation/check-p0-p2.py`, `python scripts/remediation/check-post-mne.py`, `python scripts/quality/check-runtime-dynamic-consistency.py`, `python scripts/quality/check-hosted-control-plane-consistency.py` verts ; `python scripts/quality/check-jacoco.py` (scopes `mcp-mapping`, `m19-m20-mcp-catalogue`) ; `./mvnw -B -ntp -pl minos-mcp -am verify`.

## 3. Journal du refus (AUD-SEC-09) — si 1.3 montre une valeur d'argument journalisée

- [x] 3.1 [AUD-SEC-09] Rouge : le cas « valeur absente de stderr » de 1.1 devient une assertion. **Preuve du rouge : la valeur figure dans le journal relevé en 1.3.**
- [x] 3.2 [AUD-SEC-09] Corriger selon D3 (niveau ou contenu du journal du SDK ; sinon option C dans `MinosMcpServer`). Preuve : test vert ; `MinosMcpFailureLoggingTest` vert ; stdout toujours réservé au protocole (`MinosMcpServerLifecycleTest`).

## 4. Garde durable (AUD-SEC-09)

- [x] 4.1 [AUD-SEC-09] Les cas de 1.1 restent dans `MinosMcpServerIntegrationTest` comme assertions (refus, aucun résultat, aucune valeur dans stderr) : une montée du SDK qui changerait le comportement par défaut échoue en CI. Preuve : `./mvnw -B -ntp -pl minos-app -am verify`.

## 5. Clôture

- [x] 5.1 `docs/user/mcp.md` : un argument hors schéma est refusé ; forme de l'erreur. Rejouer `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check`.
- [x] 5.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-SEC-09 : confiance requalifiée d'après 1.1, validateur du SDK non relevé par l'audit, décision D2, résultat de 1.3).
- [x] 5.3 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, `docs/user/mcp.md`.
