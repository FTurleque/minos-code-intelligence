---
id: DATA-0007
title: "Attribuer les identités projet/workspace dans un registre local"
domain: data
rules: false
---

# Attribuer les identités projet/workspace dans un registre local

- Statut : **Accepté**
- Date : **22 juillet 2026**
- Validation : **M1.2 fusionné le 22 juillet 2026**
- Jalon : M1

## Context

Le modèle MINOS validé en M0 impose qu'un chemin local ne constitue pas à lui seul l'identité métier d'un projet. M1 doit pourtant retrouver un projet entre deux exécutions, regrouper plusieurs projets dans un workspace et rester local-first sans introduire prématurément une base de données ou un service.

## Decision

1. le registre attribue des UUID aux projets et workspaces lors de leur première création ;
2. ces UUID sont persistés et relus, ils ne sont jamais dérivés du chemin, du nom ou du fournisseur d'indexation ;
3. le chemin canonique sert uniquement à rendre l'enregistrement d'une même racine locale idempotent ;
4. le stockage du registre est configurable et distinct du code métier du projet ;
5. chaque projet persiste son éventuel `workspaceId` ; la liste des projets d'un workspace est dérivée de ces enregistrements afin d'éviter deux sources de vérité relationnelles ;
6. chaque fichier de registre est écrit via fichier temporaire puis promotion atomique lorsque le système de fichiers le permet, avec repli par remplacement ;
7. le registre reste indépendant de SCIP, Glean et de tout fournisseur d'indexation.

## Do's and Don'ts

### Do

- Attribuer des UUID aux projets et workspaces à leur première création, puis les persister et les relire.
- Utiliser le chemin canonique uniquement pour rendre idempotent l'enregistrement d'une même racine locale.
- Dériver la liste des projets d'un workspace du `workspaceId` persisté par chaque projet.
- Écrire chaque fichier du registre par fichier temporaire puis promotion atomique.

### Don't

- Ne pas dériver les identités du chemin, du nom ou du fournisseur d'indexation.
- Ne pas rendre le registre dépendant de SCIP, de Glean ou d'un fournisseur d'indexation.

## Consequences

- un redémarrage de MINOS conserve l'identité d'un projet enregistré ;
- deux racines différentes obtiennent deux identités distinctes, même si elles portent le même nom ;
- un déplacement physique d'un projet n'est pas automatiquement déclaré comme continuité d'identité : cette réconciliation demandera une preuve explicite dans un incrément ultérieur ;
- aucune base SQL/embedded n'est nécessaire pour M1 ;
- les opérations concurrentes multi-processus ne sont pas encore garanties, le registre M1 synchronise uniquement les opérations dans une instance JVM.

## Compliance and Enforcement

- Gate `scripts/architecture/check-private-io.py` (job `invariants`) — écritures privées par les primitives confinées, dont la promotion atomique.
- Tests `LocalProjectRegistryTest` et `InterProcessLocalProjectRegistryTest` (`minos-storage-local`) — comportement du registre fichier.
- Revue : identités jamais dérivées du chemin ou du nom, aucune dépendance du registre vers un fournisseur.

## Validation obtenue

M1.2 a confirmé par tests puis build local vert :

- stabilité de l'UUID après recréation du registre ;
- idempotence de l'enregistrement d'une même racine ;
- persistance de l'affectation workspace ;
- absence de fuite de types fournisseur dans le package `registry`.

La PR M1.2 a été fusionnée dans `main` au commit `b12c4bdc5a6061c6d6b0e4f7ef0ad86db42d9b31`.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- Le point 6 prévoit une promotion atomique « avec repli par remplacement » ; l'écriture du registre passe par `DurableAtomicFile.replace`, qui échoue sur `AtomicMoveNotSupportedException` sans repli — preuve : `minos-storage-local/src/main/java/com/minos/storage/local/registry/LocalProjectRegistry.java:489-498`, `minos-engine/src/main/java/com/minos/io/DurableAtomicFile.java:199-200`.
- La conséquence « les opérations concurrentes multi-processus ne sont pas encore garanties » ne décrit plus le code : un registre sérialisé par verrou OS inter-processus existe — preuve : `minos-storage-local/src/main/java/com/minos/storage/local/registry/InterProcessLocalProjectRegistry.java:21-29`.
