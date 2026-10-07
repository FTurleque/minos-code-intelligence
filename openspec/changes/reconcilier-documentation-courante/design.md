# Design

## Context

Changement de documentation seule, sans delta de spécification (voir `proposal.md`). Les gates
documentaires (`scripts/docs/check-current-docs.py`, `scripts/docs/product-facts.py`,
`scripts/quality/check-milestone-artifact-references.py`) affirment des chaînes littérales sur
`README.md`, `docs/STATUS.md`, `docs/ROADMAP.md` (version et SHA de la release 1.0.1, « 10 assets »,
« #98 », « 5 paires », absence de marqueurs « NON PUBLIÉE » …) et la ligne `Version : x.y.z` de
`docs/architecture/README.md` (comparée à `<revision>` du POM). Elles passent au HEAD et ne
couvrent pas les écarts de l'annexe G : réconcilier est donc une relecture humaine contre les
sources, que les gates ne remplacent pas.

## Goals / Non-Goals

**Goals:**

- Aligner chaque affirmation corrigée sur sa source de vérité, citée dans la tâche.
- Ne casser aucune chaîne que les trois gates affirment.

**Non-Goals:**

- Modifier un ADR, un statut d'ADR, le code, le POM, un workflow ou un script.
- Changer le contenu d'une décision : on corrige des faits d'état, pas des décisions.

## Decisions

### D1 — Critère de vérité : le code, le POM, le workflow

Toute divergence se corrige dans le document, jamais en infléchissant le code ou la
configuration pour « rejoindre » la documentation. Sources : `pom.xml` (`<revision>`,
`<module>`, enforcer), `.mvn/wrapper/maven-wrapper.properties`, `.github/workflows/*.yml`, le
code cité, `git ls-remote --tags origin`, et les ADR **tels qu'ils sont écrits** (l'ADR-0042
fait foi pour la composition root : `minos-bootstrap`, `minos-app` restant l'assemblage final
distribué). Quand une source est ambiguë ou en conflit avec une décision, la divergence devient
une question ouverte plutôt qu'une correction.

### D2 — Les ADR sont intouchables ici

Les constats qui supposent d'éditer un fichier ADR (bandeaux, statuts, amendements, module cité)
ne sont pas planifiés : ils sont proposés en Questions ouvertes. Les fichiers qui ne sont pas des
ADR mais les référencent (index `docs/adr/README.md` pour ses notes en prose, arc42 09 et 11,
SYNTHESE) sont corrigés quand la correction ne change aucun statut.

### D3 — Dates d'en-tête : deux dates explicites

`docs/STATUS.md` et `docs/ROADMAP.md` portent aujourd'hui une seule date (31 août) alors qu'ils
contiennent des sections d'octobre. Solution retenue : conserver la date « état produit » telle
que revérifiée et ajouter une seconde date « planification » (les sections d'octobre existantes
sont déjà datées 2026-10-05 et 4 octobre 2026). La date de revérification est celle du jour de
la réconciliation (à renseigner au moment de l'exécution de la tâche, après relecture). Pour
`docs/architecture/README.md`, la ligne `Version : …` reste intacte (gate `product-facts.py`) ;
seule la ligne « Dernière mise à jour » change.

Alternative écartée : une date unique mise à jour sans relecture : elle déclarerait exact un
document qui ne l'a pas été.

### D4 — arc42/09 : régénérer l'index depuis l'index des ADR

`docs/architecture/arc42/09-decisions.md` annonce ne pas reproduire les ADR (« Index des ADR
existants ») : on lui ajoute les 20 lignes manquantes (0038 à 0057) et on aligne les dates sur
l'en-tête de chaque fichier ADR (0008 : 22 juillet, 0009 : 24 juillet, 0020 : 24 juillet,
0025 : 2026-07-26 … à relire fichier par fichier). Les statuts des lignes nouvelles copient
l'en-tête du fichier ADR. La ligne ADR-0036 reste dans son état actuel tant que Q1 n'est pas
tranchée.

Alternative écartée : réduire arc42/09 à un renvoi vers l'index des ADR : décision de forme du
propriétaire de l'arc42, non nécessaire pour corriger les faits.

