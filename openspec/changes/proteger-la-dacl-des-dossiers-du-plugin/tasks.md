# Tasks

Ordre : rouge, correction, preuve. Le plugin se construit par son wrapper (`minos-intellij/gradlew`), jamais par Maven. **Ne jamais lancer `pitest` du plugin sur un poste** (cause probable de H23). Aucun test ne vise le vrai profil : `user.home` est redirigé vers un dossier temporaire.

## 1. Garde des emplacements partagés (D1)

- [x] 1.1 Rouge : tests de `refuseSharedLocation` (profil, ancêtre du profil, racine de volume refusés ; `profil/.minos` et un dossier voisin acceptés) et test Windows « `minosHome` égal à `user.home` (redirigé vers un dossier temporaire) : lancement refusé, DACL inchangée ». **Preuve du rouge : la méthode n'existe pas ; le test Windows réussit le lancement au HEAD.** — rouge : compilation impossible, puis, avec des méthodes vides, trois tests en échec (« Expected java.io.IOException to be thrown, but nothing was thrown ») : au HEAD le lancement sur le profil réussit et réécrit sa DACL (dossier temporaire).
- [x] 1.2 Correction dans `MinosStrongProcessLauncher.ensureWindowsPrivateDirectory`, avant toute création. Preuve : tests verts. — fait : refus avant toute création ; la casse est ignorée sous Windows (test en majuscules).

## 2. Propriétaire d'un dossier préexistant (D2, D3)

- [x] 2.1 Rouge : test Windows « dossier préexistant, principal attendu différent du propriétaire : refus, DACL inchangée » ; test « dossier préexistant de l'utilisateur : rendu privé ». **Preuve du rouge : `requireOwnedBy` n'existe pas.** — rouge dans le même run (dossier d'un autre propriétaire accepté au HEAD).
- [x] 2.2 Correction : détection de l'existence avant création, `requireOwnedBy` avec l'identité de référence (propriétaire d'un fichier créé par le processus). Preuve : `./gradlew --no-daemon test` vert sous Windows (tests du lanceur compris) ; `python scripts/remediation/check-p0-p2.py` vert ; aucune dépendance `com.minos:`. — fait : `requireOwnedBy` avec l'identité du propriétaire d'un fichier créé par le processus. `./gradlew test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure` vert sous Windows (82 tests, 0 échec, 8 sautés) ; `check-p0-p2.py` vert ; aucune dépendance `com.minos:` ; `run_gates.py --fast` 20/20. Linux : seul le test sans plateforme y tourne, en CI.

## 3. Clôture

- [x] 3.1 `docs/user/intellij-plugin.md` : le dossier MINOS doit être dédié et appartenir à l'utilisateur ; message d'erreur et remède. `python scripts/docs/check-current-docs.py` vert. — fait (« Ownership OS des commandes CLI »).
- [x] 3.2 `docs/project/audits/2026-10-10/SUIVI.md` : cause de H23 traitée côté plugin ; défaut analogue du moteur en reste. — fait.
- [ ] 3.3 **(manuelle)** À la PR : `IntelliJ plugin (gate)` vert, job Windows exécuté ; noter les run ids.
- [x] 3.4 `openspec validate --all --strict`. — 63 passés, 0 échec.
