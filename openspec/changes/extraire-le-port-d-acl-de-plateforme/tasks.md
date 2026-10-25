# Tasks

Ordre dans chaque section : rouge, correction, preuve. Les tests vivent dans la même tâche que le code. Gates à rejouer à chaque tâche qui déplace du code : `python scripts/architecture/check-module-boundaries.py` et son auto-test, `python scripts/architecture/check-private-io.py`, `python .claude/scripts/run_gates.py --fast`. Portées JaCoCo concernées : `confined-io-primitives` et la portée du runtime local qui reçoit `com/minos/runtime/local/acl/`.

## 0. Préalable

- [x] 0.1 [AUD-ARC-01, -05, -08] Archiver le changement de la phase 1 `rendre-les-cliquets-d-architecture-etanches` (fusionné, PR #563). L'exigence A12 modifiée ici part de son texte archivé. Preuve : `openspec validate --all --strict`.

## 1. Port et découverte dans le moteur (AUD-ARC-05 ; SH-08)

- [x] 1.1 [SH-08] Rouge (`minos-engine`) : avec un chargeur de classes sans implémentation, le durcissement d'un objet sur un système de fichiers à ACL (vue d'ACL factice, `FakeAclFileAttributeView`) échoue sans chemin, et l'objet créé est supprimé. Avec deux implémentations, il échoue. Sous POSIX, le port n'est jamais consulté. **Preuve du rouge au HEAD : le durcissement réussit sans port.**
- [x] 1.2 [SH-08] Interface `com.minos.io.PlatformAccessControl` ; découverte paresseuse, une fois, par `ServiceLoader.load(PlatformAccessControl.class, PrivateLocalStorage.class.getClassLoader())` ; `CapabilityProbe.real()` délègue au port (D1, D5). Preuve : tests 1.1 verts ; A16 compte 5 appels, auto-test mis à jour.

## 2. Lanceur et implémentation dans le runtime local (AUD-ARC-05 ; SH-08)

- [x] 2.1 [SH-08] Rouge (`minos-runtime-local`, Windows) : un répertoire privé sous un chemin de plus de 260 caractères ne peut pas être durci (code 3 d'`icacls`) ; un chemin UNC ; refus d'un chemin relatif ou porteur d'un caractère interdit sans lancement. **Preuve du rouge au HEAD sur le poste Windows.**
- [x] 2.2 [SH-08] `com.minos.runtime.local.acl.WindowsIcacls` (lanceur unique, préfixe `\\?\`, attente bornée) et `WindowsIcaclsAccessControl` (le port, logique XD reprise de `WindowsDacl`) ; enregistrement dans `META-INF/services` ; `WindowsAclGrants` délègue au lanceur (D3). `PrivateLocalStorageDaclReadTest` (10 tests) déplacé dans `com.minos.runtime.local.acl` et réécrit sur la nouvelle classe. Preuve : tests 2.1 verts, `WindowsAclGrantsTest` 9/9, `WindowsAppContainerWorkerSandboxBackendTest` 15/15 sous Windows.
- [x] 2.3 [SH-08] Test Windows de bout en bout du port réel, dans `minos-runtime-local` : refus conditionnel explicite et hérité conservés, autorisation conditionnelle héritée retirée, garde du profil, dossier d'un autre compte. Les cas sont repris de `PrivateLocalStorageWindowsAclTest`, sans affaiblir une assertion.

## 3. Doublure de test et parité (SH-08)

- [x] 3.1 [SH-08] Rouge : les tests Windows des cinq modules inférieurs échouent sans implémentation (mesure de la phase 2 : 95 classes, 381 tests).
- [x] 3.2 [SH-08] `com.minos.testsupport.IcaclsTestAccessControl` dans le `test-jar` du moteur ; enregistrement dans `src/test/platform-access-control/META-INF/services/`, ajouté au classpath de Surefire (`additionalClasspathElements`), de `minos-engine`, `minos-storage-local`, `minos-storage-postgresql`, `minos-application` et `minos-integration-git` ; dépendance de test au `test-jar` pour `minos-application`, `minos-integration-git` et `minos-storage-postgresql` (D2). Preuve : les tests des cinq modules sont verts sous Windows, et `PrivateLocalStorageWindowsAclTest` 20/20 sans modification d'assertion.
- [x] 3.3 [SH-08] Test de parité (Windows, `minos-runtime-local`) et test de `minos-bootstrap` « une seule implémentation découverte ». Rouge prouvé en altérant la doublure sur une copie.

## 4. Retrait du mécanisme du moteur et cliquets (AUD-ARC-05)

- [x] 4.1 [AUD-ARC-05] Retirer `WindowsDacl` de `PrivateLocalStorage`. `PROCESS_LAUNCHERS` = `{}` ; `test_the_real_private_storage_has_its_two_icacls_sites` devient « aucun lanceur ». `RUNTIME_LOCAL_GUARDED_PACKAGES` + `acl`, avec un cas refusé et un cas accepté dans l'auto-test (D4). Mutation sur copie : un `new ProcessBuilder` remis dans `PrivateLocalStorage` fait échouer A12.
- [x] 4.2 [SH-08] `private-io-allowlist.json` : `PrivateLocalStorage` `Files.createTempFile` 4 → 3, nouvelle entrée de 1 pour `WindowsIcaclsAccessControl` avec sa raison (D6). Base SpotBugs : 8 entrées déplacées (D6), raisons dans `docs/developer/quality/code-audit.md`. Preuve : `check-private-io.py` et son auto-test verts.
- [ ] 4.3 [SH-08] JaCoCo : re-mesurer `confined-io-primitives` sur les deux systèmes, et placer `com/minos/runtime/local/acl/` dans une portée avec plancher mesuré. Preuve : `check-jacoco.py` vert sur les rapports agrégés Linux et Windows.

## 5. Clôture

- [x] 5.1 ADR 0068 : note de mise en œuvre (A12 vide, port découvert par `ServiceLoader`, cartographie de `hosted` sans mécanisme d'E/S de plateforme). `docs/developer/quality-gates.md` (A10, A12). Rejouer `python .claude/scripts/run_gates.py`, `check-current-docs.py` et `product-facts.py --check`.
- [x] 5.2 Mesure de non-régression : rejouer, sous Windows, le premier `minos index` de `fixtures/typescript/typescript-inheritance` sous JFR (80 objets durcis attendus, aucun `icacls` lancé depuis `com.minos.io`). Rejouer aussi un `MINOS_HOME` de 140 caractères, où l'index doit réussir.
- [ ] 5.3 `docs/project/audits/2026-10-10/SUIVI.md` (AUD-ARC-05 fermable, compteurs A12 2 → 0) et plan du chantier (SH-08). `openspec validate --all --strict`, `archgate check`, `mvnw verify` complet (Windows ; Linux en conteneur ou par la CI).
