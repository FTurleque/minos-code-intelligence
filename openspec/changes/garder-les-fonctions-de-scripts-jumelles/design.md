# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-09**. HEAD analysé : `5ce7b79b` (`develop` du 2026-10-22). Motivation : voir `proposal.md`.

## État vérifié au HEAD

### Méthode d'inventaire (reproductible)

1. Fichiers : `git ls-files '*.ps1' '*.psm1' '*.py'`, hors `scripts/history/`.
2. PowerShell : chaque ligne `function <Nom>` ; le corps va de cette ligne à l'accolade qui ferme la première accolade ouverte (comptage d'accolades). Python : fonctions de premier niveau, par le module `ast` (décorateurs compris).
3. Normalisation : CRLF ramené à LF, espaces de fin de ligne retirés ; rien d'autre (indentation et commentaires gardés).
4. Empreinte SHA-256 de chaque corps ; regroupement par nom (insensible à la casse pour PowerShell) ; un groupe compte s'il couvre au moins deux fichiers.
5. Contrôle croisé sur les jumelles retenues : extraction « `function` en colonne 0 jusqu'à la première ligne `}` en colonne 0 » ; mêmes bornes et mêmes regroupements que l'étape 2 pour les 25 copies PowerShell des 12 entrées de D5. Les 39 scripts du périmètre (D5) portent 280 définitions en colonne 0 et 4 définitions imbriquées (`docker/scripts/configure-docker-mcp.ps1:77`, `scripts/install/verify-windows-upgrade-transaction.ps1:365,383,448`).

Résultat : 42 noms PowerShell et 24 noms Python sont définis dans au moins deux fichiers ; 23 des noms PowerShell le sont dans au moins deux scripts du périmètre. La méthode rejouée au HEAD de départ de l'implémentation, `a8288b43` (`develop` du 2026-10-24), donne les mêmes comptes et les mêmes jumelles identiques ; aucun script du périmètre n'a changé entre les deux HEAD.

### Jumelles identiques dans les scripts vivants

| Fonction | Copies (`fichier:ligne`) | Lignes |
|---|---|---|
| `Read-BoundedUtf8` | `docker/scripts/configure-m30-docker-services.ps1:51`, `scripts/install/configure-runtime-settings.ps1:134` | 24 |
| `Restrict-FileToCurrentUser` | `configure-m30-docker-services.ps1:112`, `configure-runtime-settings.ps1:71` | 6 |
| `Get-FileAccessSecurity` | `configure-m30-docker-services.ps1:119`, `configure-runtime-settings.ps1:78` | 11 |
| `Assert-FileRestrictedToCurrentUser` | `configure-m30-docker-services.ps1:131`, `configure-runtime-settings.ps1:90` | 22 |
| `Write-PrivateSecretFile` | `configure-m30-docker-services.ps1:154`, `configure-runtime-settings.ps1:113` | 14 |
| `Test-CeilingOverride` | `docker/scripts/mcp-lifecycle.ps1:219`, `docker/scripts/prod-mcp.ps1:49` | 12 |
| `Get-PreservedCeilingOverrides` | `mcp-lifecycle.ps1:232`, `prod-mcp.ps1:62` | 11 |
| `Get-UserFolderPath` | `scripts/install/configure-mcp-clients.ps1:45`, `scripts/install/detect-mcp-clients.ps1:49` | 5 |
| `Test-Case` | `scripts/release/test-iscc-provenance.ps1:19`, `scripts/release/test-release-signature.ps1:29` | 10 |
| `Assert-True` | `scripts/install/verify-mcp-backend-lifecycle.ps1:17`, `verify-mcp-client-backend-routing.ps1:20`, `verify-mcp-client-preflight.ps1:17` | 3 |
| `find_ci_wiring_violations` (Python) | `scripts/quality/check-compose-limits.py:162`, `scripts/quality/check-image-pins.py:193` | 35 |

Les quatre fonctions d'ACL du fichier de secret et `Read-BoundedUtf8` sont des fonctions de sécurité, livrées dans deux scripts.

### Différences de forme seulement

