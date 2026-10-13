# Proposal

## Why

**AUD-QUA-01** (moyenne, sprint 6 de l'audit du 10 octobre 2026 ; confirmé, mais l'impact décrit est inversé). Le code de confinement par Job Object existe en **deux exemplaires maintenus à la main** :

- côté moteur, `minos-runtime-local` l'assemble depuis un gabarit (`windows-job-object-owner-v1.ps1.template`) et des fragments partagés (`windows-fragments/`, 14 fichiers dont 11 inclus par ce gabarit) ;
- côté plugin, `minos-intellij/src/main/resources/com/minos/intellij/protocol/windows-cli-job-owner-v1.ps1` (350 lignes) en est une copie.

**Aucun gate ni test ne compare les deux.** Mesure au HEAD (script moteur réellement assemblé contre la copie du plugin, fins de ligne normalisées) : **38 lignes propres au plugin, 42 propres au moteur**. Six fragments sont à l'identique dans le plugin ; cinq divergent.

La divergence va **dans le sens inverse** de celui de l'audit : c'est le plugin qui porte le durcissement MINOS-AUD-C04 (mode `cmd-c` explicite, forme vérifiée strictement, `BuildRawCmdCommandLine`), et le moteur qui garde la détection `isPreQuotedForCmdShell`. Le risque reste celui que l'audit nomme : un correctif de sécurité appliqué d'un côté n'atteint pas l'autre et rien ne le signale. De plus, un changement de fragment ne déclenche pas la CI du plugin : `scripts/ci/plugin-gate.py:25-36` ne liste pas `windows-fragments/`.

## What Changes

- **Gate de parité** : un contrôle statique assemble le script du moteur comme le fait le runtime et exige que les blocs communs soient identiques dans la copie du plugin.
- **Différences assumées nommées** : chaque écart voulu figure dans une liste fermée, avec sa justification et une borne ; un écart hors liste, ou une entrée devenue sans objet, fait échouer le gate.
- **Écarts de pure forme supprimés** dans la copie du plugin (mise en page d'un appel, commentaires), pour que la liste ne contienne que des différences de fond.
- **La CI du plugin se déclenche** quand un fragment ou le gabarit du moteur change.
- **Auto-test** du gate, câblé dans `invariants`.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `client-intellij` : trois exigences ajoutées (blocs communs du lanceur Job Object identiques à ceux du moteur ; différences assumées nommées et bornées ; CI du plugin déclenchée par un changement de fragment).

## Hors périmètre

- **Reporter le durcissement MINOS-AUD-C04 dans le moteur** (remplacer `isPreQuotedForCmdShell`) : c'est une modification du confinement des providers, qualifié sur une base figée ; elle est signalée ici comme différence assumée et renvoyée à une décision propre (`design.md`, D3).
- Générer la ressource du plugin au build Gradle : alternative non retenue par défaut (D1).
- Modifier les fragments ou le gabarit du moteur : `WindowsContainmentScriptTest` les fige contre la base qualifiée.
- Le lanceur AppContainer : le plugin n'en a pas de copie.
- La couverture du plugin (AUD-TST-05) et son verrou Gradle (AUD-DEP-04).

## Impact

- **Modules du reactor** : aucun code modifié ; `minos-runtime-local` est **lu** par le gate. `minos-intellij` : la ressource `windows-cli-job-owner-v1.ps1` (forme seulement).
- **Fichiers** : `scripts/quality/check-job-object-launcher-parity.py` (nouveau) et `scripts/quality/test_check_job_object_launcher_parity.py` (nouveau) ; `scripts/ci/plugin-gate.py` et `scripts/ci/test_plugin_gate.py` ; `.github/workflows/pr-ci.yml` (câblage) ; `docs/developer/quality-gates.md`.
- **Surfaces publiques** : IntelliJ, sans changement de comportement (le format du plan de propriété et le mode `command.mode` sont inchangés). CLI, API Java, MCP, NEXUS : aucune.
- **ADR** : aucun. Si la génération au build est retenue (D1, alternative), un ADR est à écrire (prochain numéro libre de `docs/adr/README.md`) : elle couple le build du plugin à l'arbre du moteur.
- **Gates à rejouer** : `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/remediation/check-audit-remediation-v2.py`, `python scripts/ci/test_plugin_gate.py`, `python scripts/remediation/check-minos-01.py`, `python scripts/remediation/check-mnd.py`, `python scripts/remediation/check-post-mne.py` (les trois assemblent déjà le lanceur par `scripts/remediation/windows_launcher.py`), `python scripts/remediation/check-post228-hardening.py`.
- **Plateformes** : le script ne s'exécute que sous Windows ; le gate est du texte et tourne sur Ubuntu 24.04 (`invariants`) comme sur un poste Windows. Les tests du lanceur du plugin tournent sur le job Windows Server 2022 de `intellij-plugin.yml`.
