# 0043 — Politique de retrait des scripts et workflows de jalon

Status: Accepted.

Complète l'audit [`AUDIT-2026-09.md`](../audit/AUDIT-2026-09.md) (constat G3) et le chantier suivi dans [`CI-HYGIENE-SUIVI.md`](../audit/CI-HYGIENE-SUIVI.md).

## Contexte

Chaque jalon (`M0`…`M29`) et chaque campagne de remédiation a laissé ses scripts sous `scripts/mNN/` ou `scripts/remediation/`, sans qu'aucune règle n'ait jamais dit quand un artefact de jalon terminé est archivé ou supprimé. Un inventaire exhaustif (101 fichiers sous `scripts/m0`…`scripts/m29` et `scripts/remediation`, plus `scripts/intellij/check-m21-parity.py`) a établi, pour chaque fichier, s'il est réellement exécuté par la CI, seulement lu/asserté par un gate qui l'est, seulement documenté, ou sans aucune référence — voir `CI-HYGIENE-SUIVI.md` § 7 pour la table complète et les preuves fichier:ligne.

Deux mécanismes rendaient l'inventaire naïf ("grep-t-on son nom quelque part ?") trompeur :

1. **Exécution indirecte.** `scripts/remediation/check-post-mne.py` exécute par sous-processus neuf gates de jalon (`ACTIVE_MILESTONE_GATES`, M21 à M28) à chaque PR, sans qu'aucun fichier YAML ne les nomme directement. Ces neuf scripts sont donc vivants alors qu'ils portent un numéro de jalon dans leur nom et leur répertoire — exactement ce que cette politique interdit pour un gate permanent.
2. **Gel par assertion statique.** Plusieurs de ces neuf gates lisent le texte d'un script frère (`run-final.ps1`, un runner `*-e2e.py`, un script de bootstrap) et exigent qu'il existe avec un contenu précis, sans jamais l'exécuter. Ce script frère n'est donc ni mort, ni vivant au sens normal : le retirer casserait le gate qui l'assert, sans qu'aucune exécution ne le protège jamais réellement. Cette politique nomme ce troisième état pour qu'il ne soit plus confondu avec les deux autres.

## Décision

### 1. Trois états possibles pour un artefact de jalon

- **Permanent** — applique un invariant toujours pertinent, réellement exécuté par la CI (directement ou par sous-processus depuis un autre gate vivant). Il rejoint `scripts/quality/`, `scripts/architecture/` ou `scripts/docs/` sous un nom qui ne cite plus de numéro de jalon, et son ancien chemin est mis à jour partout où il est invoqué (workflow, script appelant, documentation).
- **Gelé par assertion** — non exécuté, mais son existence et son contenu exact sont une précondition d'un gate permanent vivant (cas 2 ci-dessus). Il **reste en place** tant que le gate qui l'assert n'a pas été réécrit pour ne plus en dépendre : le déplacer ou le supprimer sans toucher au gate casserait une CI qui passait. Il est marqué comme tel dans l'inventaire, avec le gate qui le gèle, pour qu'un futur chantier explicite (hors du périmètre CI-hygiène, qui ne change aucun comportement de gate) puisse décider de le réécrire en assertion sur le comportement réel plutôt que sur un fichier jamais exécuté.
- **Archivé** — ni exécuté (directement ou indirectement), ni gelé par assertion. Sa seule valeur est historique. Déplacé sous `scripts/history/<jalon>/` (l'historique Git du chemin d'origine fait foi pour les diffs antérieurs) ou supprimé quand même `scripts/history/` n'apporterait rien (README purement descriptif d'un outillage lui-même archivé). Toute référence documentaire restante (ADR, roadmap, doc développeur) est mise à jour pour pointer vers le nouvel emplacement ou explicitement marquée comme référence historique.

### 2. Règles pour la suite

