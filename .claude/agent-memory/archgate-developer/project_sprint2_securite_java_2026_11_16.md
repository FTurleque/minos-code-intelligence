---
name: sprint2-securite-java-2026-11-16
description: Sprint 2 sécurité Java de l'audit 2026-11-15 fusionné dans main (PR 763-765) ; restes : archivage OpenSpec, SUIVI à jour, SEC-0083 Proposed
metadata:
  type: project
---

PR #763 (AUD-SEC-05/QUA-05), #764 (AUD-SEC-01, ADR SEC-0083 Proposed, option B) et #765 (sous-modules de l'analyse locale, hors spécification) fusionnées le 2026-11-16 dans cet ordre, `--merge`, sans suppression de branche.

**Why:** la pile était empilée (chaque branche contient la précédente) ; GitHub affichait CLEAN pour #764/#765 juste après la fusion de la précédente, sans `update-branch` nécessaire.

**How to apply:** restent à faire (décisions du propriétaire) : archiver les deux changements OpenSpec (lignes de traçabilité des dossiers de capacité à écrire à l'archivage), passer SEC-0083 de Proposed à Accepted, mettre à jour les lignes AUD-SEC-01/05 et AUD-QUA-05 de `docs/project/audits/2026-11-15/SUIVI.md` (elles disent encore « non poussé »), annoncer le correctif sous-modules selon `SECURITY.md`. Pièges vus : Gitleaks rouge sur toute PR tant que l'exception de #757 n'est pas dans main ; les remarques SonarCloud d'un commentaire peuvent dater d'avant le dernier push ; `SubmoduleWalk.containsGitModulesFile` déclare IOException ; `IgnoreSubmoduleMode` vit dans `org.eclipse.jgit.submodule.SubmoduleWalk`.
