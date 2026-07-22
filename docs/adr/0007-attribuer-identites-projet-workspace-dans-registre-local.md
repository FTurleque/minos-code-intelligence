# ADR-0007 — Attribuer les identités projet/workspace dans un registre local

- Statut : **Accepté**
- Date : **22 juillet 2026**
- Validation : **M1.2 fusionné le 22 juillet 2026**
- Jalon : M1

## Contexte

Le modèle MINOS validé en M0 impose qu'un chemin local ne constitue pas à lui seul l'identité métier d'un projet. M1 doit pourtant retrouver un projet entre deux exécutions, regrouper plusieurs projets dans un workspace et rester local-first sans introduire prématurément une base de données ou un service.

## Décision

1. le registre attribue des UUID aux projets et workspaces lors de leur première création ;
2. ces UUID sont persistés et relus, ils ne sont jamais dérivés du chemin, du nom ou du fournisseur d'indexation ;
3. le chemin canonique sert uniquement à rendre l'enregistrement d'une même racine locale idempotent ;
4. le stockage du registre est configurable et distinct du code métier du projet ;
5. chaque projet persiste son éventuel `workspaceId` ; la liste des projets d'un workspace est dérivée de ces enregistrements afin d'éviter deux sources de vérité relationnelles ;
6. chaque fichier de registre est écrit via fichier temporaire puis promotion atomique lorsque le système de fichiers le permet, avec repli par remplacement ;
7. le registre reste indépendant de SCIP, Glean et de tout fournisseur d'indexation.

## Conséquences

- un redémarrage de MINOS conserve l'identité d'un projet enregistré ;
- deux racines différentes obtiennent deux identités distinctes, même si elles portent le même nom ;
- un déplacement physique d'un projet n'est pas automatiquement déclaré comme continuité d'identité : cette réconciliation demandera une preuve explicite dans un incrément ultérieur ;
- aucune base SQL/embedded n'est nécessaire pour M1 ;
- les opérations concurrentes multi-processus ne sont pas encore garanties, le registre M1 synchronise uniquement les opérations dans une instance JVM.

## Validation obtenue

M1.2 a confirmé par tests puis build local vert :

- stabilité de l'UUID après recréation du registre ;
- idempotence de l'enregistrement d'une même racine ;
- persistance de l'affectation workspace ;
- absence de fuite de types fournisseur dans le package `registry`.

La PR M1.2 a été fusionnée dans `main` au commit `b12c4bdc5a6061c6d6b0e4f7ef0ad86db42d9b31`.
