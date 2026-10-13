# Design

## Context

Sprint 6 de l'audit du 10 octobre 2026 : **AUD-QUA-01**. HEAD analysé : `949e1569` (`develop`). Prérequis livré : AUD-TST-13 (gate de portée du plugin). `RES` = `minos-runtime-local/src/main/resources/com/minos/runtime/local`, `PS1` = `minos-intellij/src/main/resources/com/minos/intellij/protocol/windows-cli-job-owner-v1.ps1`.

Ordre dans le sprint 6 : indépendant des autres changements du sprint.

## État vérifié au HEAD

### AUD-QUA-01 — confirmé, impact inversé

- `PS1` : 350 lignes ; `:16` `public static class MinosJobObjectOwnerV1` ; `:146` `Run(string[] command, string[] environment, string workingDirectory, bool rawCmd)` ; `:248` `BuildRawCmdCommandLine` ; `:345` lecture de `command.mode`. Chargée par `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosStrongProcessLauncher.java:314`.
- Moteur : `RES/windows-job-object-owner-v1.ps1.template` (137 lignes) inclut 11 des 14 fragments de `RES/windows-fragments/` par des directives `#minos-include:` ; l'assemblage est fait par `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsContainmentScript.java`.
- `RES/windows-fragments/command-line-builder.ps1frag:13-16` : `isPreQuotedForCmdShell`.
- Un assembleur Python existe déjà : `scripts/remediation/windows_launcher.py` (`assemble`, `:25`), utilisé par `check-minos-01.py`, `check-mnd.py` et `check-post-mne.py`.
- `scripts/ci/plugin-gate.py:25-36` : `PLUGIN_PATHS` ne contient ni `windows-fragments/` ni le gabarit.
- Aucun script de `scripts/`, aucun workflow et aucun test de `minos-runtime-local` ne cite `cli-job-owner`.

**Mesuré pendant l'analyse** (lecture seule, script moteur assemblé par `windows_launcher.assemble`, fins de ligne et espaces de fin normalisés) :

| Comparaison | Lignes propres au premier | Lignes propres au second |
|---|---|---|
| copie du plugin / moteur assemblé | 38 | 42 |
| copie du plugin / fichier « golden » du moteur | 38 | 38 |
| moteur assemblé / fichier « golden » | 4 | 0 |

