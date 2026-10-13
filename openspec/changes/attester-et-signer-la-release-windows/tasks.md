# Tasks

Ordre : rouge, correction, preuve. Trois lots : 1 (attestation, sans dépendance externe), 2 (garde-fou de signature), 3 (signature réelle, selon D2). À fusionner après `publier-le-plugin-avec-la-release`. Les `.ps1` restent en CRLF ; chaque workflow modifié est relu par `yaml.safe_load`.

## 1. Attestation de provenance (AUD-DEP-06)

- [ ] 1.1 [AUD-DEP-06] Rouge : cas d'auto-test du gate des workflows — job de publication sans étape d'attestation (refusé), `id-token: write` au niveau du workflow (refusé), configuration cible (acceptée) ; cas « `supply-chain.md` cite un chemin de script inexistant » (refusé). **Preuve du rouge : le dépôt réel échoue sur l'attestation et sur `supply-chain.md:97`.**
- [ ] 1.2 [AUD-DEP-06] `release-windows.yml`, job `publish` : `actions/attest-build-provenance` épinglée par SHA sur tous les artefacts publiés (installateur, zips, SBOM, notices, archive du plugin si présente), permissions `id-token: write` et `attestations: write` au niveau du job (D1). Preuve : gate vert ; `python scripts/quality/check-workflow-pins.py` et `python scripts/docs/check-current-docs.py` verts.
- [ ] 1.3 [AUD-DEP-06] `docs/developer/supply-chain.md` : chemin du script corrigé, état réel (non signé, attesté), commande `gh attestation verify` ; documentation utilisateur d'installation : comment vérifier un téléchargement. Rejouer `check-current-docs.py`.

## 2. Garde-fou de signature (AUD-DEP-06)

- [ ] 2.1 [AUD-DEP-06] Rouge : `scripts/release/test_release_signature_gate.py` (Windows, extraction des définitions réelles ; sauté ailleurs avec sa raison) — exigence + fichier non signé → échec ; exigence + signature de test sans horodatage → échec ; exigence + mode absent → échec avant publication ; exigence absente → succès et état « non signé ». Câbler dans `invariants` et dans le pas Windows de `verify` (`python scripts/quality/check-ci-wiring.py`).
- [ ] 2.2 [AUD-DEP-06] Fonction de contrôle (D3) appelée par le workflow de release ; lecture de `MINOS_REQUIRE_SIGNED_RELEASE` ; état de signature écrit dans le manifeste de release (D4, champ additif). Preuve : test vert sous Windows PowerShell 5.1 et `pwsh` ; `python scripts/release/check-supply-chain.py` vert.
- [ ] 2.3 [AUD-DEP-06] Ordre signature → sommes → qualification (D5) dans `build-windows-distribution.ps1` et `publish-windows-release.ps1` ; la qualification recalcule les sommes des fichiers publiés. Preuve : test « artefact modifié après le calcul de sa somme → échec » ; candidat local (`build-local-windows-candidate.ps1`) si l'outillage du poste le permet, sinon le dire.

## 3. Signature réelle (AUD-DEP-06)

- [ ] 3.1 **(manuelle)** [AUD-DEP-06] **Décision à obtenir** : D2 (service de signature, certificat sur runner auto-hébergé, ou pas de signature pour l'instant). Consigner dans `design.md`. Si A ou B : rédiger l'ADR (prochain numéro de `docs/adr/README.md`) et brancher le mode retenu dans le job `build`, exécutables internes compris.
- [ ] 3.2 **(manuelle)** [AUD-DEP-06] Au prochain candidat de release : relever l'attestation (`gh attestation verify` sur l'installateur téléchargé), l'état de signature du manifeste, et, si D2 = A ou B, `Get-AuthenticodeSignature` sur l'installateur et `minos.exe`. Noter les run ids dans `SUIVI.md`.

## 4. Clôture

- [ ] 4.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-DEP-06 : statut par lot, décision D2, écarts sur le script de signature, preuves).
- [ ] 4.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md` (état de signature exact), `docs/developer/supply-chain.md`, `docs/user/installation.md`, index des ADR si un ADR est créé.