- **`Require-Identifier`** (`configure-m30-docker-services.ps1:45-49`, `configure-runtime-settings.ps1:55-59`) : même signature, même motif `^[A-Za-z_][A-Za-z0-9_]{0,62}$`, même test `IsNullOrWhiteSpace`. Seul le message diffère : la copie Docker ajoute `(letter/underscore first, then letters/digits/underscore)`. Les deux scripts valident la même chose (utilisateur, base et schéma PostgreSQL : `configure-m30-docker-services.ps1:301-303`, `configure-runtime-settings.ps1:274-275`). Aucun test ni gate ne cite ce message (`git grep "must be a safe identifier"`).
- **`Invoke-NativeChecked` de `scripts/release/`** (`build-windows-distribution.ps1:43-59`, `publish-windows-release.ps1:32-46`) : corps identiques sauf la mise en forme du bloc `finally` (`build-windows-distribution.ps1:55-57` sur trois lignes, `publish-windows-release.ps1:44` sur une). Différence accidentelle.

### Homonymes divergents du périmètre (D5), non gardés

`Assert-Equal`, `Assert-Throws` (`scripts/release/test-*.ps1`), `Assert-True` (trois autres corps dans `scripts/install/verify-*.ps1`), `Fail-Or-Warn`, `Find-EmbeddedClaudeCli`, `Invoke-DockerWorkflow`, `Invoke-MinosVersion`, `Invoke-NativeChecked` (`scripts/ci/qualify-docker-upgrade.ps1:32` contre la paire de release), `New-FakeMcpCli`, `Read-KeyValueFile`, `Resolve-CommandPath`, `Write-BackendConfiguration`, `Write-Utf8Json`. Un seul est une divergence de comportement entre scripts livrés : `Find-EmbeddedClaudeCli` lit `%APPDATA%` par `Get-UserFolderPath` dans `detect-mcp-clients.ps1:129-140`, et par `[Environment]::GetFolderPath` dans `configure-mcp-clients.ps1:520-531`.

### Hors du périmètre gardé

- **Scripts de jalon** : `Resolve-Python` (8 corps), `Get-Head`, `Assert-CleanWorktree`, `Invoke-PythonGate`, `Invoke-SemanticDisabled`, etc. dans `scripts/m22/` à `scripts/m27/run-final.ps1` et `scripts/m24/bootstrap-windows-toolchains.ps1`, **gelés par assertion** (ADR 0043 ; lus par `check-java-ast-provider-consistency.py:50`, `check-polyglot-provider-consistency.py:110-111,377`, `check-hosted-control-plane-consistency.py:161-162`) ; `scripts/m29/run-s3.ps1` à `run-s7.ps1`, lus par les tests Java `minos-app/src/test/java/com/minos/packaging/M29*ContractTest.java`. `scripts/m15/run-final.ps1` est un **rejeu manuel** (`.github/workflows/historical-qualification.yml:147`, `workflow_dispatch`) ; `scripts/m14/validate-local.ps1` est appelé par `scripts/m15/capture-baseline.ps1:228` et lu par `scripts/lib/test_minos_exit_code.py:133`. `Read-Backend` est identique entre `scripts/install/verify-mcp-backend-lifecycle.ps1:21` et `scripts/m29/run-s7.ps1:55` (gelé).
- **Ressources de modules** : `Decode`, `Read-List`, `Read-Plan`, `Read-Environment` des lanceurs Windows (`minos-intellij/.../windows-cli-job-owner-v1.ps1`, goldens de `minos-runtime-local`) sont déjà comparés par `scripts/quality/check-job-object-launcher-parity.py` et les goldens de `WindowsContainmentScriptTest` ; `Resolve-CSharpCompiler` du runner `scip-java-windows-runner.ps1` est du code de module.
- **Python** : `sha256` (`scripts/quality/check-reproducible-build.py:36`, `scripts/release/check-supply-chain.py:23`, `scripts/release/create-release-manifest.py:35`) et `sha256_of` (`scripts/quality/check-tools-manifest.py:69`, `scripts/release/build-embedded-tools.py:50`), 6 lignes chacune ; `read` (5 lignes) identique dans 9 gates de cohérence.

### Ce qui est livré (relu)

