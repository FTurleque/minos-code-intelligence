# Proposal

## Why

Le constat **AUD-ARC-01** de l'audit du 10 octobre 2026 relève que les surfaces utilisent directement des types de `minos-engine` au lieu de passer par des cas d'usage de `minos-application` (ADR 0058 § 3, ADR 0057 § 6). La règle A11 tient cette dette. Le lot 1 (`faire-consommer-l-indexation-aux-surfaces`, PR #548) en a retiré l'indexation. Son design inventorie cinq lots suivants. Le lot 2, le **diagnostic de l'hôte**, attendait SH-08 (port d'ACL de plateforme), fusionné le 2026-10-25 (PR #564).

Au HEAD `47c02b89`, `minos doctor` (`minos-cli/src/main/java/com/minos/cli/command/DoctorCommand.java`) utilise trois types du moteur, et c'est le seul fichier de la CLI qui les utilise :

- `io.PrivateLocalStorage` : `privacyOf`, ligne 250, pour la confidentialité des dossiers de `MINOS_HOME` ;
- `runtime.WorkerSandboxProbe` : lignes 10, 95 et 101-103, pour les bacs à sable de l'hôte ;
- `runtime.HostCommandLocator` : ligne 116, rendu par la racine de composition et chaîné sans être nommé. Seule la vue bytecode d'A11 le voit.

## What Changes

- **Service applicatif `com.minos.application.HostDiagnosticsService`**. Il rend le diagnostic de l'hôte en types JDK et applicatifs :
  - la confidentialité des emplacements de `MINOS_HOME` (libellé → niveau) ;
  - la sélection des bacs à sable, dans un résumé `WorkerSandboxDiagnosis` ;
  - la localisation des commandes de l'hôte.

  Il est construit à partir de la racine de composition (`defaults()`), ou à partir de la sonde et du localisateur injectés, ce qui permet de le tester dans `minos-application`.
- **`DoctorCommand`** consomme ce service. La CLI garde sa vue `WorkerSandboxReport` et son point d'injection pour les tests (décision D1 du lot 1). La liste des emplacements contrôlés passe dans le service.
- **Cliquets A11** :
  - garde Python, liste de la CLI : `io.PrivateLocalStorage` et `runtime.WorkerSandboxProbe` sont retirés (**43 → 41 types**) ;
  - table bytecode de `ModuleArchitectureTest`, CLI : ces deux types et `runtime.HostCommandLocator` sont retirés (**39 → 36 types**).
- **Sortie inchangée à l'octet** : `cli-doctor.golden`, clés JSON, verdict et code de sortie.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : une exigence est ajoutée (le diagnostic de l'hôte est consommé par un service applicatif).

## Hors périmètre

- Les autres lots d'AUD-ARC-01 (phase 3 : noyau transverse, contrôle hébergé, Git et résultats de requête, export NEXUS).
- La forme de la sortie de `minos doctor`, qui reste identique, et toute nouvelle information de diagnostic.
- `PrivateLocalStorage.privacyOf` : SH-08 ne l'a pas changé, car il lit l'ACL par Java et ne lance rien. Le lot l'appelle tel quel depuis l'application.

## Impact

- **Modules** : `minos-application` (service, résumé, tests) et `minos-cli` (`DoctorCommand`, tests de commande adaptés à la nouvelle source). Aucune dépendance Maven ajoutée ; `minos-application` dépend déjà du moteur.
- **Surfaces publiques** : aucune rupture. La sortie de `minos doctor` est inchangée, ce que garde `A2SurfaceCharacterizationTest` (`cli-doctor.golden`).
- **ADR** : aucun nouvel ADR. Note de mise en œuvre de l'ADR 0058 § 3 (lot 2).
- **Gates** : `check-module-boundaries.py` (table A11 de la CLI), `test_check_module_boundaries.py` (un cas lit `io.PrivateLocalStorage` dans la liste réelle de la CLI), `ModuleArchitectureTest` (table bytecode). Portées JaCoCo : `global` et `public-api` ne bougent pas, et le nouveau code de `minos-application` est couvert par ses tests (SonarCloud, 80 %).
- **Plateformes** : la sonde des bacs à sable diffère selon le système (AppContainer sous Windows, bubblewrap sous Linux), mais le service ne fait que la relayer. Les tests de commande tournent sur les deux systèmes.
- **Constat** : AUD-ARC-01, lot 2 sur 6.
