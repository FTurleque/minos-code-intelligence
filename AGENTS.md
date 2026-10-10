# MINOS Code Intelligence — consignes pour les agents IA

Moteur de code intelligence **local-first**, multi-langages, indépendant des fournisseurs d'IA et **capability-honest** : une capacité absente ou non qualifiée n'est jamais présentée comme acquise. Logiciel propriétaire source-available (`LICENSE`, `CONTRIBUTING.md`).

Ce fichier est la source commune pour tout agent (Claude Code, Codex, etc.). `CLAUDE.md` l'importe et ajoute ce qui est propre à Claude Code. Détail par sujet : `.claude/rules/`, `.claude/skills/`, `docs/developer/ai-configuration.md`.

## Langue

Artefacts du dépôt (docs, ADR, OpenSpec, messages de commit, PR, commentaires de gouvernance) **en français**. Identifiants, noms de classes et messages de code suivent la convention du module touché (Javadoc sans mélange de langues dans un même fichier).

## Toolchain (`docs/TOOLCHAIN_POLICY.md`)

| Partie | Baseline |
|---|---|
| Noyau, CLI, MCP, stockage, providers | Java **24**, Maven **3.9+** par le **wrapper** `mvnw` / `mvnw.cmd` (il fait foi, pas un Maven local) |
| Plugin `minos-intellij` | Java **21**, Gradle **9.6.1** (`minos-intellij/gradlew`), IntelliJ Platform 2026.1, **hors du reactor Maven** |
| Qualification CI | Ubuntu 24.04 **et** Windows Server 2022 : tout changement tient sur les deux |

Ne pas élargir une plage de versions « parce qu'une version plus récente existe ». Un changement de baseline est une décision (voir la politique).

## Commandes de référence

```bash
./mvnw -B -ntp -pl <module> -am test          # un module et ses amonts (-am est nécessaire)
./mvnw -B -ntp verify                         # tout le reactor (long)
python .claude/scripts/run_gates.py --fast    # gates statiques du job `invariants` (~10 s)
python .claude/scripts/run_gates.py           # + auto-tests des gates
openspec validate --all --strict              # spécifications
```

- Windows : `mvnw.cmd` lancé depuis PowerShell 7 exige `powershell.exe` 5.1 (`C:\Windows\System32\WindowsPowerShell\v1.0`) dans le `PATH` et un `PSModulePath` de Windows PowerShell ; **citer** les arguments `-Dx.y=z`.
- `clean` à la racine supprime `target/` racine (JAR ombré utilisé par d'autres consommateurs) : le dire avant de le lancer.
- Sur poste Windows, le scope JaCoCo `m24-polyglot-provider-platform` est rouge hors régression : comparer à `develop` avant d'investiguer.

## Architecture (ne pas contourner)

- Reactor de 14 modules, direction `domain → engine → runtime/storage → adapters → application → surfaces` (ADR 0022). **Ne jamais créer un module pour reproduire un package** ; un package appartient à un seul module (ADR 0044).
- Racine de composition : `minos-bootstrap`, découverte par `ServiceLoader` (ADR 0042) ; `minos-app` est l'assemblage final distribué. `application ↛ adaptateurs`, `surfaces ↛ adaptateurs`.
- Aucun cycle de packages (règle A8 de `check-module-boundaries.py`, table `KNOWN_PACKAGE_CYCLES` vide, plugin compris).
- MCP en STDIO **lecture seule** (ADR 0017) : stdout est réservé au protocole, les journaux vont sur stderr. Surfaces publiques (CLI, API Java, MCP) **versionnées et additives**.
- **Fail-closed** : le code non fiable est refusé par le bac à sable OS, jamais exécuté en mode dégradé. Une décision d'architecture structurante donne un ADR numéroté (`docs/adr/`, prochain numéro : lire l'index).
- Le plugin IntelliJ est un client externe du protocole CLI : aucune dépendance `com.minos:*`.

## Gouvernance Git et CI

- Branches thématiques (`docs/`, `ci/`, `fix/`, `feat/`, `refactor/`, `build/`, `sec/`, `chore/`) → PR vers `develop` → promotion `develop` → `main`. **Jamais de push direct** sur `main` ni `develop`, jamais de push forcé. Releases immuables (tags `vX.Y.Z`, jamais retaguées).
- Commits : Conventional Commits en français (`type(portée): sujet`), un sujet par commit.
- Ruleset « Protect main & develop » : `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, `Dependency vulnerability gate / osv-scan`, `Static invariants (single run)`, `Gitleaks`, `IntelliJ plugin (gate)`, `SonarCloud Code Analysis` (liste déclarée dans `.github/required-checks.json`). Branche **à jour** exigée : une PR « BEHIND » passe par `gh pr update-branch` puis un nouveau CI.
- SonarCloud juge la couverture du **nouveau code module par module** (seuil 80 %) : une classe déplacée compte comme code neuf, ses tests doivent vivre dans son module.
- Aucune CI, aucun push, aucune PR sans que l'utilisateur l'ait demandé.

## Gates littéraux

Les scripts `scripts/**/check-*.py` affirment des **chaînes exactes** lues dans les sources (noms de méthodes, de classes, expressions d'appel). Avant de renommer, déplacer ou restructurer : chercher l'ancien nom dans `scripts/` et rejouer le gate concerné (skill `minos-literal-gates`). Tout `test_*.py` et tout script à `--self-test` doit être exécuté par le job `invariants` (`check-ci-wiring.py`).

## OpenSpec

`openspec/config.yaml` fait contrat. Flux : proposition → design (« État vérifié au HEAD ») → spécifications (`## ADDED/MODIFIED Requirements`, chaque exigence ≤ 500 caractères avec au moins un `#### Scenario:` GIVEN/WHEN/THEN) → tâches (rouge → correction → preuve, tests dans la même tâche que le code, tâche finale « Clôture »). Valider par `openspec validate --all --strict`. Les spécifications courantes (`openspec/specs/`) ne se modifient qu'en archivant un changement ; les archives sont de l'historique.

## Sécurité (liste courte ; détail dans `.claude/rules/securite.md`)

Aucun secret versionné (Gitleaks) ; environnement des processus externes issu d'une liste d'autorisation, jamais hérité ; aucune écriture ni lecture de fichier privé hors des primitives d'E/S (`check-private-io.py`) ; aucun chemin absolu dans un message public ni un journal WARNING ; `Locale.ROOT` pour toute comparaison de casse.

## Documentation

État produit : `docs/STATUS.md`, `docs/ROADMAP.md` (gardés par `check-current-docs.py` et `product-facts.py --check`). Historique figé : `docs/history/`, `docs/audit/archive/`, `openspec/changes/archive/`. Audit en cours : `docs/audit/2026-10-10/` (suivi dans `SUIVI.md`).
