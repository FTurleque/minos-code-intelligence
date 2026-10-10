---
paths:
  - ".github/**"
  - "docker/**"
  - "packaging/**"
---

# Workflows GitHub, Docker, packaging

- **Épinglage immuable** : toute action externe est épinglée par SHA de commit (`check-workflow-pins.py`) ; toute image est `image:tag@sha256:digest` avec **une seule source** par image gérée (`check-image-pins.py`) ; tout service Compose porte ses plafonds CPU, mémoire et PID (`check-compose-limits.py`).
- **Une exécution par PR** : un contrôle lourd (Maven `verify`, faits produit, JaCoCo, toolchain sandbox) s'exécute **une fois par OS**, dans `pr-ci.yml` seulement (`check-single-execution.py`). Ne pas recréer un workflow qui répète un job déjà couvert.
- **Checks exigés** : la liste déclarée est `.github/required-checks.json` (ruleset « Protect main & develop » : `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `Dependency vulnerability gate / osv-scan`, `Static invariants (single run)`, `Gitleaks`, `IntelliJ plugin (gate)`, `SonarCloud Code Analysis` ; `Docker upgrade evidence gate` pour `main`). Un check exigé doit résoudre un job qui peut rendre un verdict sur une PR : **aucun filtre `paths:` au niveau du workflow** d'un check exigé (`check-ci-wiring.py`). Un check exigé qui n'a jamais tourné bloque toutes les PR : l'ajouter au ruleset après un premier run, à la main sur GitHub, puis `python scripts/quality/verify-ruleset.py`.
- **YAML** : un nom d'étape contenant « : » non cité invalide tout le workflow (aucun job ne démarre, run `push` en échec). Charger le fichier par `yaml.safe_load` avant de pousser ; le hook `post_edit.py` le fait.
- **Auto-tests** : tout `test_*.py` / `--self-test` d'un gate est exécuté par une étape du job `invariants`.
- **Permissions** : `permissions:` minimales par job, aucun secret exposé aux PR de forks, `GITHUB_TOKEN` en lecture sauf besoin nommé.
- **Releases** : tags immuables, candidat construit depuis `develop` contenant l'ascendance de `main` (vérifié par CI), preuve Docker/Windows avant promotion (`release-promotion-gate.yml`). Voir le skill `minos-release-promotion`.
- **Dependabot** couvre Maven, `/minos-intellij` (Gradle), GitHub Actions et les images ; il ne change jamais silencieusement la baseline Java/Maven/Gradle/IntelliJ (`docs/TOOLCHAIN_POLICY.md`).
- Toute modification de workflow est une modification de **gouvernance** : la présenter comme telle dans la PR, et rejouer `check-workflow-pins`, `check-single-execution`, `check-ci-wiring`.
