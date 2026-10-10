---
paths:
  - "minos-*/src/main/java/**/*.java"
---

# Code Java de production

Conventions relevées dans les gates, les ADR et les revues du dépôt. La convention du **module touché** fait foi quand elle est plus précise.

## Architecture
- Respecter la direction `domain → engine → runtime/storage → adapters → application → surfaces` (ADR 0022). Aucune dépendance Maven nouvelle sans relire `scripts/architecture/check-module-boundaries.py` (règles A2, A3, A7, A8, A9).
- Un package appartient à **un seul module** (ADR 0044). Déplacer une classe plutôt que renommer ; un déplacement fait entrer la classe en « code neuf » pour SonarCloud : ses tests vivent dans son module.
- `application` ne nomme aucune classe d'adaptateur ; les surfaces non plus (ADR 0042, 0058). Le câblage est dans `minos-bootstrap`.
- Pas de cycle de packages : un nouveau package est une feuille tant qu'il peut l'être (callbacks plutôt que dépendances vers `ui`/`actions`).
- Constructeur unique et point d'entrée nommé (ADR 0045, règle A4) : pas de constructeurs télescopiques. Aucune E/S dans un constructeur de `record`.

## Erreurs, journaux, secrets
- Les messages vers l'appelant passent par `PublicErrorMessages` et ne contiennent **aucun chemin absolu**, ni cause interne, ni identifiant sensible. Un échec interne reste opaque pour l'appelant, diagnostiquable pour l'opérateur.
- Journal : `System.Logger`. Un WARNING écrit le chemin **relatif** à la racine gérée et la classe de l'exception, jamais un `Path` absolu concaténé ni la cause d'un échec de fichier (la cause d'un diagnostic de bac à sable est une exception nominative).
- stdout est réservé au protocole MCP : rien d'autre n'y écrit (journaux sur stderr).
- Aucun secret, jeton ni mot de passe en clair dans le code, les messages ou les journaux.

## E/S et processus
- E/S privées uniquement par les primitives (`ConfinedFileOpener`, `DurableAtomicFile`, `BoundedFileLease`, `BoundedProperties`, `PrivateLocalStorage`, `FileTreeOperations`) : `Files.write`, `Files.createDirectories`, `Files.newInputStream`, `FileChannel.open`/`lock` sont interdits ailleurs (`check-private-io.py`, liste de tolérance en cliquet).
- Un processus externe ne reçoit **jamais** l'environnement hérité : liste d'autorisation (`ProviderProcessEnvironment`), motifs de secret exclus.
- Code non fiable : bac à sable OS ou refus, jamais de mode dégradé silencieux ; une dégradation de confinement doit être observable.

## Hygiène
- `Locale.ROOT` sur toute comparaison ou transformation de casse.
- Aucun numéro de jalon (`m24`, `s5`…) dans un nom de fichier, de classe ou de script (ADR 0043).
- Sorties « déterministes » : ordre de clés stable, pas de `NaN`/`Infinity`, échappement JSON par l'encodeur unique `DeterministicJson`.
- Une capacité n'est annoncée que si le provider produit réellement les faits correspondants ; une limite se déclare dans la sortie (limitations), pas seulement en documentation.

## Avant de conclure
Rejouer `python .claude/scripts/run_gates.py --fast`, puis `./mvnw -B -ntp -pl <module> -am test`. Voir les skills `minos-verify-local` et `minos-literal-gates`.
