---
name: minos-release-promotion
description: Préparer et contrôler (sans jamais publier de soi-même) une promotion develop → main ou une release MINOS — pré-requis d'ascendance, checks exigés, preuves Docker et Windows, qualification hors ligne, plugin IntelliJ, tags immuables, notes. À utiliser quand l'utilisateur parle de release, de candidat, de promotion vers main, de tag vX.Y.Z, de publication Windows ou Docker, ou demande « est-on prêt à promouvoir ».
---

# Release et promotion

**Ce skill contrôle et prépare ; il ne publie, ne tague et ne promeut jamais sans demande explicite de l'utilisateur, et il ne contourne aucun ruleset.** Les hooks refusent déjà : push direct sur `main`/`develop`, suppression ou déplacement d'un tag `vX.Y.Z`, `gh pr merge --admin`, modification d'une release publiée.

## Principes (docs/developer/supply-chain.md, docs/TOOLCHAIN_POLICY.md)
- **Releases immuables** : tags `vX.Y.Z`, jamais retaguées, jamais supprimées. Un défaut dans une release se corrige par une release suivante.
- Flux : `develop` → PR vers `main`. `develop` doit **contenir l'ascendance de `main`** avant tout candidat (vérifié par la CI, étape « Require main ancestry in every candidate »).
- Un baseline Java/Maven/Gradle/IntelliJ ne change que par décision et promotion d'abord par `develop`.

## Liste de contrôle d'un candidat
1. Branche à jour de `main` : `git fetch origin main && git merge-base --is-ancestor origin/main HEAD`.
2. CI de la PR de promotion verte sur les **checks exigés** (`.github/required-checks.json`) **et** `Docker upgrade evidence gate` (workflow `release-promotion-gate.yml`, `main` seul). Ruleset réel conforme : `python scripts/quality/verify-ruleset.py` doit rendre 0.
3. Preuves de release : `docker-release-validation.yml` (images), `docker-upgrade-qualification.yml` (mise à niveau A → B, manuelle), `windows-installer.yml` et `windows-in-place-upgrade.yml` (installateur et mise à jour transactionnelle), `intellij-plugin.yml` (qualification du plugin). Lire les run ids, ne pas les supposer.
4. **Critère local, jamais lancé par la CI** : `scripts/release/qualify-offline-install.ps1 -Package minos-<version>-windows-x64.zip` (machine neuve, sans réseau) — à demander à l'utilisateur de lancer ou à lancer sur sa demande.
5. Supply-chain : SBOM CycloneDX et `THIRD-PARTY-NOTICES.txt` produits, `python scripts/release/check-supply-chain.py`, `python scripts/quality/check-tools-manifest.py` (charge d'outils ↔ catalogue), actions épinglées par SHA, images par digest.
6. Documentation : `docs/STATUS.md`, `docs/ROADMAP.md`, notes de version, `docs/user/production-installation.md` à jour (skill `minos-doc-sync`).
7. Publication Windows : workflow manuel `release-windows.yml` (version SemVer en entrée ; seul le job de publication a `contents: write`). Plugin : `intellij-plugin-release.yml` se déclenche à la publication de la release.

## Rapport
Pour chaque point : fait / non fait / non vérifiable ici, avec la commande ou le run. Lister les **actions manuelles** restant au propriétaire (ruleset, secrets, publication). Ne jamais déclarer « prêt » sur la seule base d'une CI verte de PR.
