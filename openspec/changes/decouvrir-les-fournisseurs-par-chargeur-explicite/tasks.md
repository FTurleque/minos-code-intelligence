# Tasks

Ordre : relevé et décision, puis dans chaque section rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`.

## 1. Relevé et décision (AUD-ARC-04)

- [x] 1.1 [AUD-ARC-04] Établir par exécution quels fournisseurs `StorageBackendProvider` sont visibles dans les tests de `minos-api`, `minos-cli`, `minos-mcp`, `minos-bootstrap` et `minos-app` (le double `CloseCountingStorageProvider` et le vrai fournisseur PostgreSQL y coexistent-ils ?) ; lister les tests qui dépendent de « le premier gagne ». Vérifier la présence des deux fichiers `META-INF/services` dans le JAR ombré de `minos-app`. Consigner dans `design.md`.
- [x] 1.2 [AUD-ARC-04] **Décision à obtenir** : D3 (injection du fournisseur d'identité reportée, recommandé ; ou faite maintenant). Consigner dans `design.md`.

## 2. Sélection du stockage (AUD-ARC-04)

- [x] 2.1 [AUD-ARC-04] Rouge : dans `minos-bootstrap`, tests de `StorageBackendSelection` — chargeur de contexte vide : PostgreSQL trouvé ; chargeur à deux fournisseurs `postgresql` : refus nommant les classes, sans chemin ; même chargeur et backend `local` : ouvert ; fournisseur absent : message existant. **Preuve du rouge : avec un chargeur de contexte vide, « provider is not available » au HEAD ; avec deux fournisseurs, le premier est ouvert.**
- [x] 2.2 [AUD-ARC-04] `StorageBackendSelection` : chargeur du port, variante à chargeur paramétrable de visibilité paquet, refus des doublons de l'identifiant demandé (`Locale.ROOT`) (D1, D2). Corriger les tests relevés en 1.1 pour qu'un seul fournisseur `postgresql` soit visible par module. Preuve : tests verts ; `./mvnw -B -ntp -pl minos-bootstrap -am verify` ; `./mvnw -B -ntp -pl minos-app -am verify` ; `python scripts/quality/check-jacoco.py` (scope `m30-storage-backend-selection`).

## 3. Identité de chemin (AUD-ARC-04)

- [x] 3.1 [AUD-ARC-04] Rouge : dans `minos-runtime-local` (où le fournisseur est enregistré), test Windows — chargeur de contexte vide : identité capturée ; dans `minos-engine`, test à chargeur isolé — deux fournisseurs : identité indisponible, `ExecutionPathAuthorization.tryCapture` rend vide. **Preuve du rouge : sous Windows, identité vide avec un chargeur de contexte vide au HEAD.** Dire si le test Windows a été exécuté localement ou seulement en CI.
- [ ] 3.2 [AUD-ARC-04] `StableFileSystemIdentity` : chargeur du port, refus de plus d'un fournisseur avec un journal qui nomme les classes ; Javadoc de `ExecutionPathIdentityProvider` alignée (« au plus un fournisseur »). Preuve : tests verts ; `./mvnw -B -ntp -pl minos-runtime-local -am verify` ; tests de confinement existants verts sur les deux systèmes (CI).

## 4. Garde (AUD-ARC-04)

- [x] 4.1 [AUD-ARC-04] Rouge : dans `scripts/architecture/test_check_module_boundaries.py`, cas « `ServiceLoader.load` à un argument dans un `src/main` » refusé et cas à deux arguments accepté. **Preuve du rouge : la règle ajoutée échoue sur le HEAD (2 sites).**
- [x] 4.2 [AUD-ARC-04] Règle dans `scripts/architecture/check-module-boundaries.py` (D4, numéro fixé à l'implémentation) ; `docs/developer/quality-gates.md` mis à jour. Preuve : `python scripts/architecture/check-module-boundaries.py` et son auto-test verts ; `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` verts.

## 5. Clôture

- [x] 5.1 Amender l'ADR 0042 par une note de « Mise en œuvre » (résidu de chargeur de contexte fermé aux deux sites, règle des doublons, extensions par chargeur de contexte non supportées, injection reportée) ; cocher dans `docs/project/roadmap/storage-hexagonal-2026-10/TASKS.md` les actions de SH-10 réalisées et laisser ouvertes celles qui ne le sont pas. Rejouer `python scripts/docs/check-current-docs.py`.
- [x] 5.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-ARC-04 : statut, décision D3, résultat du relevé 1.1, précision sur l'identifiant « A8 »).
- [ ] 5.3 `python .claude/scripts/run_gates.py` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, ADR 0042, `docs/developer/quality-gates.md`.
