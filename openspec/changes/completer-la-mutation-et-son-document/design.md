# Design

## Context

Sprint 6 de l'audit du 10 octobre 2026 : **AUD-TST-11**. HEAD analysé : `949e1569` (`develop`). `DOC` = `docs/quality/code-audit-couverture.md`, `WF` = `.github/workflows/code-audit.yml`.

Ordre dans le sprint 6 : indépendant des autres changements ; la correction documentaire (section 1 des tâches) peut passer à tout moment.

## État vérifié au HEAD

### AUD-TST-11 — confirmé, une contradiction de plus

- `DOC:122` (§ 3.3, ligne « Exécuté ») : « 6 modules complets, `minos-runtime-local` partiel, `minos-storage-postgresql` interrompu, 6 modules non exécutés ».
- `DOC:229` (§ 5.2) : « 11 modules sur 14 analysés (`minos-runtime-local` partiellement) ».
- `DOC:288` (§ 7) : « PIT n'a analysé que 3 modules sur 14 ».
- Tableau du § 5.2 (`DOC:231-247`) : 10 modules avec résultats, `minos-runtime-local` avec 656 timeouts ou erreurs, `minos-bootstrap` et `minos-app` « pas de rapport », `minos-cli` « rapport vide » (`DOC:243`). `DOC:252` : 560 `RUN_ERROR` non analysés. `DOC:265` : plugin « bloqué ». Le § 7.3 dit « terminé pour 10 modules », cohérent avec le tableau.
- `WF:3-6` : `workflow_dispatch` seul. `WF:16-21` : PIT sur **un** module choisi, avec `-am` (les modules amont sont aussi mutés). `WF:112-115` : job `mutation` sur `ubuntu-24.04`. `WF:230-231` : job du plugin (tests, SpotBugs, PIT ; commande `:252`).
- `scripts/quality/audit-report-summary.py:37` : `TIMED_OUT`, `MEMORY_ERROR` et `RUN_ERROR` sont comptés à part des mutants tués ; son en-tête (`:11-15`) annonce une sortie en échec quand un module compilé n'a pas de rapport ou que le rapport est vide.
- Le changement actif `etendre-audit-outille-a-tout-le-perimetre` écarte de son périmètre le fait de rendre PIT bloquant (`openspec/changes/etendre-audit-outille-a-tout-le-perimetre/proposal.md:29`).

**Non vérifié** : l'état réel de PIT sur GitHub (aucune exécution consultée) ; que le job du plugin de `code-audit.yml` ait déjà tourné ; le comportement de `audit-report-summary.py` (lu, non exécuté) ; les documents hors `docs/quality/` qui reprennent « 3 sur 14 » n'ont été que repérés (`docs/audit/2026-10-10/findings.md`, `docs/audit/2026-10-10/sprints.md`, une archive).

### Écarts avec l'action proposée par l'audit

