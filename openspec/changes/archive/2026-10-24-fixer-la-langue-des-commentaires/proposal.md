# Proposal

## Why

**AUD-QUA-11** (faible, sprint 11 de l'audit du 10 octobre 2026 ; connu depuis l'audit de septembre sous Q18). Les commentaires et la Javadoc du code de production n'ont pas de langue de référence écrite, et aucun contrôle n'empêche un fichier de mêler deux langues.

Décompte au HEAD `5ce7b79b` (méthode dans `design.md`) : par la commande de l'audit, **168** fichiers Java de production sur **560** ont une ligne de commentaire accentuée (l'audit annonçait 170 sur 547 ; la même commande en compte 176 sous une locale octet). Les fichiers qui mêlent les deux langues sont **22** selon l'heuristique de l'audit et **29** selon un critère par commentaire ; les deux ne s'accordent que sur 19 (l'audit annonçait 27). Les plus touchés : `minos-engine` (8 mixtes), `minos-application` (7), `minos-provider-scip` (6) ; `minos-api`, `minos-nexus` et `minos-integration-git` n'ont aucun commentaire français. Rejoué au HEAD `ebee335a` de la mise en œuvre (2026-10-24) : 576 fichiers, 170 à la commande de l'audit, 192 hors langue et **34** mixtes au critère du gate, 98 fichiers dont un commentaire cite un jalon (`design.md`).

`CONTRIBUTING.md` ne dit rien de la langue des commentaires. La seule règle écrite est `AGENTS.md:9` : la Javadoc ne mélange pas les langues dans un même fichier. Aucun gate ne la garde, et elle ne dit pas quelle langue choisir.

## What Changes

- **Une règle écrite** dans `CONTRIBUTING.md`, alignée avec `AGENTS.md`, et la langue de chaque module déclarée dans un seul fichier du dépôt (`scripts/quality/comment-language.json`).
- **Langue de référence : l'anglais** pour les commentaires et la Javadoc de tous les modules de code, plugin `minos-intellij` compris (décision du propriétaire du 2026-10-24, D1 = A).
- **Un gate à deux cliquets**, `scripts/quality/check-comment-language.py` (auto-test `--self-test`, câblé dans le job `invariants`) : un fichier mixte hors de la liste nominative échoue ; par module, le nombre de fichiers ayant un commentaire hors de la langue déclarée ne peut que baisser, et de même le nombre de fichiers dont un commentaire cite un jalon. Liste et compteurs tolèrent les déplacements de fichiers.
- **Les commentaires qui citent un jalon** (`M17`, `M21-S6`), renvoyés ici par `retirer-les-jalons-des-identifiants`, sont réécrits sans le jalon quand leur fichier est repris (décision du 2026-10-24).
- **Les fichiers mixtes sont repris en premier**, par petits lots, à la main.
- **Aucune traduction en masse** : un fichier écrit entièrement dans l'autre langue n'est pas touché par ce changement ; il est seulement compté.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `audit-qualite-code` : trois exigences ajoutées (la langue des commentaires de chaque module est déclarée et le nombre de fichiers hors langue ne peut que baisser ; un fichier de production ne mêle pas deux langues hors d'une liste décroissante ; le nombre de fichiers dont un commentaire cite un jalon ne peut que baisser).

## Hors périmètre

- Traduire les fichiers écrits entièrement dans une langue autre que celle de leur module (ils sont comptés, pas repris).
- Les messages de code, les identifiants, les chaînes publiques et les journaux.
- Les tests (`src/test`), les scripts et la documentation, qui ont leurs propres règles (`AGENTS.md`).
- Juger la qualité ou l'exactitude des commentaires.

## Impact

- **Modules du reactor** : ceux qui ont un fichier mixte au HEAD : `minos-application`, `minos-cli`, `minos-engine`, `minos-provider-scip`, `minos-runtime-local`, `minos-storage-local`, `minos-storage-postgresql` (34 fichiers, liste exacte dans `design.md`). Commentaires seulement : aucun comportement modifié. Le plugin `minos-intellij` est lu par le gate mais n'a aucun fichier mixte.
- **Surfaces publiques** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés). La Javadoc de `minos-api` est déjà entièrement anglaise.
- **ADR** : aucun nouveau, aucun amendé (convention de contribution, pas décision d'architecture).
- **Gates à rejouer** : `python scripts/quality/check-comment-language.py` et son `--self-test` (nouveaux) ; `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/test_check_ci_wiring.py`, `python scripts/quality/check-single-execution.py` et `python scripts/remediation/check-audit-remediation-v2.py` (gate neuf câblé) ; `python scripts/docs/check-current-docs.py` ; `python .claude/scripts/run_gates.py` (les gates littéraux lisent les sources : 21 fichiers mixtes sont cités par un `check-*.py`, aucune chaîne exigée dans un commentaire). La baseline SpotBugs et `check-private-io.py` ne dépendent pas des lignes ni des commentaires (`design.md`).
- **Plateformes** : Windows et Linux ; le gate lit les sources en UTF-8 explicite sur les deux.
