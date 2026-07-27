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

## Frontière avec la CI

Aucune modification de workflow GitHub Actions n'est incluse dans S5 en juillet 2026. L'épinglage immuable des actions, les checks distants et la branch protection restent dans M21-S2, explicitement en pause jusqu'en août.