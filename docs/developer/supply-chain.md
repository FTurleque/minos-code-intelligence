# Supply-chain et provenance de release

M21-S5 durcit la distribution Windows sans modifier les workflows GitHub Actions avant leur reprise explicite en août 2026.

## Preuves générées

Le build de release compile/package d'abord le reactor, puis `build-windows-distribution.ps1` invoque explicitement le goal agrégateur CycloneDX **depuis la racine d'exécution Maven** :

```text
target/sbom/minos-cyclonedx.json
```

Ce découplage est volontaire. `makeAggregateBom` est un goal aggregator et ne doit pas être attaché au module enfant `minos-app` : CycloneDX 2.9.2 ignore alors l'exécution avec `Skipping CycloneDX on non-execution root`.

Contrat courant :

```text
format        CycloneDX JSON
specVersion   1.6
scope test    exclu
reactor       agrégé depuis la racine Maven
provider      org.cyclonedx:cyclonedx-maven-plugin:2.9.2
```

La distribution Windows embarque ensuite :

```text
supply-chain/minos.cdx.json
supply-chain/THIRD-PARTY-NOTICES.txt
RELEASE-MANIFEST.json
```

`RELEASE-MANIFEST.json` contient le SHA-256 et la taille de chaque fichier de la distribution, hors manifest lui-même, ainsi que la version et le commit Git exacts.

Le build produit aussi des sidecars de release :

```text
minos-<version>.cdx.json
minos-<version>.cdx.json.sha256
MINOS-<version>-THIRD-PARTY-NOTICES.txt
MINOS-<version>-THIRD-PARTY-NOTICES.txt.sha256
```

Ils sont publiés avec le setup, le ZIP et leurs checksums par `publish-windows-release.ps1`.

## Politique licences tierces

`scripts/release/generate-third-party-notices.py` dérive l'inventaire depuis le SBOM et exclut les composants `com.minos`.

En mode release M21, `--strict` impose qu'un composant tiers fournisse au moins une métadonnée de licence exploitable dans le SBOM. Une dépendance sans licence connue bloque donc la qualification jusqu'à clarification ou correction de ses métadonnées ; MINOS ne devine jamais une licence.

Le fichier de notices est un index de coordonnées et de métadonnées publiées. Il ne remplace pas le texte de licence autoritatif de chaque projet tiers.

## Intégrité

`scripts/release/check-supply-chain.py` vérifie :

- CycloneDX 1.6 ;
- présence de composants tiers ;
- cohérence du nombre de composants entre SBOM et notices ;
- présence des licences en mode strict ;
- cohérence `VERSION` / version demandée / commit exact ;
- cohérence du manifest ;
- SHA-256 et taille de chaque fichier ;
- absence de fichier non déclaré ou de chemin stale dans le manifest.

Les ZIP, setup, SBOM et notices disposent chacun d'un sidecar `.sha256`.

## Authenticode

La signature Windows n'est jamais simulée.

Le helper :

```powershell
.\scripts\release\sign-windows-artifact.ps1 `
  -Artifact .\target\dist\MINOS-<version>-windows-x64-setup.exe `
  -CertificateThumbprint <thumbprint> `
  -TimestampUrl <url-optionnelle>
```

utilise `signtool.exe`, SHA-256 et vérifie ensuite `Get-AuthenticodeSignature`.

Le runner S5 accepte un candidat non signé tant qu'aucun certificat de production n'est configuré. Pour rendre la signature obligatoire :

```powershell
$env:MINOS_REQUIRE_SIGNED_RELEASE = '1'
```

Dans ce mode, un setup sans signature Authenticode `Valid` échoue.

## Gate M21-S5

Entrée autoritative locale :

```powershell
.\scripts\m21\run-s5.ps1 -ExpectedHead <sha>
```

Le runner :

1. rejoue le gate M21/M20 exact-head ;
2. reconstruit le package de release sans répéter les tests déjà passés par le gate core ;
3. génère le SBOM agrégé depuis la racine Maven ;
4. génère notices et manifest ;
5. vérifie tous les checksums ;
6. construit le setup ;
7. rejoue install/uninstall ZIP + setup via `publish-windows-release.ps1 -ValidateOnly` ;
8. vérifie le statut Authenticode selon la politique locale ;
9. confirme HEAD inchangé et worktree propre.

## Images de conteneur : mise à jour des digests par Dependabot

Toutes les images sont écrites `<image>:<tag>@sha256:<digest d'index>` (gate `scripts/quality/check-image-pins.py`). Dependabot (`.github/dependabot.yml`) les suit par **deux** écosystèmes, chacun avec ses propres fichiers :

