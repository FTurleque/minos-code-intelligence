# Proposal

## Why

**AUD-DEP-06** (moyenne, sprint 5 de l'audit du 10 octobre 2026). La release Windows est publiée **sans signature Authenticode ni attestation de provenance** :

- `scripts/release/sign-windows-artifact.ps1` existe mais n'est appelé par **aucun** workflow ni aucun script de release ;
- la variable `MINOS_REQUIRE_SIGNED_RELEASE`, censée rendre la signature obligatoire, n'est lue que par un script historique (`scripts/history/m21/run-s5.ps1:90,92`) ;
- `release-windows.yml:173-175` et `:249-251` publient l'installateur et le zip avec des fichiers `.sha256` **hébergés au même endroit** que les binaires ;
- aucune action d'attestation (`attest-build-provenance`), aucune permission `id-token` ni `attestations` dans les workflows ;
- `docs/developer/supply-chain.md:97` renvoie à `.\scripts\m21\run-s5.ps1`, chemin qui n'existe plus.

Un utilisateur ne peut donc pas distinguer un installateur légitime d'un installateur substitué, et la politique « signature obligatoire » décrite par la documentation n'est exécutée par rien.

## What Changes

- **Attestation de provenance** de chaque artefact publié (installateur, zips, SBOM, notices, archive du plugin), émise par le workflow de release et vérifiable par `gh attestation verify`.
- **Signature fail-closed** : quand la signature est exigée, la release échoue si un exécutable publié n'est pas signé et horodaté ; quand elle ne l'est pas, la release le **dit** dans son manifeste et ses notes au lieu de le taire.
- **Ordre garanti** : signature avant le calcul des sommes et avant la qualification.
- **Documentation** : procédure de vérification pour l'utilisateur, chemin corrigé, état réel de la signature.

Le **mode de signature** (certificat, service de signature) dépend d'un moyen que seul le propriétaire peut fournir : c'est une décision en attente (`design.md`, D2). L'attestation, elle, ne dépend de rien d'externe et peut être livrée seule.

## Capabilities

### New Capabilities

(aucune : `chaine-de-livraison` est créée par `conserver-les-notices-tierces`)

### Modified Capabilities

- `chaine-de-livraison` : trois exigences ajoutées (provenance attestée de chaque artefact publié ; signature exigée fail-closed et jamais présumée ; sommes calculées sur les artefacts finals).

## Hors périmètre

- **Acheter ou provisionner un certificat de signature** : action du propriétaire.
- La signature ou l'attestation des images Docker (elles sont construites sur le poste de l'utilisateur ou en CI de validation, pas publiées dans un registre).
- La réputation SmartScreen (elle dépend du certificat et du temps).
- La publication du plugin (AUD-DEP-07, changement `publier-le-plugin-avec-la-release`, à fusionner avant) et la reproductibilité du build (AUD-DEP-11).
- Les scripts historiques sous `scripts/history/`.

## Impact

- **Modules du reactor** : aucun.
- **Fichiers** : `.github/workflows/release-windows.yml` (jobs `build` et `publish`), `scripts/release/sign-windows-artifact.ps1`, `scripts/release/build-windows-distribution.ps1` et `publish-windows-release.ps1` (ordre signature → sommes → qualification), `scripts/quality/check-workflow-pins.py` ou `check-ci-wiring.py` (gate), `docs/developer/supply-chain.md`, documentation utilisateur d'installation.
- **Surfaces publiques** : aucune sortie CLI, MCP ou API. Le manifeste de release gagne un champ additif sur l'état de signature.
- **ADR** : un ADR sur la signature et la provenance des livrables (prochain numéro libre de l'index) **si** un service de signature est retenu en D2 ; planifié en tâche, non rédigé ici.
- **Gates à rejouer** : `python scripts/quality/check-workflow-pins.py` (nouvelle action épinglée par SHA), `python scripts/docs/check-current-docs.py` (`:207-213`, chaînes exigées dans `release-windows.yml`), `python scripts/release/check-supply-chain.py`, `python scripts/quality/check-ci-wiring.py`, `scripts/install/verify-windows-upgrade-transaction.ps1` si la liste des fichiers livrés change.
- **Plateformes** : Windows (signature, `signtool`) ; l'attestation est indépendante de la plateforme. Dépôt **public** : les attestations d'artefacts sont disponibles.
