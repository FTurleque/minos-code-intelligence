# Proposal

## Why

**AUD-PERF-02** (moyenne, sprint 4 de l'audit du 10 octobre 2026). La construction des documents sémantiques relit et redécode le fichier source **en entier pour chaque symbole** : `SemanticDocumentFactory.java:74` appelle `sourceReader.readExcerpt(...)` par symbole, et `LocalSourceReader.java:130-157` ouvre le fichier, le lit, le découpe en lignes et mesure chaque ligne en UTF-8 à chaque appel. Le commentaire de `SemanticDocumentFactory.java:69-70` affirme l'inverse (« a file is not reread once per symbol »).

Le coût est en O(symboles du fichier × taille du fichier). L'audit d'architecture de septembre l'a mesuré : environ 6 ms par document, 112,7 s pour 19 115 documents. Il est payé à chaque `minos index` avec un provider d'embeddings, et à la première requête hybride de chaque snapshot (`HybridSearchService.java:136`).

La boucle traite pourtant déjà les symboles fichier par fichier : l'information nécessaire pour ne lire qu'une fois est là.

## What Changes

- **Une lecture par fichier** : pendant la construction des documents, chaque fichier source est ouvert et décodé une fois ; les extraits de ses symboles sont découpés en mémoire.
- **Portée d'appel** : le contenu lu n'est conservé que le temps de traiter le fichier ; aucun cache ne survit à la construction, donc la fraîcheur des extraits est inchangée.
- **Documents identiques à l'octet** à ceux d'aujourd'hui.
- Le commentaire inexact est corrigé.

## Capabilities

### New Capabilities

(aucune : `recherche-semantique` est créée par `lire-l-identite-du-snapshot-actif-sans-le-charger`)

### Modified Capabilities

- `recherche-semantique` : deux exigences ajoutées (une ouverture par fichier pendant la construction des documents ; aucun contenu de source conservé après la construction).

## Hors périmètre

- Remettre un cache dans `LocalSourceReader` pour les autres appelants (contexte, extraits MCP) : il a été retiré à dessein pour la fraîcheur (`LocalSourceReader.java:133-135`) ; inchangé.
- Le port `SourceReader` : sa signature ne change pas.
- La synchronisation appelée sans changement (traitée par `synchroniser-l-index-semantique-par-differentiel`) et le coût d'une requête hybride (AUD-PERF-01).
- Le découpage des documents (taille des extraits, nombre de lignes de contexte) : inchangé.

## Impact

- **Modules du reactor** : `minos-application` seulement (`application/semantic/SemanticDocumentFactory`, `context/LocalSourceReader`).
- **Surfaces publiques** : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `check-private-io.py` (la lecture reste faite par `ConfinedFileOpener`), scope JaCoCo `semantic-hybrid-retrieval`, `python scripts/remediation/check-mne.py` et `check-mnd.py` s'ils citent `readExcerpt` (à relever en tâche 1.1), `python scripts/architecture/check-module-boundaries.py`.
- **Plateformes** : Windows et Linux, sans différence de comportement attendue (fins de ligne des fixtures à couvrir).
