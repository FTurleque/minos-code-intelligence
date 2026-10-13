# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-09**. HEAD analysé : `949e1569` (`develop`).

## État vérifié au HEAD

### AUD-QUA-09 — confirmé, mais la plupart des copies sont gelées

Méthode : extraction de chaque définition `function <Nom>` jusqu'à son accolade fermante, fins de ligne ramenées à LF, espaces de fin de ligne retirés, comparaison des corps.

- **`Read-BoundedUtf8`** : identique (24 lignes) dans `docker/scripts/configure-m30-docker-services.ps1:51` et `scripts/install/configure-runtime-settings.ps1:134`.
- **`Require-Identifier`** : `docker/scripts/configure-m30-docker-services.ps1:45` et `scripts/install/configure-runtime-settings.ps1:55` ; 5 lignes chacune, **corps différents**.
- **`Test-CeilingOverride`** (12 lignes) et **`Get-PreservedCeilingOverrides`** (11 lignes) : identiques dans `docker/scripts/mcp-lifecycle.ps1:205,218` et `docker/scripts/prod-mcp.ps1:49,62`.
- **`find_ci_wiring_violations`** : `scripts/quality/check-compose-limits.py:162` et `scripts/quality/check-image-pins.py:192` (identité reprise de la contre-vérification, 37 lignes).
- **`Invoke-NativeChecked`** : 6 définitions hors `scripts/history/`, 6 corps distincts, de 5 à 32 lignes : `scripts/ci/qualify-docker-upgrade.ps1:32`, `scripts/m14/validate-local.ps1:16`, `scripts/m15/run-final.ps1:21`, `scripts/m24/bootstrap-windows-toolchains.ps1:57`, `scripts/release/build-windows-distribution.ps1:43`, `scripts/release/publish-windows-release.ps1:32`.
- **`Resolve-Python`** : 8 définitions hors `scripts/history/` — `scripts/release/build-windows-distribution.ps1:35` et sept `run-final.ps1` (`scripts/m15/`, `scripts/m22/` à `scripts/m27/`).
- **`scripts/lib/`** ne contient que `MinosExitCode.ps1`, son test et un fichier JSON ; ses deux consommateurs (`scripts/m14/validate-local.ps1:14`, `scripts/m29/run-s5.ps1:17`) ne sont pas livrés.

### Ce qui est livré (relu)

- `scripts/release/build-windows-distribution.ps1:323-342` copie un par un `mcp-lifecycle.ps1`, `configure-docker-mcp.ps1`, `configure-m30-docker-services.ps1`, puis dix scripts de `scripts/install/` dont `configure-runtime-settings.ps1`. `prod-mcp.ps1` n'y figure pas.
- `scripts/install/update-installation.ps1:249-263` liste les fichiers exigés d'une installation, dont `integration\configure-runtime-settings.ps1` et `docker\scripts\mcp-lifecycle.ps1`.
- `scripts/remediation/check-post-mne.py:197-201` exige la chaîne `Read-BoundedUtf8` dans les deux scripts ; `scripts/install/test_secret_file_acl.py:133` nomme `Read-BoundedUtf8` parmi les fonctions qu'il extrait du script Docker.

**Non vérifié** : aucun script ni gate exécuté ; le statut gelé ou vivant de `scripts/m14/validate-local.ps1` et de `scripts/m15/run-final.ps1` (aucune référence trouvée dans `.github/`, `scripts/quality/` ni `scripts/remediation/`, ce qui ne suffit pas à conclure) ; la nature de la différence entre les deux `Require-Identifier` (tâche 1.1) ; la liste complète des contrôles qui vérifient le contenu du paquet.

### Écarts avec l'action proposée par l'audit

1. **`Require-Identifier` n'est pas identique** dans les deux scripts : l'extraire tel quel changerait le comportement de l'un des deux.
2. **`Invoke-NativeChecked` : six corps, pas trois variantes**, avec des signatures différentes : pas de fonction commune sans réécrire chaque appelant, dont des scripts de jalon.
3. **`Resolve-Python` n'a qu'un consommateur vivant** hors des scripts gelés par assertion : l'extraction n'a pas d'objet.
4. **Une bibliothèque dot-sourcée casse la distribution si elle n'est pas livrée.** Il faut l'ajouter à la liste de copie (`build-windows-distribution.ps1:323-342`), à la liste des fichiers exigés (`update-installation.ps1:249-263`) et à tout contrôle de paquet, et résoudre son chemin dans les deux dispositions (dépôt et installation).
5. **Deux gates lisent le texte des scripts** : `check-post-mne.py:197-201` et `test_secret_file_acl.py:133` échoueraient si `Read-BoundedUtf8` quittait le script Docker.
6. **Sans risque** : un module Python partagé pour `find_ci_wiring_violations`, les deux gates étant lancés depuis le dépôt.

