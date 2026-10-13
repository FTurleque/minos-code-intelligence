# Proposal

## Why

**AUD-DEP-16** (faible, sprint 3 de l'audit du 10 octobre 2026) : le jar ombré et les images Docker redistribuent des dépendances sous licence Apache-2.0 sans conserver leurs fichiers `NOTICE`, ce que demande l'article 4(d) de cette licence.

- **Jar ombré** : `minos-app/pom.xml:65-66` ne déclare que `ServicesResourceTransformer` et `ManifestResourceTransformer`. Les `META-INF/NOTICE` homonymes des dépendances s'écrasent : le jar local du 12 octobre ne contient qu'**un** `META-INF/NOTICE` de 738 octets (celui de Jackson 2) et un `META-INF/NOTICE.txt` (Commons Codec). Le constat était « plausible » ; il est **mesuré**.
- **Images Docker** : `docker/Dockerfile.mcp:17` et `docker/Dockerfile.mcp.release:254` copient le jar et aucune notice. Le fichier `THIRD-PARTY-NOTICES.txt` n'existe que dans la distribution Windows, où `scripts/release/generate-third-party-notices.py` le produit depuis le SBOM (`build-windows-distribution.ps1:482-484`). `docs/developer/supply-chain.md:52` précise d'ailleurs qu'il est un index de coordonnées qui « ne remplace pas le texte de licence ».

## What Changes

- **Notices agrégées dans le jar** : les `NOTICE` des dépendances sont concaténés à l'ombrage au lieu de s'écraser, sans en-tête attribuant MINOS à un tiers.
- **Licences conservées** : les textes de licence des dépendances restent dans le jar, chacun identifiable (aucun n'est supprimé ni écrasé).
- **Images Docker** : les notices et licences sont présentes dans l'image à un emplacement documenté, extraites du jar pendant la construction (aucun fichier à fournir en plus dans le contexte de build).
- **Un test d'intégration du jar ombré** affirme le contenu attendu, et la qualification Docker affirme sa présence dans l'image.

## Capabilities

### New Capabilities

- `chaine-de-livraison` : aucune capacité existante ne décrit ce qu'un livrable doit contenir ou prouver à la publication. Elle reçoit ici les notices tierces ; les changements du sprint 5 y ajoutent la publication du plugin, l'attestation de provenance et la reproductibilité du build.

### Modified Capabilities

(aucune)

## Hors périmètre

- Les licences des outils **non Maven** de l'image (paquets npm de scip-typescript et scip-python, binaires Go, Rust, .NET, paquets Debian) : ils ne figurent pas dans le SBOM Maven. Périmètre à trancher (décision D3) ; ce changement se limite aux dépendances Java redistribuées dans le jar.
- Un audit juridique des licences ou de leur compatibilité avec la licence propriétaire de MINOS.
- La distribution Windows : elle livre déjà `THIRD-PARTY-NOTICES.txt` ; elle profite du jar corrigé sans autre modification.
- Le plugin IntelliJ (une dépendance, gson) : hors reactor ; observation consignée.
- Les autres constats du sprint 3.

## Impact

- **Modules du reactor** : `minos-app` (configuration du shade, nouveau test d'intégration `ShadedJar*IT`). Aucun autre module.
- **Fichiers** : `minos-app/pom.xml`, `docker/Dockerfile.mcp`, `docker/Dockerfile.mcp.release`, `scripts/ci/qualify-docker-release.sh`, `docs/developer/supply-chain.md`.
- **Surfaces publiques** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS). Le contenu `META-INF` du jar change.
- **ADR** : aucun nouvel ADR ; l'ADR 0040 (`:73`, SBOM et notices de la charge) reçoit une note de mise en œuvre.
- **Gates à rejouer** : `./mvnw -B -ntp -pl minos-app -am verify` (tous les `ShadedJar*IT`, goldens), `python scripts/release/check-supply-chain.py`, `python scripts/quality/check-tools-manifest.py`, `python scripts/architecture/check-module-boundaries.py`, `python scripts/docs/check-current-docs.py`, gate « Provider-complete image exact-head ».
- **Plateformes** : le jar est identique sous Windows et Linux ; les images sont Linux.