Fragments présents à l'identique dans la copie du plugin (6) : `command-line-quoting`, `createprocess-import`, `job-configuration-verify`, `job-limit-structs`, `kernel32-imports`, `startupinfo-struct`. Fragments divergents (5) : `script-preamble` (`using System.IO` en plus), `createprocess-call` (mise en page et un commentaire), `resume-and-verify` (texte d'un message), `command-line-builder` (fond : mode `cmd-c` strict côté plugin, détection `isPreQuotedForCmdShell` côté moteur), `plan-parsing` (endroit de la suppression du plan, texte d'un message). Dans le corps du gabarit : signature de `Run`, commentaires, textes de messages, lecture de `command.mode`. La répartition exacte de chaque ligne entre fragment et gabarit est à établir en tâche 1.1.

**Non vérifié** : aucun des deux scripts n'a été exécuté ; l'équivalence de comportement des zones « de pure forme » est déduite de la lecture du diff.

### Écarts avec l'action proposée par l'audit

1. **Le « 70 lignes » de l'audit compare la copie au fichier « golden »**, qui est la base **pré-remédiation** (`minos-runtime-local/src/test/java/com/minos/runtime/local/sandbox/WindowsContainmentScriptTest.java:17-21`), pas au script que le moteur écrit sur disque. L'écart réel est de 38 + 42 lignes.
2. **Le sens de la divergence est inversé.** Le plugin porte le durcissement MINOS-AUD-C04 ; le moteur garde l'ancienne détection. « Un correctif du moteur n'atteint pas le plugin » se lit ici dans l'autre sens.
3. **Générer la ressource au build Gradle couple le plugin à l'arbre Maven** : le plugin est un client externe, construit seul, et sa release se construit depuis `minos-intellij/`. Cela ne règle pas non plus la divergence de fond, qu'il faudrait d'abord trancher (un seul `command-line-builder`).
4. **Un changement de fragment ne déclenche pas la CI du plugin** (`PLUGIN_PATHS`) : avec une génération au build, un fragment modifié changerait le plugin livré sans exécuter ses tests.
5. **Les fragments du moteur ne peuvent pas être retouchés pour « aligner »** : `WindowsContainmentScriptTest` n'accepte, par rapport à la base qualifiée, que la suppression du plan. Tout alignement de forme se fait dans la copie du plugin.

## Goals / Non-Goals

**Goals** : toute divergence entre les deux lanceurs est soit refusée, soit nommée et justifiée ; un changement d'un côté est vu de l'autre à la PR ; la liste des différences ne peut que se resserrer.

**Non-Goals** : unifier les deux scripts ; modifier le confinement du moteur ; changer le format du plan de propriété.

## Decisions

### D1. Gate de parité plutôt que génération — **décision en attente du propriétaire**

**Option A (recommandée) : gate statique** `scripts/quality/check-job-object-launcher-parity.py` (nouveau ; bibliothèque standard, squelette `scripts/quality/gate_cli.py`, option `--root`). Il assemble le script du moteur par la fonction existante de `scripts/remediation/windows_launcher.py` (pas de troisième assembleur), puis : (1) exige que chaque fragment de la liste « identiques » figure tel quel dans `PS1` ; (2) calcule le diff ligne à ligne du reste et exige que chaque zone divergente appartienne à la liste fermée des différences assumées (D2). Garde le plugin autonome et rend la divergence visible sans décider de son fond.

**Option B : génération au build Gradle** depuis les fragments et un gabarit propre au plugin. Supprime la copie, mais couple le build et la release du plugin au reactor (écart 3), impose d'étendre `PLUGIN_PATHS` et de trancher d'abord D3, et demande un ADR.

### D2. Liste fermée des différences assumées

Chaque entrée : un nom, le fragment ou la zone du gabarit concernés, une justification, un maximum de lignes divergentes. Entrées attendues après la tâche 2.3 : mode `cmd-c` explicite (`BuildRawCmdCommandLine`, signature de `Run`, lecture de `command.mode`, `using System.IO`) ; messages qui nomment « CLI » au lieu de « provider » ; suppression du plan faite avant le démarrage du CLI plutôt que dans la lecture du plan. C'est un cliquet : une entrée sans objet échoue, un maximum ne se relève pas sans justification écrite.

### D3. Sort de la divergence de fond — **décision en attente du propriétaire**

Recommandation : **la nommer, ne pas la trancher ici**. Elle touche le confinement des providers, qualifié contre une base figée ; l'aligner sur le mode strict du plugin est un changement de `confinement-code-non-fiable`, à ouvrir à part avec sa requalification Windows. Tant qu'elle existe, le gate l'affiche à chaque exécution. Alternative : l'aligner dans ce changement (périmètre et risque nettement plus larges).

### D4. Écarts de pure forme retirés du côté du plugin

`createprocess-call` (mise en page, commentaire) et les commentaires absents sont repris du moteur dans `PS1`, pour que ces fragments passent dans la liste « identiques ». Les messages restent des différences assumées : ils nomment le processus possédé. Aucun test ni gate n'affirme leur texte au HEAD.

### D5. `PLUGIN_PATHS` étendu

Ajout de `RES/windows-fragments/`, de `RES/windows-job-object-owner-v1.ps1.template` et du nouveau gate. Avec l'option A, le gate d'`invariants` voit déjà la divergence ; l'extension fait en plus tourner les tests Windows du lanceur du plugin sur la PR qui modifie un fragment, y compris quand l'écart tombe dans une zone assumée.

### D6. Auto-test

`scripts/quality/test_check_job_object_launcher_parity.py` (nouveau), sur des copies temporaires des vrais fichiers : fragment commun modifié d'un seul côté (refusé) ; écart hors liste (refusé) ; entrée de liste sans objet (refusée) ; maximum dépassé (refusé) ; dépôt réel (accepté) ; gabarit ou fragment manquant (refusé, jamais « rien à comparer »). Câblé dans `invariants`.

## Direction des dépendances

Aucune arête de code. Le gate lit des ressources de `minos-runtime-local` et de `minos-intellij` ; le plugin ne dépend toujours d'aucun artefact `com.minos:*` ni, avec l'option A, d'aucun fichier du reactor à la construction.

## Qualification des capacités

- Parité des blocs communs du lanceur Job Object : **qualifiée** par contrôle statique.
- Équivalence de comportement des deux lanceurs : **partielle** — la construction de la ligne de commande diffère (D3) et le gate ne prouve que l'égalité de texte, pas l'exécution.

## Windows et Linux

Le lanceur ne s'exécute que sous Windows. Le gate ne dépend d'aucun système (lecture UTF-8, fins de ligne normalisées, chemins en `/`) et doit donner le même résultat sur Ubuntu et sur un poste Windows. Les tests d'exécution du lanceur du plugin restent sur le job Windows de `intellij-plugin.yml`.

## Risks / Trade-offs

- Un gate de texte peut être contourné en élargissant la liste : chaque entrée est nominative, bornée, justifiée et relue en PR.
- Reprendre la forme du moteur dans `PS1` modifie une ressource de sécurité du plugin : diff relu ligne à ligne, tests Windows du lanceur rejoués (job Windows), aucune ligne de fond touchée.
- Importer `windows_launcher.py` depuis `scripts/quality/` lie deux dossiers de scripts : préférable à un troisième assembleur ; si l'import gêne, le module est déplacé avec ses trois appelants dans la même tâche.
- Ajouter un auto-test à `invariants` peut casser `check-single-execution.py` et `check-audit-remediation-v2.py` : les rejouer.
