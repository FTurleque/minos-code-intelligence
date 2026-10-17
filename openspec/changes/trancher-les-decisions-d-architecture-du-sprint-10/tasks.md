# Tasks

Changement de décisions : aucune tâche ne modifie du code. Il n'y a donc pas de « rouge » ; la preuve de chaque tâche est le document consigné et les gates documentaires verts.

## 1. Instruction (AUD-ARC-01, -05, -07, -08, -11, -12)

- [ ] 1.1 [AUD-ARC-05, AUD-ARC-07, AUD-ARC-11] Relire en entier les ADR 0022, 0042, 0044, 0047, 0055, 0056, 0057, 0058 et `docs/project/roadmap/storage-hexagonal-2026-10/TASKS.md` (SH-01 à SH-12) ; corriger dans `design.md` toute affirmation que cette relecture infirme.
- [ ] 1.2 [AUD-ARC-11] Vérifier si un consommateur externe (dépôt NEXUS, documentation utilisateur) nomme `com.minos.integration.nexus.NexusExportBridgeMain` ; le consigner (entrée de la décision D3).

## 2. Décisions du propriétaire

- [ ] 2.1 **(manuelle)** [AUD-ARC-07] **Décision à obtenir** : D1 (ADR 0047 accepté avec son ordre, recommandé ; reporté avec critère ; rejeté). Consigner dans `design.md`.
- [ ] 2.2 **(manuelle)** [AUD-ARC-05] **Décision à obtenir** : D2 (port d'ACL conçu avec AUD-SEC-06 et `hosted` maintenu, recommandé ; ou amendement de l'ADR 0044).
- [ ] 2.3 **(manuelle)** [AUD-ARC-11] **Décision à obtenir** : D3 (ADR 0044 maintenu et arête documentée, recommandé ; ou service de route déplacé).
- [ ] 2.4 **(manuelle)** [AUD-ARC-12] **Décision à obtenir** : D4 (convention pour les nouveaux packages seulement, recommandé ; ou renommage progressif).

## 3. Consignation

- [ ] 3.1 [AUD-ARC-07] Mettre à jour le statut de l'ADR 0047 selon D1 (avec le critère de mesure) et l'index `docs/architecture/adr/README.md`. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 3.2 [AUD-ARC-05, AUD-ARC-11, AUD-ARC-12] Rédiger l'ADR (prochain numéro libre de l'index) qui confirme ou amende l'ADR 0044 sur les points D2 à D4 ; si D4 retient une convention, l'écrire aussi dans `.claude/rules/`. Rejouer `check-current-docs.py`.
- [ ] 3.3 [AUD-ARC-01, AUD-ARC-08] Reporter l'ordre de spécification D5 dans `docs/project/roadmap/storage-hexagonal-2026-10/README.md` (prérequis par chantier).

## 4. Clôture

- [ ] 4.1 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` : les six constats restent **ouverts** ; décisions prises, chantier et prérequis de chacun.
- [ ] 4.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, `docs/project/governance/ROADMAP.md`, index des ADR.
