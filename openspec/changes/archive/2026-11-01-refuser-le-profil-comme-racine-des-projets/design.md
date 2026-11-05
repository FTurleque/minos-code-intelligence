# Design

## Context

Constat d'un essai réel du 31 octobre 2026 : après une désinstallation avec purge puis une réinstallation, `MINOS_PROJECTS_DIR` désignait le profil de l'utilisateur. `TPL` = `packaging/windows/minos-installer.iss.template`, `SW` = `scripts/install/switch-mcp-backend.ps1`, `ZIP` = `scripts/install/install-windows.ps1`. HEAD analysé : `a44d9ebf` (`develop`).

## État vérifié au HEAD

- `TPL:943` : `DockerProjectsPage.Values[0] := ExpandConstant('{param:DOCKERPROJECTSROOT|' + ReadInstallerValue('ProjectsRoot', ExpandConstant('{%USERPROFILE}')) + '}');` — sans valeur enregistrée ni paramètre, le profil est proposé.
- `TPL:1053-1056` : la page Docker ne vérifie que `DirExists(DockerProjectsPage.Values[0])`.
- `TPL:495` : la valeur acceptée est enregistrée dans `HKCU` (`ProjectsRoot`) et reproposée à la mise à jour suivante.
- `TPL:643-644` : la racine est passée telle quelle au commutateur (`-ProjectsRoot`). En mode silencieux, aucune page n'est validée : le commutateur est la seule garde.
- `SW:66-68` puis `SW:98-103` : `GetFullPath`, racine obligatoire, dossier existant. Aucune autre condition.
- `ZIP:82-89` : mêmes contrôles, avant l'extraction ; `ZIP:166-181` appelle ensuite le commutateur dans une transaction qui restaure l'installation précédente en cas d'échec.
- `docker/scripts/mcp-lifecycle.ps1:560` et `docker/scripts/prod-mcp.ps1:274` écrivent `MINOS_PROJECTS_DIR` à partir de la racine reçue, sans contrôle.
- `scripts/install/verify-installer-template.ps1:50-53` : commentaire et message citent `{%USERPROFILE}` comme valeur de repli.
- Règle voisine : `minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java:158-164` (`refuseSharedLocation`) refuse le profil, ses parents et une racine de volume pour un dossier privé ; `scripts/install/update-installation.ps1:143-163` refuse une racine d'installation égale à une racine de volume, au profil, à `%LOCALAPPDATA%`, `%APPDATA%`, etc.
- `minos-application/src/main/java/com/minos/application/ProjectPathMappingService.java:40-49` : une correspondance de chemins différente de celle enregistrée n'est jamais remplacée (`CONFLICT`).

Non vérifié au moment de la spécification : comportement d'Inno Setup en mode silencieux avec une racine refusée (prouvé par la tâche 3.3).

## Décisions

### D1 — Valeur par défaut : aucune, et refus d'une racine dangereuse

Trois options ont été pesées :

- **A. Champ vide et obligatoire.** Supprime la cause de l'incident, mais un utilisateur peut toujours parcourir jusqu'au profil, et une valeur enregistrée avant le correctif reste proposée.
- **B. Dossier dédié par défaut** (par exemple `%USERPROFILE%\source\repos`). Il n'existe pas forcément ; le créer reviendrait à monter un dossier vide et à masquer le vrai choix. Rejeté.
- **C. Refus d'une racine dangereuse.** Couvre la saisie, `/DOCKERPROJECTSROOT`, la valeur enregistrée et l'installation ZIP.

**Retenu : A + C.** Le champ est vide sans réglage précédent ; toute racine dangereuse est refusée à chaque porte d'entrée. L'option A seule ne protège pas ; l'option C seule laisserait l'assistant proposer une valeur qui sera refusée.

### D2 — Ce qui est dangereux

Une racine est refusée quand elle est :

1. une racine de volume (`C:\`, `\\serveur\partage`) ;
2. le profil de l'utilisateur ou l'un de ses parents ;
3. égale à, parente de, ou contenue dans un dossier protégé : `%LOCALAPPDATA%`, `%APPDATA%`, et les dossiers de MINOS connus de l'appelant (installation, données, runtime Docker, données Docker).

Un sous-dossier du profil hors AppData (par exemple `%USERPROFILE%\source\repos`) est accepté : c'est l'emplacement courant des projets. La comparaison est faite sur le chemin absolu normalisé, sans séparateur final, sans distinction de casse (`OrdinalIgnoreCase` en PowerShell, `AnsiLowercase` en Pascal). Un profil introuvable rend le contrôle impossible : la racine est alors refusée (fail-closed).

### D3 — Où vit la règle

- **PowerShell** : une fonction `Get-UnsafeDockerProjectsRootReason`, sans effet de bord, qui reçoit la racine, le profil et la liste des dossiers protégés et rend la raison du refus (chaîne vide si acceptée). Elle est définie à l'identique dans `SW` et `ZIP` : `ZIP` doit refuser **avant** d'extraire et de remplacer l'installation, alors que `SW` n'est disponible qu'une fois le paquet extrait. Un fichier partagé ajouterait un script livré à trois listes explicites (`build-windows-distribution.ps1`, `update-installation.ps1`, `ZIP`) ; la copie est déclarée dans `TWINS` de `scripts/quality/check-twin-script-functions.py`, qui exige deux corps identiques, et le test de comportement exécute les deux définitions extraites par l'analyseur PowerShell. Un strict sous-dossier du dossier temporaire n'est pas refusé pour se trouver dans un dossier protégé qui contient lui-même le dossier temporaire, c'est-à-dire `%LOCALAPPDATA%` (les qualifications `verify-mcp-backend-lifecycle.ps1` et `scripts/m29/run-s7.ps1` y placent leurs racines) ; il l'est s'il contient un dossier protégé ou se trouve dans un dossier de MINOS placé sous le dossier temporaire.
- **Inno Setup** : une fonction Pascal `UnsafeDockerProjectsRootReason` appelée par `NextButtonClick` sur la page Docker, avec le message en français. Le mode silencieux et `/DOCKERPROJECTSROOT` sont gardés par `SW`, qui refuse avant toute écriture ; l'installeur signale alors l'échec de configuration du backend et conserve le backend précédent (`TPL:651-670`).
- Le message ne contient aucun chemin (`.claude/rules/securite.md`, point 7) ; il nomme la catégorie refusée et le remède (choisir un dossier dédié aux projets).

### D4 — Qualification

- Fonction PowerShell : **qualifiée** par un test de comportement sous Windows PowerShell 5.1 (`@EnabledOnOs(WINDOWS)`, exécuté par `Verify (windows-2022)`), sur des chemins construits par le test, sans dépendre du profil de l'hôte.
- Page Inno Setup : **partielle** — contrat textuel (`verify-installer-template.ps1` et test Java) et compilation de l'installeur par `build-local-windows-candidate.ps1` ; le parcours de l'assistant est vérifié à la main (tâche 3.3), aucun test automatique ne pilote l'interface Inno.

## Risques

- Une installation qui monte déjà le profil garde son runtime tant que l'utilisateur n'en choisit pas un autre ; changer de racine bute sur la correspondance de chemins enregistrée (hors périmètre, remède documenté : désinstaller avec purge puis réinstaller).
- Un utilisateur qui gardait ses projets à la racine d'un volume de données (`D:\`) doit désormais choisir un sous-dossier : rupture voulue, message actionnable.