| Écosystème | Fichiers lus (`directory: "/docker"`) | Ce qu'il met à jour |
| --- | --- | --- |
| `docker` | `Dockerfile.mcp`, `Dockerfile.mcp.release` (`FROM`) | l'image de base Temurin (le majeur est ignoré : il doit suivre `maven.compiler.release`) |
| `docker-compose` | `compose-mcp.prod.yaml`, `compose-mcp.connected.yaml` (`image:`, y compris le défaut d'un `${VAR:-<image>:<tag>@sha256:…}`) | les images `pgvector/pgvector` (défaut de `MINOS_POSTGRES_IMAGE`) et `ollama/ollama` (défaut de `MINOS_OLLAMA_IMAGE`) du compose connecté |

Dependabot réécrit **ensemble** le tag et le digest, ou seulement le digest quand le tag n'a pas bougé (reconstruction du tag amont). Pour pgvector il ne compare que les tags de même suffixe (`-pg17`) : un changement de majeur PostgreSQL n'est jamais proposé. Il ne lit pas `${MINOS_IMAGE}` (l'image MINOS elle-même, sans défaut) ni les services `build:`.

**Pourquoi les fichiers s'appellent `compose-mcp.*.yaml`.** L'écosystème `docker-compose` ne retient un fichier que si son nom correspond à `(docker-)?compose(-\w+)?(\.[\w-]+)?\.ya?ml` (`FILENAME_REGEX`, `docker_compose/file_fetcher.rb` de `dependabot-core`) : au plus **un** segment pointé. Les anciens noms à deux segments pointés étaient invisibles pour lui, donc les digests pgvector et ollama, épinglés par le lot S11, ne se seraient jamais mis à jour ; ce n'est plus le cas. `check-image-pins.py` refuse un fichier compose de `docker/` dont le nom sort de ce motif, et l'absence de l'entrée `docker-compose` pour `/docker` dans `dependabot.yml` (auto-tests rouge puis vert).

**Ce que Dependabot ne fait pas, et qui reste à la charge de la revue de sa PR :**

1. Il ne sait pas pourquoi une version est montée. Une PR pgvector ou ollama se relit avec les notes de version de l'amont : pgvector peut demander `ALTER EXTENSION vector UPDATE` sur une base existante, Ollama peut changer le comportement d'un runner ou l'empreinte mémoire.
2. Les plafonds mémoire et PID du compose (`x-limits-ollama`, `x-limits-postgres`, `docs/user/docker-runtime.md`, « Mesures et marges ») ont été **mesurés** sur les versions épinglées au moment de S11 : après une montée d'Ollama ou de pgvector, rejouer la mesure avant de fusionner.
3. Il ne lance pas la qualification Docker (`docker-upgrade-qualification.yml`, déclenchée à la main) : la lancer avant de fusionner une PR qui touche l'image du compose.
4. Le gate `check-image-pins.py` reste rouge si Dependabot produisait un tag sans digest ou un digest sans tag ; il ne dit rien du bien-fondé de la montée.

**Ce qui a été vérifié, et ce qui ne l'a pas été.** Rejoué hors ligne sur les fichiers réels (S23-SUIVI, « Renommage des compose ») : le nom est apparié ; les `FROM` des Dockerfile ne le sont pas ; l'analyse lit deux dépendances (`pgvector/pgvector` `0.8.2-pg17`, `ollama/ollama` `0.32.0`) avec leur digest ; la réécriture du défaut interpolé ne change que la ligne visée (digest seul, ou tag et digest). **Non rejoué** : l'interrogation du registre Docker Hub et le choix du tag candidat (lus dans `docker/update_checker.rb`, non exécutés) : la première PR de Dependabot sur ces fichiers est la preuve de bout en bout, à surveiller le lundi suivant la fusion.

## Frontière avec la CI

Aucune modification de workflow GitHub Actions n'est incluse dans S5 en juillet 2026. L'épinglage immuable des actions, les checks distants et la branch protection restent dans M21-S2, explicitement en pause jusqu'en août.