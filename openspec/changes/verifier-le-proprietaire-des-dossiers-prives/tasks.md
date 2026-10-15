# Tasks

Ordre : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Aucun test ne vise le vrai profil : `user.home` est redirigé vers un dossier temporaire et le principal attendu est fourni par le test. **Jamais de PIT local** sur ce code.

## 1. Garde (D1 à D5)

- [x] 1.1 Rouge : dans `PrivateLocalStorageWindowsAclTest`, « profil redirigé : refusé, DACL inchangée », « parent du profil : refusé », « dossier préexistant d'un autre principal : refusé, DACL inchangée, aucun chemin dans le message », « dossier préexistant de l'utilisateur : durci ». **Preuve du rouge : le point d'entrée n'existe pas ; avec un point d'entrée qui délègue au comportement actuel, les refus n'ont pas lieu.** — rouge : compilation impossible, puis, avec un point d'entrée qui délègue au comportement actuel, deux tests en échec (« Expected java.io.IOException to be thrown, but nothing was thrown ») pour le profil redirigé et le dossier d'un autre principal.
- [x] 1.2 Correction dans `PrivateLocalStorage` : existence notée avant création ; sur ACL, refus D2 et D3 avant `hardenDirectory` ; probe replié (D4). Preuve : `./mvnw -B -ntp -pl minos-engine -am test` vert sous Windows ; `check-p0-p2.py`, `check-private-io.py` verts. — fait : garde sur ACL avant `createPrivateDirectories`, messages sans chemin, probe replié sur le dossier vérifié (pas le dossier temporaire public : SonarCloud S5443) ; garde et lecture de `user.home` testées sur tous les systèmes (`PrivateLocalStorageSharedLocationTest`, 3 cas) ; moteur 711 tests verts sous Windows. `./mvnw -B -ntp -pl minos-engine -am test` vert sous Windows (708 tests, 0 échec) ; `check-private-io.py` et `check-p0-p2.py` verts.
- [x] 1.3 Non-régression des appelants : `./mvnw -B -ntp -pl minos-application,minos-runtime-local,minos-storage-local -am test` vert sous Windows (dossiers temporaires préexistants). — fait sous Windows, tests des paquets de chaque module : `minos-runtime-local` 485, `minos-storage-local` 244, `com/minos/application/**` 80, tous verts. Linux et l'image Docker : en CI (2.3).

## 2. Clôture

- [x] 2.1 `docs/developer/` (stockage privé) ou `docs/user/troubleshooting.md` : message et remède. `python scripts/docs/check-current-docs.py` vert. — fait (`docs/user/troubleshooting.md`).
- [x] 2.2 `docs/audit/2026-10-10/SUIVI.md` : reste « moteur » de H23 traité. — fait.
- [ ] 2.3 **(manuelle)** À la PR : `Verify (windows-2022)` et `Verify (ubuntu-24.04)` verts, image Docker qualifiée (`Provider-complete image exact-head gate`) ; noter les run ids.
- [x] 2.4 `openspec validate --all --strict`. — 64 passés, 0 échec.
