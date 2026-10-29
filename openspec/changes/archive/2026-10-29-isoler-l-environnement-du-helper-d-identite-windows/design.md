# Design

## Context

Constat de revue de la PR #599. `WEP` = `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsExecutionPathIdentityProvider.java`, `PPE` = `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProviderProcessEnvironment.java`. HEAD analysé : `e365a9c1` (tête de la PR #599, sur `develop` `810dcfb2`).

## État vérifié au HEAD

- `WEP` `startHelper` construit un `ProcessBuilder` dont l'environnement est celui de la JVM, puis y ajoute les deux cibles : aucun filtre.
- `PPE:98-115` : `TRUSTED_LAUNCHER_KEYS`, 16 noms non secrets (`PATH`, `PATHEXT`, `SystemRoot`, `windir`, `SystemDrive`, `ComSpec`, `TEMP`, `TMP`, emplacements de profil, `USERNAME`, `USERDOMAIN`, `COMPUTERNAME`, `PSModuleAnalysisCachePath`) ; `PSModulePath` n'y figure pas.
- `PPE` `applyForTrustedLauncher` : package-private, utilisée par `ProcessIndexerExecutor` pour les lanceurs du bac à sable. Les variables déclarées passent par le motif de refus, qui écarte tout nom `MINOS_*`.
- `windows-path-identity-v1.ps1:3-4` lit les deux cibles comme des chaînes, sans évaluation.

## Mesure (poste Windows 10, 28 octobre 2026)

Helper lancé directement, environnement vidé puis rempli d'un seul jeu, trois exécutions par jeu :

| Environnement | Résultat |
|---|---|
| aucune variable | PowerShell meurt à l'initialisation : exit -65536, erreur 8009001d, 140 à 590 ms, avec message |
| `SystemRoot` | exit 1 (`Add-Type`) |
| `SystemRoot`, `TEMP`, `TMP` | identités rendues |
| jeu des lanceurs de confiance | identités rendues |

L'échec de la PR #599 (environ 20 ms, aucune sortie) n'est pas celui d'un environnement vide : la mesure n'en établit pas la cause.

## Décisions

- **D1 — jeu des lanceurs de confiance plutôt qu'un jeu à trois variables.** Il est déjà mesuré sur un runner CI et ne contient aucun secret. Il porte `PSModuleAnalysisCachePath`, dont l'absence coûte environ 23 s au démarrage de PowerShell sur un runner, au-delà du délai de 15 s du helper. Une seule liste reste la source de vérité.
- **D2 — cibles posées après le filtre.** Les deux noms `MINOS_*` sont choisis par MINOS et portent un chemin ; les passer comme variables déclarées les ferait refuser par le motif.
- **D3 — `applyForTrustedLauncher` publique.** `sandbox` dépend déjà de `process` (`CommandLocator`) ; la classe reste hors de `RUNTIME_LOCAL_EXPORTED_TYPES`, donc innommable hors du module (A10).

## Capacité

Qualifiée sous Windows 10 (poste) ; Windows Server 2022 à confirmer par la CI de la PR. Profils itinérants ou de domaine non mesurés (marge du jeu des lanceurs). Sans objet sous Linux.
