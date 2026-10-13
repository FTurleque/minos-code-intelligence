# Tasks

Ordre dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`.

## 1. Mesure et décisions (AUD-DEP-16)

- [ ] 1.1 [AUD-DEP-16] Reconstruire le jar ombré au HEAD (`./mvnw -B -ntp -pl minos-app -am package`) et relever : fichiers `META-INF/NOTICE*` et `LICENSE*` présents, et, pour chaque jar du classpath d'exécution, ceux qu'il fournit (nom exact, taille). Consigner le tableau dans `design.md` (lève les « non mesuré »).
- [ ] 1.2 [AUD-DEP-16] **Décisions à obtenir** : D2 (licences par dépendance, recommandé, ou notices seules) et D3 (outils non Maven hors périmètre, recommandé). Consigner dans `design.md`.

## 2. Jar ombré (AUD-DEP-16)

- [ ] 2.1 [AUD-DEP-16] Rouge : `ShadedJarNoticesIT` dans `minos-app` (D5) — notice de chaque dépendance du tableau 1.1 présente, aucun en-tête d'attribution, licences non écrasées. **Preuve du rouge : `./mvnw -B -ntp -pl minos-app -am verify "-Dit.test=ShadedJarNoticesIT"` échoue sur le jar actuel (une seule notice).**
- [ ] 2.2 [AUD-DEP-16] `minos-app/pom.xml` : `ApacheNoticeResourceTransformer` avec `addHeader=false` (D1), transformer d'appoint pour toute variante de nom relevée en 1.1 ; licences selon D2. **Ne pas** ajouter `ApacheLicenseResourceTransformer` (il supprime les licences). Preuve : `ShadedJarNoticesIT` vert, tous les `ShadedJar*IT` et les goldens verts ; `python scripts/architecture/check-module-boundaries.py` vert.
- [ ] 2.3 [AUD-DEP-16] Rejouer `python scripts/release/check-supply-chain.py` et `python scripts/quality/check-tools-manifest.py` ; comparer la taille du jar avant et après.

## 3. Images Docker (AUD-DEP-16)

- [ ] 3.1 [AUD-DEP-16] Rouge : `scripts/ci/qualify-docker-release.sh` vérifie d'abord le répertoire des licences de l'image (présent, notices non vides, identiques à celles du jar) — échec sur l'image actuelle. Puis extraction dans `docker/Dockerfile.mcp.release` et `docker/Dockerfile.mcp` (D4), avec échec si rien n'est extrait. Rejouer les tests `M29Docker*` et `python scripts/quality/check-image-pins.py`.
- [ ] 3.2 **(manuelle)** [AUD-DEP-16] À la PR : lancer `docker-release-validation.yml` ; noter le run id dans `SUIVI.md`.

## 4. Clôture

- [ ] 4.1 `docs/developer/supply-chain.md` : ce que contiennent le jar, la distribution Windows et les images ; limite sur les outils non Maven (D3) ; note de mise en œuvre de l'ADR 0040. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-DEP-16 : statut passé de « plausible » à mesuré, écart sur le transformer de licences, décisions D2 et D3).
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/developer/supply-chain.md`.