- **Distribution Windows** : `scripts/release/build-windows-distribution.ps1:323-332` copie `prod-mcp-release.ps1`, `mcp-lifecycle.ps1`, `configure-docker-mcp.ps1`, `configure-m30-docker-services.ps1` ; `:333-347` copie dix scripts de `scripts/install/` dans `integration\` (dont `configure-runtime-settings.ps1`, `configure-mcp-clients.ps1`, `detect-mcp-clients.ps1`) ; `:349-350` copie `install-windows.ps1` en `install.ps1`. `prod-mcp.ps1`, `scripts/windows/` et `scripts/lib/` ne sont pas copiés.
- **Contrôle du paquet** : `scripts/install/update-installation.ps1:255-284` (`Assert-Package`) liste les fichiers exigés d'une installation.
- **Installateur** : `packaging/windows/minos-installer.iss.template:54-55` embarque `update-installation.ps1` et `detect-mcp-clients.ps1` ; `:436-442` extrait `detect-mcp-clients.ps1` **seul** dans `{tmp}` et l'y exécute.
- **Images Docker** : `docker/Dockerfile.mcp` et `docker/Dockerfile.mcp.release` ne copient aucun script PowerShell.
- **Bibliothèques dot-sourcées existantes, jamais livrées** : `scripts/windows/MinosWindows.ps1` (`docker/scripts/prod-mcp.ps1:16`, `scripts/intellij/run-minos.ps1:10`, scripts m14 et m15) et `scripts/lib/MinosExitCode.ps1` (`scripts/m14/validate-local.ps1:14`, `scripts/m29/run-s5.ps1:17`). Aucun script livré ne dot-source de fichier.

### Ce qui lit ces fonctions

- `scripts/remediation/check-post-mne.py:197-202` exige la chaîne `Read-BoundedUtf8` dans les deux scripts.
- `scripts/install/test_secret_file_acl.py:28-29,133` extrait par l'analyseur PowerShell les quatre fonctions d'ACL des deux scripts, et `Read-BoundedUtf8` du script Docker ; Windows seulement (`:136`), exécuté par le job Windows de `pr-ci.yml:343`.
- `Test-CeilingOverride` et `Get-PreservedCeilingOverrides` ne sont exercés par aucun test automatique.
- `find_ci_wiring_violations` vérifie que le gate et son auto-test (`CI_STEPS`, `check-compose-limits.py:74`, `check-image-pins.py:60`) sont des étapes sans `if:` du job `invariants`. `check-ci-wiring.py` (règle 2) n'exige que les auto-tests et ne regarde pas les `if:`. Les auto-tests sont `test_check_compose_limits.py` et `test_check_image_pins.py` (`pr-ci.yml:88-97`) : aucun des deux gates n'a d'option `--self-test`. `check-image-pins.py:37-38` importe `gate_cli` ; `check-compose-limits.py` ne l'importe pas.

### Aucun gate existant ne compare des fonctions de scripts

`git grep -l -i -E 'parity|identi|twin|jumel' -- scripts` : seul `check-job-object-launcher-parity.py` compare des copies, celles d'un lanceur entier (fragments contenus mot pour mot, zones divergentes en liste fermée à plafond). Il sert de modèle : racine `--root` par `gate_cli`, normalisation `normalized()` (fins de ligne, espaces de fin), refus d'un fichier absent, liste qui ne fait que se resserrer.

### Écarts avec l'action proposée par l'audit

1. **`Require-Identifier` n'est pas identique**, mais la différence n'est que le message : aligner ne change aucune valeur acceptée.
2. **`Invoke-NativeChecked` : six corps, pas trois variantes** ; la paire de `scripts/release/` ne diffère que par la mise en forme ; les trois autres sont dans `scripts/ci/` et des scripts de jalon.
3. **`Resolve-Python` n'a qu'un consommateur hors des scripts de jalon** (`build-windows-distribution.ps1:35`) : rien à mutualiser.
4. **L'audit a manqué quatre paires de sécurité identiques et livrées** (les fonctions d'ACL du fichier de secret, à côté de `Read-BoundedUtf8`) et trois groupes hors sécurité (`Get-UserFolderPath`, `Test-Case`, `Assert-True`).
5. **Une bibliothèque dot-sourcée ne tient pas avec les listes réelles** : voir D1.
6. **Python** : un module partagé ne pose aucun problème de livraison, les gates s'exécutant depuis le dépôt.

## Goals / Non-Goals

**Goals** : une divergence entre copies listées échoue dans le job `invariants` ; une nouvelle copie identique dans un script du périmètre ne passe pas sans être déclarée ; la vérification du câblage CI des gates n'est écrite qu'une fois ; la distribution Windows garde la même liste de fichiers.

**Non-Goals** : une bibliothèque PowerShell livrée ; toucher aux scripts de jalon, à `scripts/history/` ou aux ressources des modules ; aligner les homonymes divergents ; réduire le nombre de copies.

## Decisions

### D1. Garde d'identité plutôt que bibliothèque livrée — **option A, décidée par le propriétaire le 2026-10-24**

**Option A (recommandée) : gate d'identité.** Les copies restent dans leurs scripts ; le gate de D5 les compare.

**Option B : bibliothèque dot-sourcée livrée.** Confrontée aux listes réelles :
- `detect-mcp-clients.ps1` s'exécute seul depuis `{tmp}` (`minos-installer.iss.template:436-442`) : une bibliothèque devrait aussi être embarquée par l'installateur et extraite à côté de lui.
- Les paires de sécurité relient `docker\scripts\` et `integration\` : la bibliothèque devrait être copiée deux fois ou résolue par un chemin relatif propre à chaque disposition (dépôt, distribution, installation).
- Elle s'ajouterait à `build-windows-distribution.ps1:323-350`, à `Assert-Package` (`update-installation.ps1:255-284`) et au modèle Inno Setup, puis serait re-prouvée par la mise à jour transactionnelle (`verify-windows-upgrade-transaction.ps1`) et un candidat de release.
- `check-post-mne.py:197-202` et `test_secret_file_acl.py:28-29,133` seraient réécrits ; ce dernier note que les scripts ne peuvent pas être dot-sourcés (ils configurent une installation au chargement).
- `mcp-lifecycle.ps1` (livré) ne dot-source rien ; `prod-mcp.ps1` (non livré) dot-source déjà `scripts/windows/MinosWindows.ps1`, qui n'est pas livré.

Décision : **A** (propriétaire, 2026-10-24) ; aucune bibliothèque livrée. B ajoute un fichier dont l'absence casse l'installateur et la mise à jour, pour des fonctions de 3 à 24 lignes.

D2, D3, D4 (câblage CI dans `scripts/quality/gate_cli.py`) et D5 sont retenues telles qu'écrites ci-dessous (propriétaire, 2026-10-24).

### D2. `Require-Identifier` : aligner

Les deux corps valident la même chose ; la copie de `configure-runtime-settings.ps1` prend le message plus explicite de la copie Docker. Aucune valeur acceptée ou refusée ne change. La paire entre dans la liste gardée.

### D3. `Invoke-NativeChecked` : aligner la paire de `scripts/release/` seulement

Le bloc `finally` de `publish-windows-release.ps1:44` prend la forme de `build-windows-distribution.ps1:55-57`, sans changement de sens. La paire entre dans la liste gardée. `scripts/ci/qualify-docker-upgrade.ps1:32` (qui ne relâche pas `$ErrorActionPreference`) et les scripts de jalon ne sont pas touchés.

### D4. Vérification du câblage CI dans `gate_cli.py`

`find_ci_wiring_violations(root, steps)` rejoint `scripts/quality/gate_cli.py` ; la liste des étapes devient un paramètre. `check-image-pins.py` l'importe déjà ; `check-compose-limits.py` l'importe à son tour, de la même façon (dossier du script en tête du chemin d'import), sans repli : un import impossible fait échouer le gate. Pas de nouveau fichier, donc rien à déclarer pour `check-milestone-artifact-references.py`. Le gate de D5 s'en sert pour son propre câblage. La docstring de `gate_cli.py` nomme les auto-tests qui l'exercent. Alternative : un module `scripts/quality/ci_wiring.py` séparé ; non retenue, `gate_cli.py` étant déjà le squelette commun des gates de ce dossier (`.claude/rules/gates-python.md`).

### D5. Contrat du gate d'identité

`scripts/quality/check-twin-script-functions.py`, auto-test `scripts/quality/test_check_twin_script_functions.py`, sur le modèle de `check-job-object-launcher-parity.py` (`gate_cli`, `--root`, bibliothèque standard seule).

- **Périmètre** : les `.ps1` suivis de `docker/scripts/`, `scripts/install/`, `scripts/release/`, `scripts/ci/` (39 fichiers au HEAD), parcourus par `gate_cli.repository_files`. Les dossiers de jalon, `scripts/history/` et les ressources des modules en sont exclus.
- **Liste fermée `TWINS`** : (fonction, fichiers). Liste initiale : les 10 entrées PowerShell du tableau ci-dessus, plus `Require-Identifier` et `Invoke-NativeChecked` (paire de release), soit 12 entrées et 25 copies.
- **Extraction** : `function <Nom>` en colonne 0, jusqu'à la première ligne `}` en colonne 0, ou la ligne même si elle ferme ses accolades. Normalisation : fins de ligne et espaces de fin de ligne seulement. Lecture en UTF-8 ; les `.ps1` ne sont jamais réécrits.
- **Échecs** : deux copies listées diffèrent (le message nomme la fonction et les fichiers) ; un fichier listé est introuvable ; une fonction listée est absente, non bornable ou définie plusieurs fois dans un fichier ; une entrée nomme moins de deux fichiers (entrée morte) ; deux fichiers du périmètre portent la même fonction au même corps sans entrée qui les couvre (copie non déclarée) ; le gate ou son auto-test n'est pas une étape sans condition du job `invariants` (D4).
- **Non gardé** : un homonyme de corps différent non listé (inventaire ci-dessus) ; une définition imbriquée ; ce que font les fonctions (les tests de chaque script le prouvent).
- **Sortie** : en cas de succès, le nombre d'entrées et de copies comparées ; chemins écrits avec `/`.

## Recouvrements avec les autres changements actifs

- **`retirer-les-jalons-des-identifiants`** (AUD-QUA-08) : son gate `check-milestone-identifiers.py` refuse un jalon dans les noms de fichiers et les déclarations de production ; sa recommandation D2 vise `*/src/main/**`. `configure-m30-docker-services.ps1` porte `m30` et figure dans `TWINS`. Si ce changement étend son périmètre aux scripts et renomme le fichier, le renommage met à jour `TWINS` dans le même commit ; sinon le gate d'identité échoue (fichier introuvable). Ni l'un ni l'autre ne renomme les scripts de jalon.
- **`fixer-la-langue-des-commentaires`** (AUD-QUA-11) : exclut les scripts ; aucun fichier commun. Les deux changements ajoutent un gate au job `invariants` et une section à `docs/developer/quality-gates.md` : le second fusionné rejoue `check-ci-wiring.py`, `check-single-execution.py` et `check-audit-remediation-v2.py`.
- **Changements dont des tâches restent ouvertes sur des fichiers voisins** (`attester-et-signer-la-release-windows`, `durcir-les-workflows-github`, `publier-le-plugin-avec-la-release`, `reserver-minos-ollama-au-runtime-docker`, `lire-le-registre-sans-ecrire`) : tâches manuelles ou sur les fichiers compose ; aucune ne touche les fonctions listées.

## Direction des dépendances

Sans objet : aucun module du reactor touché (ADR 0022).

## Qualification des capacités

Aucune capacité produit nouvelle. Le gate est un contrôle de dépôt : il prouve l'identité textuelle des copies listées, pas leur comportement. La distribution Windows garde son contenu (option A).

## Windows et Linux

- Le gate et son auto-test sont du Python de texte, bibliothèque standard seule : exécutés par le job `invariants` (`ubuntu-24.04`, `pr-ci.yml:51`) et sur poste Windows, avec la même sortie.
- Les deux scripts modifiés (D2, D3) s'exécutent sous Windows PowerShell 5.1 (installateur, release) et PowerShell 7 : preuve par l'analyseur des deux moteurs, `test_secret_file_acl.py` (job Windows de `pr-ci.yml`) et le check `Verify (windows-2022)`. Les `.ps1` restent en CRLF.

## Risks / Trade-offs

- L'option A garde les copies : elle rend une divergence visible, elle ne la supprime pas.
- L'extraction en colonne 0 dépend de la mise en forme. Une définition qu'elle ne sait pas borner fait échouer le gate ; elle ne passe jamais pour identique.
- La détection des copies non déclarées ne voit que les corps identiques : une copie modifiée dès sa création passe pour un homonyme divergent.
- `Find-EmbeddedClaudeCli` diverge entre détection et configuration : relevé ici, non corrigé.