### D5 — Pas de nouveau contenu

Les ADR manquants et les amendements ne sont pas créés ici (Q4) ; les sept diagrammes le sont, sur décision de l'utilisateur (Q7, tâche 8.2).
Les corrections n'ajoutent que des faits relus à la source.

## Risks / Trade-offs

- [Une correction rend un gate rouge (chaîne littérale supprimée)] → rejouer les trois gates
  après chaque tâche, pas seulement à la fin ; ne pas toucher aux lignes qui portent le SHA, l'URL
  et « 10 assets » de la release 1.0.1.
- [Corriger une affirmation sans relire sa source] → chaque tâche liste la source et la commande
  de relecture ; la tâche n'est pas cochée sans cette relecture.
- [Des fichiers voisins répètent la même affirmation] → pour chaque fait corrigé, `grep` le même
  fait dans `docs/` hors `docs/history/` et `docs/audit/annexes/` (G-11 s'est ainsi révélé plus
  large que la fiche).
- [Le nombre de modules ou de jobs évolue pendant les travaux] → relire le POM et `pr-ci.yml`
  au moment de l'édition, ne pas recopier les chiffres de ce document.

## Migration Plan

Aucun déploiement. Une PR thématique `docs/` vers `develop` (workflow Git du dépôt). Retour
arrière : revert de la PR.

## Open Questions

Aucune ne bloque une tâche ; chaque point attend une décision de l'utilisateur, seul à pouvoir
changer le statut d'un ADR.

- **Q1 — G-02, statut de l'ADR-0036.** **Résolue le 2026-10-07 : alignement sur le fichier validé par l'utilisateur** (tâche 6.2). Le fichier ADR dit « Accepted » (accepté le 2026-08-09,
  commit `cd87f402`) ; l'index `docs/adr/README.md`, `arc42/09-decisions.md` et
  `arc42/11-risques-dette.md:3` disent « Proposed ». Proposition : aligner ces trois documents sur
  le fichier, qui est la source de vérité de l'index. À valider par l'utilisateur.
- **Q2 — G-03, G-05, G-06, bandeaux et amendements d'ADR.** Ajouter « partiellement remplacé par
  ADR-0037 » à l'ADR-0021 ; « amendé par 0042, 0044 (et 0055) » à l'ADR-0022 (dont la liste de
  modules est périmée) ; un renvoi vers l'ADR-0041 dans l'ADR-0033 ; l'amendement de 0038 par 0041
  dans l'index. Édition de fichiers ADR (« Accepted ») : non planifiée.
- **Q3 — G-04, parité Docker de l'ADR-0037.** L'ADR et l'index disent « parité non acquise /
  pending » ; `STATUS.md` et `risks/register.md` (R-01) disent résolu par M29. Le code seul ne
  prouve pas la parité. À trancher par le propriétaire ; sinon annoter l'ADR sans réécrire sa
  décision.
- **Q4 — G-07, G-08, ADR à proposer.** ADR-0031 §2 (« loopback endpoints only ») est contredit par
  l'endpoint Docker managé `minos-ollama` ; le backend PostgreSQL/pgvector n'a aucun ADR alors que
  l'ADR-0025 en exige un avant intégration runtime. Un amendement/ADR (statut Proposed) pour
  chacun est à décider ; rien n'est rédigé ici.
- **Q5 — G-11, politique de toolchain.** Le wrapper est en Maven 3.10.0, accepté par la plage
  enforcer `[3.9,4.0)`. `docs/TOOLCHAIN_POLICY.md` (« Maven : 3.9.x »), les ADR-0004, 0005 et
  0022 (« 3.9.x », « 3.9.16 » de référence M0), `docs/developer/README.md:124`,
  `docs/user/troubleshooting.md:30`, arc42/02 CT-2 et arc42/04 disent « 3.9.x ». La politique
  exige une qualification explicite d'un changement de baseline : 3.10.0 est-il une baseline
  qualifiée (alors reformuler en « 3.x dans [3.9,4.0) ») ou une dérive Dependabot à rétablir ?
  Les tâches ne corrigent que les **faits d'état** (STATUS, ROADMAP, `docker-runtime.md`). À noter :
  l'image Docker et le paquet d'outils embarqués restent en Maven 3.9.16
  (`docker/Dockerfile.mcp.release`, `embedded-tools.json`) : c'est une autre chose que le wrapper.