- Un gate **permanent** ne porte jamais de numéro de jalon (`mNN`) dans son nom de fichier ni dans le répertoire qui le contient.
- Un artefact de jalon est retiré (archivé ou reclassé permanent) dès que **son jalon est clos** et que **son invariant est couvert par un gate permanent** — les deux conditions ensemble, jamais l'une sans l'autre. Un jalon clos dont l'invariant n'est couvert par rien ne perd pas son gate ; il gagne un renommage vers `scripts/quality|architecture|docs`.
- Ajouter un nouveau script de jalon impose d'écrire, dans le même commit, sa condition de retrait (quel gate permanent le remplacera, ou pourquoi il restera un outil de rejeu manuel — `workflow_dispatch` — au lieu d'un gate de PR).
- Un outil de **rejeu manuel** délibéré (par exemple une qualification historique déclenchée à la demande via `workflow_dispatch`, comme `historical-qualification.yml`) n'est pas un artefact orphelin : c'est un choix explicite documenté. Il garde son numéro de jalon si le rejeu est spécifique à ce jalon, mais doit être atteignable depuis un point d'entrée CI référencé (pas seulement depuis la documentation).

### 3. Un gate s'ancre sur un invariant, pas sur une phrase

Trois gates existants ont échoué avant tout commit pendant ce chantier (`check-semantic.py`/`check-remote-distributed.py`/`check-runtime-dynamic.py`/`check-hosted.py` lors de leur propre renommage, puis `check-audit-remediation-v2.py` deux fois — suppression de `post-228-hardening.yml`, puis reformulation de `docs/STATUS.md` ; détail dans `CI-HYGIENE-SUIVI.md` § 8, J1/J3/J6). Ce n'est pas trois accidents mais une seule fragilité, répétée : un gate qui vérifie qu'une **chaîne littérale exacte** apparaît dans un fichier cible casse à chaque reformulation légitime de ce fichier, même quand l'invariant qu'il protège reste respecté.

Règle pour tout gate écrit ou modifié à partir de maintenant :

- **S'ancrer sur le comportement ou la structure, pas sur la formulation.** Préférer un test qui exécute le code et observe son effet, ou qui parse une structure nommée (comme `read_job_block` dans `check-audit-remediation-v2.py`, qui extrait un bloc YAML par son nom plutôt que de chercher une sous-chaîne dans le fichier entier), plutôt qu'une phrase censée apparaître telle quelle.
- **Échouer si la cible est introuvable, jamais réussir par défaut.** Un gate qui traiterait un fichier ou un bloc absent comme « rien à vérifier » transformerait une suppression en trou noir silencieux ; cette politique l'interdit pour tout gate présent ou futur.

### 4. Garde-fou

Un contrôle (`scripts/quality/check-milestone-artifact-references.py` ou équivalent) échoue si un script versionné sous `scripts/` n'est référencé ni par un workflow (exécution directe ou via un `if:`/chemin filtré), ni par un autre script exécuté par la CI (exécution ou assertion statique), ni par la documentation, ni par une liste explicite d'archives assumées. Il tourne une seule fois dans le pipeline consolidé (issu de C1) et a son propre auto-test (au moins : un script fictif non référencé fait échouer le contrôle ; un script référencé par chacune des voies ci-dessus le laisse passer).

## Conséquences

- Neuf gates de jalon (M21–M28) changent de chemin et perdent leur numéro de jalon dans leur nom ; `check-post-mne.py` et les pages de documentation qui les citent sont mis à jour dans le même commit que le déplacement.
- Un ensemble de scripts gelés par assertion (M22–M27 : `run-final.ps1`/`.sh`, les runners `*-e2e.py`, le bootstrap M24) reste en place, documenté comme tel — leur réécriture éventuelle est un chantier distinct, non un report indéfini : elle est nommée explicitement pour ne pas redevenir invisible.
- Un ensemble de scripts sans aucune référence vivante (M0 entier, la phase S1–S6 de M15, M16 entier, M17, la branche morte de M21, `check-m21-parity.py`, deux orphelins de `scripts/remediation`) est archivé sous `scripts/history/`.
- `scripts/m19/run-final.ps1` et `scripts/m20/run-final.ps1` sont retirés en même temps que les jobs `m19-final-windows`/`m20-final-windows` (C1), puisque ces jobs étaient leur unique appelant.

## Limites connues

- Les scripts « gelés par assertion » ne sont pas résolus par cette politique, seulement nommés et rendus visibles. Le risque qu'ils décrivent une réalité qui a dérivé sans que personne ne le remarque reste ouvert.
- `M26RuntimeFixture.java` et `M27HostedFixture.java` ne sont compilés/exécutés que par leurs runners `*-e2e.py` respectifs (eux-mêmes gelés par assertion, jamais exécutés) : ils suivent le même sort que leur runner, pas celui d'un fichier Java ordinaire du reactor Maven (ils ne sont d'ailleurs dans aucun module Maven).