## Goals / Non-Goals

**Goals** : une divergence entre copies ne passe plus inaperçue ; le câblage CI des gates n'est écrit qu'une fois ; rien de la distribution ne change sans décision.

**Non-Goals** : une bibliothèque PowerShell générale ; toucher aux scripts gelés ; réduire le nombre de copies à tout prix.

## Decisions

### D1. Garde d'identité ou bibliothèque livrée — **décision en attente du propriétaire**

**Option A (recommandée) : garde d'identité.** Les copies restent où elles sont ; `scripts/quality/test_twin_script_functions.py` (nouveau) lit une liste déclarée (fonction, fichiers) et compare les corps normalisés. La distribution ne change pas, les deux gates de l'écart 5 restent verts, et une correction faite dans une seule copie fait échouer le job `invariants`.

**Option B : bibliothèque livrée.** `Read-BoundedUtf8`, `Test-CeilingOverride` et `Get-PreservedCeilingOverrides` passent dans un fichier de `scripts/lib/`, dot-sourcé par les scripts ; le fichier est ajouté aux deux listes de l'écart 4, `check-post-mne.py` et `test_secret_file_acl.py` sont réécrits dans la même tâche, et la résolution du chemin est testée dans une installation réelle. Une seule copie, au prix d'un fichier de plus dont l'absence casse l'installation et d'une preuve de release.

### D2. `Require-Identifier` : aligner ou renommer

Les deux corps sont relus (tâche 1.1). S'ils doivent valider la même chose, ils sont alignés sur le plus strict et la paire entre dans la liste gardée. S'ils valident des choses différentes, l'un des deux change de nom et la paire n'est pas gardée. Dans les deux cas, plus aucun nom ne désigne deux comportements.

### D3. `Invoke-NativeChecked` reste hors garde

Exception possible : la paire de `scripts/release/` (`build-windows-distribution.ps1:43`, `publish-windows-release.ps1:32`), si le relevé 1.1 montre que la différence est accidentelle. Elle est alors alignée et ajoutée à la liste ; sinon la raison de la différence est écrite dans `design.md`. Les scripts de `scripts/m14/`, `scripts/m15/` et `scripts/m24/` ne sont pas touchés.

### D4. Module Python partagé

`scripts/quality/_ci_wiring.py` (nouveau) porte `find_ci_wiring_violations`. Les deux gates l'importent (le dossier du script est en tête du chemin d'import quand un gate est lancé par `python scripts/quality/…`) et gardent leur `--self-test`. Un import impossible fait échouer le gate avec un code non nul : il n'existe pas de repli sur une copie locale.

### D5. Contrat de la garde

La liste des jumelles est déclarée dans le test. Normalisation : fins de ligne et espaces de fin de ligne seulement — ni l'indentation ni les commentaires ne sont effacés, pour qu'une copie reste une copie. Échec si deux corps diffèrent, si une fonction listée est absente d'un fichier, si un fichier listé est introuvable, ou si une paire ne nomme qu'un fichier. Le test lit les `.ps1` sans les réécrire : ils restent en CRLF.

## Direction des dépendances

Sans objet (aucun module du reactor).

## Qualification des capacités

Sans objet ; aucune capacité produit. La distribution Windows garde son contenu (option A).

## Windows et Linux

La garde est un test Python de texte : mêmes résultats sur les deux systèmes, sans PowerShell. La correction de `Require-Identifier` touche deux scripts PowerShell : `test_secret_file_acl.py` ne s'exécute que sous Windows, la preuve passe donc par le check `Verify (windows-2022)` ou un poste Windows.

## Risks / Trade-offs

- L'option A garde la duplication : elle la rend sûre, elle ne la supprime pas. C'est assumé pour des fonctions de sécurité livrées dans des fichiers qui doivent rester autonomes.
- Aligner `Require-Identifier` sur le plus strict peut refuser une valeur acceptée aujourd'hui par l'un des deux scripts : le relevé 1.1 doit dire lesquelles.
- Une liste de jumelles incomplète donne une fausse assurance : le test affiche la liste qu'il contrôle, et la tâche 1.1 recherche les autres définitions portant le même nom dans les scripts vivants.