- **Q6 — G-17, module cité par l'ADR-0039.** « Écarts (f) » place `IndexingResumePlanner` dans
  `minos-application` ; la classe est dans `minos-engine` (`com.minos.orchestration`). L'ADR-0023
  ne mentionne pas le format V3 (ADR-0046). Édition d'ADR : non planifiée.
- **Q7 — G-18, diagrammes absents.** `docs/architecture/diagrams/README.md` référence sept
  fichiers qui n'ont jamais existé (`c4-context.md`, `c4-container.md`, `c4-component-application.md`,
  `seq-indexation-nominale.md`, `seq-erreur-provider.md`, `seq-mcp-startup.md`,
  `deployment-native.md`). Retirer les lignes ou créer les diagrammes (nouveau contenu) ?
  **Résolue le 2026-10-07 : l'utilisateur veut les diagrammes** (tâche 8.2). Ils sont relus contre le code, pas recopiés d'arc42 : voir « Écarts constatés ».
- **Q8 — G-20, index des ADR.** 29 titres sur 57 de l'index diffèrent du H1 du fichier et le
  vocabulaire de statuts de l'index (« Partially superseded », « Accepted — à implémenter »)
  dépasse celui que définit son en-tête. Quelle source est canonique, faut-il étendre le
  vocabulaire ?
- **Q9 — `openspec/config.yaml`.** Son contexte écrit « minos-app est le composition root », ce que
  l'ADR-0042 contredit (même écart que G-16). Fichier hors périmètre : à corriger par
  l'utilisateur si souhaité, sans quoi les prochains artefacts OpenSpec reprendront l'erreur.

## Écarts constatés à l'implémentation (2026-10-07)

Aucun ne change le périmètre ; ils précisent D1 à D4. Le détail est dans « Évidence d'implémentation » de `tasks.md`.

- **D3, date de revérification.** Fixée au 7 octobre 2026 (jour de l'exécution) pour `STATUS.md`, `ROADMAP.md` et `docs/architecture/README.md` ; elle ne couvre que les faits relus pendant ce changement.
- **D4, dates sans en-tête.** Les ADR 0026 et 0030 n'ont aucune date dans leur en-tête : les dates du tableau (2026-07-27, 2026-07-28) sont conservées, non vérifiables. 0038, 0040 et 0043 n'en ont pas non plus : la colonne porte « — » au lieu d'une date déduite de l'historique Git. Les statuts des lignes ajoutées sont une abréviation du texte d'en-tête, sans interprétation.
- **Tâche 3.1, ADR 0039.** La ligne est retirée du tableau « Travaux ouverts en conception » et remplacée par une phrase sous le tableau (acceptée et implémentée), plutôt que simplement supprimée, pour que l'ADR reste citée dans la feuille de route.
- **Tâche 7.1, commande de vérification.** `git log --grep "pull request #333"` ne renvoyait rien dans ce clone ; l'état de la PR a été établi par `gh pr view 333` et `git merge-base --is-ancestor bc1d3421 HEAD`.
- **Tâche 8.1, lien en plus.** `PROMPT-SPRINT-2-3-RESTANT.md` portait un troisième lien mort (`docs/adr/`, relatif invalide) ; il pointe désormais le fichier de l'ADR 0041.
- **Tâche 8.3, arc42.** Sur demande de l'utilisateur, les diagrammes d'arc42 sont remplacés par ceux de `docs/architecture/diagrams/` (copie, pas renvoi : arc42 reste lisible seul) ; le fichier `diagrams/` fait foi en cas d'écart. Le diagramme Docker de § 7.3 n'est pas relu.
- **Constat hors périmètre, non corrigé.** `arc42/05-vue-blocs.md` (section `minos-storage-postgresql`) écrit que le module dépend de `minos-application` ; son `pom.xml` et `module-dependencies.md` montrent `minos-domain`, `minos-engine` et `minos-storage-local` seulement. À corriger dans un changement distinct si l'utilisateur le décide.
