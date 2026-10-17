# Tasks

Ordre dans chaque section : rouge, correction, preuve. Aucun fichier Java n'est modifié par ce changement. À faire **avant** les autres changements du sprint 8.

## 1. Relevé et décision (AUD-SEC-03)

- [x] 1.1 [AUD-SEC-03] Ajouter provisoirement les règles de D1 au gate et relever son décompte réel par couple (fichier, API) : il fait foi sur le relevé `grep` du design (43 couples, 30 fichiers, plus une redirection). Consigner l'écart éventuel dans `design.md`.
- [x] 1.2 [AUD-SEC-03] **Décision à obtenir** : D2 (`PrivateLocalStorage` par entrées de liste, recommandé, ou remis dans `PRIMITIVES`). Consigner dans `design.md`.

## 2. Règles et auto-test (AUD-SEC-03)

- [x] 2.1 [AUD-SEC-03] Rouge : dans `scripts/architecture/test_check_private_io.py`, un témoin par règle nouvelle dans `WITNESSES`, un cas « référence de méthode » et un cas « import statique » sur une API nouvelle, un cas de redirection refusé et un accepté ; réécrire `test_the_similar_but_allowed_calls_are_not_caught` (`:167-170`) pour qu'il ne garde que des appels réellement permis. **Preuve du rouge : `test_the_witnesses_cover_every_forbidden_call` échoue et les témoins nouveaux ne produisent aucune violation au HEAD.**
- [x] 2.2 [AUD-SEC-03] `scripts/architecture/check-private-io.py` : règles de D1 dans `FORBIDDEN`, `_FILES_METHODS` et `_PATTERNS` ; docstring mise à jour (liste des appels interdits, phrase sur `PrivateLocalStorage`, selon D2). Preuve : `python scripts/architecture/test_check_private_io.py -v` vert. **Preuve du rouge sur le dépôt réel : `python scripts/architecture/check-private-io.py` échoue et énumère les occurrences non listées.**

## 3. Liste de tolérance (AUD-SEC-03)

- [x] 3.1 [AUD-SEC-03] Inscrire chaque couple relevé en 1.1 dans `scripts/architecture/private-io-allowlist.json`, avec son maximum et une justification qui nomme soit la raison durable (cgroupfs pour `LinuxCgroupJob`), soit le lot de migration (D3) ; `PrivateLocalStorage` selon D2. Preuve : `python scripts/architecture/check-private-io.py` vert, `forbidden=20` dans sa sortie ; retirer une entrée au hasard puis en gonfler une autre et constater les deux échecs (non commités).
- [x] 3.2 [AUD-SEC-03] Aligner `.claude/rules/java-production.md` (liste des primitives et des appels interdits) et `docs/developer/quality-gates.md` (section du gate) sur l'état réel. Preuve : `python scripts/docs/check-current-docs.py`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python .claude/scripts/run_gates.py --fast` verts.

## 4. Clôture

- [x] 4.1 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-SEC-03 : statut « garde posé, migration à lotir », décomptes corrigés, décision D2, liste des lots de migration à ouvrir avec leurs fichiers).
- [x] 4.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/developer/quality-gates.md`, `.claude/rules/java-production.md`, `docs/project/governance/STATUS.md` si le garde y est cité.
