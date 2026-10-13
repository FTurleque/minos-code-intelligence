# Proposal

## Why

**AUD-QUA-11** (faible, sprint 11 de l'audit du 10 octobre 2026 ; connu depuis l'audit de septembre sous Q18). Les commentaires et la Javadoc du code de production n'ont pas de langue de référence écrite.

Décompte au HEAD, par la commande de l'audit : **173** fichiers Java de production sur **553** contiennent un commentaire en français (l'audit annonçait 170 sur 547) ; **26** d'entre eux mêlent français et anglais selon l'heuristique de l'audit (27 annoncés). Répartition : `minos-engine` 61 sur 169, `minos-application` 49 sur 108, `minos-domain` 19 sur 37, `minos-provider-scip` 14 sur 41, `minos-cli` 13 sur 39 ; `minos-api`, `minos-nexus`, `minos-integration-git` et `minos-storage-postgresql` n'en ont aucun.

`CONTRIBUTING.md` ne dit rien de la langue des commentaires. La seule règle écrite est dans `AGENTS.md` : la Javadoc ne mélange pas les langues dans un même fichier. Aucun gate ne la garde, et elle ne dit pas quelle langue choisir.

## What Changes

- **Une règle écrite** dans `CONTRIBUTING.md`, avec la langue de chaque module déclarée à un seul endroit du dépôt.
- **Un gate à liste décroissante** : un fichier de production qui mêle deux langues échoue, sauf s'il figure dans la liste des fichiers mixtes relevés au HEAD ; la liste ne peut que diminuer.
- **Les fichiers mixtes sont repris en premier**, par petits lots, à la main.
- **Aucune traduction en masse** : un fichier écrit entièrement dans l'autre langue n'est pas touché par ce changement.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `audit-qualite-code` : deux exigences ajoutées (la langue des commentaires de chaque module est déclarée ; un fichier de production ne mêle pas deux langues hors d'une liste décroissante).

## Hors périmètre

- Traduire les fichiers écrits entièrement dans une langue autre que celle de leur module.
- Les messages de code, les identifiants, les chaînes publiques et les journaux.
- Les tests (`src/test`), les scripts et la documentation, qui ont leurs propres règles (`AGENTS.md`).
- Les commentaires qui citent un jalon (changement `retirer-les-jalons-des-identifiants`, où ils sont aussi hors périmètre).
- Le plugin `minos-intellij`, sauf si la décision D1 l'inclut.

## Impact

- **Modules du reactor** : tous ceux qui ont un fichier mixte (au moins `minos-engine`, `minos-application`, `minos-domain`, `minos-provider-scip`, `minos-cli` ; `minos-mcp` et `minos-runtime-local` d'après les exemples de l'audit, non relus ; liste exacte en tâche 1.1). Commentaires seulement : aucun comportement modifié.
- **Surfaces publiques** : aucune. La Javadoc de `minos-api` reste en anglais.
- **ADR** : aucun (convention de contribution, pas décision d'architecture).
- **Gates à rejouer** : `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` (gate neuf câblé), `python scripts/architecture/check-private-io.py`, baseline SpotBugs (`quality/spotbugs-baseline/`), `python scripts/docs/check-current-docs.py`, `python .claude/scripts/run_gates.py` (les gates littéraux lisent les sources : un commentaire modifié peut porter une chaîne exigée).
- **Plateformes** : Windows et Linux ; le gate lit les sources en UTF-8 sur les deux.
