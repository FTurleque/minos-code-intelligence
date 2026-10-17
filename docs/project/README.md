# Suivi du projet MINOS

Cet espace sert à piloter et auditer le projet. Il ne décrit pas comment utiliser ou construire MINOS (voir [`../user/`](../user/) et [`../developer/`](../developer/)).

| Dossier | Contenu |
|---|---|
| [`governance/`](governance/) | [État opérationnel](governance/STATUS.md) et [roadmap produit](governance/ROADMAP.md), gardés par `check-current-docs.py` et `product-facts.py --check` |
| [`roadmap/`](roadmap/) | plans d'exécution des jalons (`M<n>_EXECUTION.md`) et des chantiers datés (`<chantier>-<AAAA-MM>/`) |
| [`audits/`](audits/README.md) | audit en cours à la racine, audits clos sous `archive/` |
| [`research/`](research/) | études et expériences |
| [`history/`](history/README.md) | relevés figés : cadrage C0, retours M0, preuves M0 → M13, réconciliations, validations |

## Correspondance des anciens chemins

La documentation a été réorganisée le 2026-10-17. Les relevés datés (historique, audits archivés, changements OpenSpec archivés) citent dans leur prose les chemins de leur date ; leurs liens ont été recalculés.

| Ancien chemin | Nouveau chemin |
|---|---|
| `docs/STATUS.md` | `docs/project/governance/STATUS.md` |
| `docs/ROADMAP.md` | `docs/project/governance/ROADMAP.md` |
| `docs/TOOLCHAIN_POLICY.md` | `docs/developer/TOOLCHAIN_POLICY.md` |
| `docs/adr/` | `docs/architecture/adr/` |
| `docs/audit/` | `docs/project/audits/` |
| `docs/roadmap/` | `docs/project/roadmap/` |
| `docs/research/` | `docs/project/research/` |
| `docs/history/` | `docs/project/history/` |
| `docs/m0/` | `docs/project/history/m0/` |
| `docs/quality/` | `docs/developer/quality/` |
| `docs/releases/` | `docs/user/release-notes/` |
| `docs/generated/` | `docs/developer/generated/` |