1. **« Corriger le § 7 » ne suffit pas** : le § 3.3 (`DOC:122`) porte un troisième décompte, que l'audit ne cite pas. La cause est la répétition d'un fait chiffré ; la règle documentaire du dépôt (« un fait chiffré s'écrit une seule fois puis se référence ») donne la correction (D1).
2. **Les exécutions sur GitHub sont des actions sortantes** : seul le propriétaire les lance. Elles sont des tâches **(manuelles)**, et le changement reste « implémenté, preuves d'exécution en attente » tant qu'elles ne sont pas faites.
3. **Un runner Linux ne rejoue pas les 560 mutants du poste Windows à l'identique** : le code AppContainer et Job Object n'y a aucune couverture. Rejouer `minos-runtime-local` sur `ubuntu-24.04` donne un rapport complet **pour Linux** ; la part Windows reste à déclarer (D2).
4. **`-am` mute aussi les modules amont** : muter `minos-app` revient à muter presque tout le reactor en une exécution, d'une durée inconnue (2 h mesurées pour `minos-runtime-local` seul sur le poste).
5. **Le plancher PIT est hors périmètre d'un changement actif** qui en renvoie la décision : il ne se fixe pas ici (D3).

## Goals / Non-Goals

**Goals** : un seul décompte, vrai, dans le document ; un état nommé pour chacun des 14 modules et pour le plugin ; les exécutions manquantes faites et tracées ; la décision sur le plancher instruite.

**Non-Goals** : améliorer un score de mutation ; automatiser `code-audit.yml` ; fixer un seuil.

## Decisions

### D1. Un seul tableau d'état, les autres sections y renvoient

Le tableau des statuts du § 5.2 devient la source unique : un module par ligne, un statut parmi « exécuté », « partiel », « non exécuté », « bloqué », avec l'exécution et la raison. Les § 3.3 et § 7 perdent leur décompte et renvoient au § 5.2. La correction est datée dans le document. Elle se fait **sans attendre** les exécutions.

### D2. Exécutions manquantes et limite Linux

Ordre : `minos-cli`, `minos-bootstrap`, `minos-runtime-local`, puis `minos-app` (le plus long, écart 4), puis le job du plugin. Chaque résultat entre dans le tableau avec son identifiant d'exécution, le système et la date. Le document distingue « muté sous Linux » de « muté sous Windows » : les classes propres à Windows de `minos-runtime-local` restent **non analysées** tant qu'aucune machine Windows jetable n'a rejoué le module ; la mise en garde du § 7.2 (PIT d'un code qui modifie l'hôte : jamais sur un poste) est conservée.

### D3. Plancher PIT par module — **décision en attente du propriétaire**

Recommandation : **aucun plancher** tant que les 14 modules et le plugin n'ont pas un rapport complet ; ensuite, un plancher par module, **non bloquant** d'abord (contrôlé dans `code-audit.yml`, pas dans `pr-ci.yml`), décidé dans la suite de `etendre-audit-outille-a-tout-le-perimetre`. Alternatives : (a) plancher dès maintenant sur les 10 modules complets — fige des scores mesurés pendant un incident d'environnement (H23) et sur un seul système ; (b) ne jamais fixer de plancher et garder PIT comme outil d'audit à la demande.

### D4. Le décompte vient de la synthèse des rapports

Les lignes du tableau sont celles de `python scripts/quality/audit-report-summary.py pit --all` exécuté sur les rapports téléchargés ; un module sans rapport y apparaît en échec et s'écrit « non exécuté ». Aucun chiffre n'est recopié à la main d'une sortie de PIT (qui compte timeouts et erreurs comme tués).

## Direction des dépendances

Sans objet (aucun code).

## Qualification des capacités

- Mutation des 10 modules exécutés : **qualifiée** pour le système où elle a tourné.
- `minos-runtime-local` : **partielle** jusqu'au rejeu ; sa part Windows le reste après un rejeu sous Linux.
- `minos-cli`, `minos-bootstrap`, `minos-app`, plugin IntelliJ : **non supportée** au HEAD (aucun rapport), à requalifier après les tâches manuelles.

## Windows et Linux

Mesures du 8 octobre : poste Windows. Exécutions de `code-audit.yml` : Ubuntu 24.04. Le tableau porte une colonne « système ». Les branches Linux n'étaient pas couvertes sous Windows (limite déjà écrite au § 3.3) ; l'inverse vaut pour les exécutions à venir.

## Risks / Trade-offs

- Une exécution `-am` de `minos-app` peut dépasser le délai du job : la lancer en dernier, relever la durée, et dire si elle n'aboutit pas.
- Corriger le document avant les exécutions le laisse « incomplet » de façon exacte : c'est le but.
- Les documents de l'audit en cours qui citent « 3 sur 14 » sont corrigés par le suivi de l'audit, pas par ce changement ; les archives ne sont pas touchées.
- Sans plancher, une érosion de la force des tests reste invisible entre deux audits : risque accepté par D3, à revoir une fois les rapports complets.
