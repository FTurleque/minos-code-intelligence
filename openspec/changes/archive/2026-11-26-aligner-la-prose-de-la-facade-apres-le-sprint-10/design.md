# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement documentaire : un commentaire Javadoc, deux parenthèses des sections de mise en œuvre d'ARCH-0042, une cellule de l'index des ADR, un stéréotype de diagramme, une ligne de `docs/user/java-api.md` et un exemple d'une mémoire d'agent. Design réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

Règle de rédaction appliquée à chaque texte durable : il dit l'état en vigueur, au présent, sans récit du chantier ni « désormais », « auparavant », « reporté » (`.claude/rules/adr.md`, « Forme d'un ADR actif »). Une décision `Accepted` ne se réécrit pas : seules ses sections de mise en œuvre et l'index se complètent (`.claude/rules/documentation.md`, section ADR).

## État vérifié au HEAD

Relu au commit `75e10c681` (PR #819).

| Fait | Preuve |
|---|---|
| ARCH-0087 : « Statut : Accepted (décision du propriétaire du 2026-11-24) » ; Origine : trois changements, dont `retirer-la-racine-de-composition-de-la-facade` | `docs/architecture/adr/ARCH-0087-adaptateurs-embarques-et-facade-reduite.md:10-12` |
| ARCH-0087 « Mise en œuvre » : décisions 2.2 et 2.3 par `retirer-la-racine-de-composition-de-la-facade` | `ARCH-0087-…md:88-92` |
| Index des ADR, ligne ARCH-0087 : statut `Accepted`, Origine « Sprint 7 … (changements `cabler-…`, `injecter-…`) » | `docs/architecture/adr/README.md:92` |
| arc42 : ARCH-0087 `Accepted` | `docs/architecture/arc42/09-decisions.md:60` |
| Mémoire d'agent : exemple « groupe 4 … retrait de `compositionRoot()` et des 15 accesseurs, reporté par le propriétaire ; ARCH-0087 reste `Proposed` » ; plus loin, « ARCH-0087 `Accepted` » | `.claude/agent-memory/archgate-developer/project_audit_2026_11_15_clos.md:19-21`, `:41` |
| Le fichier de mémoire est suivi par Git | `git ls-files .claude/agent-memory` |
| Javadoc de classe : « Long-lived composition root for one MINOS home and one selected storage backend. » ; `compositionRoot()` est privée au package et documentée comme telle | `minos-application/src/main/java/com/minos/application/MinosApplication.java:66`, `:267-272` |
| ARCH-0042 § 8.1 : « l'application l'obtient par `MinosApplicationComposer.resumableRunMarkers(home)` (via `MinosApplication.compositionRoot()`) » ; au HEAD, la façade lit le champ `compositionRoot` dans son package | `docs/architecture/adr/ARCH-0042-racine-de-composition.md:332-333` ; `MinosApplication.java:409` |
| ARCH-0042 § 10 : « décision ARCH-0087 (Proposed) » | `ARCH-0042-…md:400-401` |
| ARCH-0042 est `Accepted` ; § 8 et § 10 sont des sections de mise en œuvre | `ARCH-0042-…md:10`, `:324`, `:398` |
| Diagramme de la façade : `<<composition root long-lived>>` | `docs/developer/public-surfaces.md:10-12` |
| `java-api.md`, rupture ARCH-0087 : `retentionService()` → « `new LocalProjectOperations(application)` (rétention des snapshots) ; la rétention suit aussi chaque indexation » | `docs/user/java-api.md:359` |
| `LocalProjectOperations` : méthodes publiques `addProject`, `listProjects`, `inventory`, `inspectProject`, `inspection`, `statusInspection`, `importScip`, `close` ; la rétention n'est appelée qu'après un import publié | `minos-application/src/main/java/com/minos/application/LocalProjectOperations.java:63-100`, `:140-142`, `:218` |
| L'indexation compacte après succès et après échec | `minos-application/src/main/java/com/minos/application/indexing/ProjectIndexingUseCase.java:359`, `:429` |
| La documentation utilisateur décrit déjà la rétention après import | `docs/user/autonomous-indexing.md:194-196` |
| Aucune autre surface n'expose une rétention de snapshots à la demande (les commandes `team retention-*` portent sur l'audit du mode hébergé) | `docs/user/team-hosted-mode.md:42-53` ; recherche dans `minos-cli/src/main` |
| Aucun gate ne cite les textes à modifier (`Long-lived`, `composition root long-lived`, `LocalProjectOperations(application)`, `via \`MinosApplication.compositionRoot()\``) ; `check-p0-p2.py` lit `public-surfaces.md` pour d'autres chaînes | recherche dans `scripts/` ; `scripts/remediation/check-p0-p2.py:164`, `:261-264` |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| ARCH-0042:401 qualifie ARCH-0087 de `Proposed` : site non cité par l'audit | `ARCH-0042-…md:401` | `ARCH-0087-…md:10` | D2 |
| `public-surfaces.md:12` décrit encore la façade comme racine de composition : site non cité par l'audit | `docs/developer/public-surfaces.md:12` | `MinosApplication.java:267-272` | D4 |
| L'audit cite les lignes 20-21 de la mémoire ; l'exemple commence ligne 19 | `findings.json`, AUD-ARC-15 | `project_audit_2026_11_15_clos.md:19-21` | D3 |
| L'exigence courante « La documentation courante ne cite aucune ligne du garde » traite `.claude/agent-memory/` comme figé ; l'audit en demande pourtant la correction | `openspec/specs/frontieres-de-modules/spec.md:1438-1449` | `findings.json`, AUD-ARC-15, action | D3 : corriger, sans en faire une exigence |
| L'action d'AUD-QUA-08 laisse le choix entre documenter et exposer une opération | `findings.json`, AUD-QUA-08 | — | D1 |
| `SUIVI.md:7` | `findings.json`, AUD-ARC-15 | brief du sprint 11 | hors périmètre (coordinateur) |

## Exigences couvertes

- frontieres-de-modules › La documentation courante de la façade décrit son état en vigueur — D2, D3, D4.
- frontieres-de-modules › La table de rupture de la façade ne promet aucun remplaçant absent — D1.

## Décisions

### D1 — Rétention : documenter l'absence de remplaçant à la demande [Décision du propriétaire attendue, recommandation : documenter]

Options :

- (a) écrire dans `java-api.md:359` : « aucun remplaçant à la demande : la rétention s'applique après chaque indexation et chaque import qui publie un snapshot (`LocalProjectOperations.importScip`) » ;
- (b) exposer une opération de rétention dans la façade (par exemple `LocalProjectOperations.compact(projectIdentifier)`).

Recommandation : (a). Aucun appelant connu, ni dans le reactor ni dans la documentation, ne compacte à la demande. La rétention automatique couvre le besoin décrit (`autonomous-indexing.md:194-196`). Une opération nouvelle serait une surface publique de plus, avec bail de projet, refus et tests, pour un cas sans demande. Si un hôte en exprime le besoin, (b) fera l'objet de son propre changement.

### D2 — ARCH-0042 : deux parenthèses des sections de mise en œuvre [Proposition, recommandation]

- § 8.1 (`:333`) : « (via `MinosApplication.compositionRoot()`) » devient « (lu par `MinosApplication` dans son package ; la racine n'est pas publique, [ARCH-0087](ARCH-0087-adaptateurs-embarques-et-facade-reduite.md) § 2) ».
- § 10 (`:401`) : « [ARCH-0087](…) (Proposed) » devient « [ARCH-0087](…) », sans statut écrit en dur. Le statut vit dans l'ADR et dans l'index ; le recopier crée la dérive constatée.

Ce sont des sections de mise en œuvre d'un ADR accepté : on les complète, on ne touche pas à la décision (option (c) de la racine dans `minos-bootstrap`). L'audit proposait une « note de renvoi » : la parenthèse réécrite au présent en tient lieu, sans phrase de récit.

### D3 — Mémoire d'agent : un exemple qui ne date pas [Proposition, recommandation]

Le fichier est suivi et relu par l'agent `archgate-developer` en début de session. La règle des lignes 19-21 reste vraie ; seul son exemple dit un état périmé. Recommandation : garder la règle et remplacer l'exemple par un énoncé sans statut, par exemple « (cas d'une exigence d'`injecter-les-collaborateurs-des-cas-d-usage` retirée du delta, livrée ensuite par `retirer-la-racine-de-composition-de-la-facade`) ». Aucune exigence n'est écrite sur `.claude/agent-memory/`, que la spécification traite comme figé (écart 4).

### D4 — Javadoc de classe et stéréotype du diagramme [Proposition, recommandation]

- Javadoc (anglais, sans jalon) : « Facade of one MINOS home and one selected storage backend: it hands out the application services and use cases, and keeps its ports and composition root package-private (ADR 0087). »
- `public-surfaces.md:12` : `<<application facade>>` à la place de `<<composition root long-lived>>`. Les méthodes listées par le diagramme existent au HEAD et restent.

## Vérification

- Rouge : au HEAD, la commande du scénario « Prose en vigueur » rend les lignes `MinosApplication.java:66`, `ARCH-0042-…md:333`, `:401` et `public-surfaces.md:12` (code 0) ; la commande du scénario de rétention rend la ligne 359 de `java-api.md`.
- Correction : les mêmes commandes ne rendent plus rien (code 1) ; la ligne 92 de l'index contient `retirer-la-racine-de-composition-de-la-facade`.
- Gates : `check-comment-language.py`, `check-current-docs.py`, `product-facts.py --check`, `check-p0-p2.py`, `archgate check` (index), `run_gates.py --fast`.
- Windows et Linux : sans objet (texte) ; les commandes `git grep` valent sur les deux.

## Recouvrements

- `tenir-le-contrat-de-la-facade-par-le-garde` (AUD-ARC-13, AUD-SEC-12) : `MinosApplication.java` (ce changement touche `:66`, l'autre `:358-361`) et `java-api.md` (ce changement touche `:359`, l'autre `:358`). Ordre conseillé : ce changement d'abord, sans dépendance ; l'autre rebase sur des lignes voisines.
- `injecter-les-ports-des-operations-locales` (AUD-ARC-14) : touche `MinosApplication.java` (hunk distinct) et déclare ne pas modifier `java-api.md`. Aucun ordre imposé.
- `actualiser-le-dossier-des-frontieres-de-modules` (AUD-ARC-16) : même dossier de capacité ; le second archivé ajoute ses lignes de traçabilité après l'autre.

## Traçabilité

| Constat | Exigence | Décision | Tâches | Preuve exécutée (2026-11-25) | Preuve manquante |
|---|---|---|---|---|---|
| AUD-ARC-15 | frontieres-de-modules › La documentation courante de la façade décrit son état en vigueur | D2, D3, D4 | 1.1-1.5 | rouge : `git grep` du scénario « Prose en vigueur » rend 4 lignes (code 0), celui de l'index ne rend rien (code 1) ; correction : le premier ne rend plus rien (code 1), le second rend `README.md:92` (code 0) ; `check-comment-language.py`, `check-p0-p2.py`, `check-current-docs.py`, `product-facts.py --check`, `archgate check` (15/15), `run_gates.py --fast` (30/30) verts | compilation du Javadoc (CI) |
| AUD-QUA-08 | frontieres-de-modules › La table de rupture de la façade ne promet aucun remplaçant absent | D1 | 2.1-2.3 | D1 (a) retenue (recommandation, 2026-11-25) ; rouge : `java-api.md:359` (code 0) ; correction : code 1 ; autres remplaçants de la table relus publics au HEAD (`MinosApplication.java:319-395`, `ProviderPlatformService.java:35-84`, `ProjectInspectionService.java:150`, `LocalProjectOperations.java:48`, `:100`) | — |
