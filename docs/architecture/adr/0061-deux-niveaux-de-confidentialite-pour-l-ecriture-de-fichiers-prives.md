---
id: ADR-0061
title: "Écrire un fichier privé à deux niveaux de confidentialité"
domain: security
rules: true
---

# ADR-0061 — Écrire un fichier privé à deux niveaux de confidentialité

- **Statut** : Accepted
- **Date** : 2026-10-18
- **Acceptation** : 2026-10-23, décision du propriétaire
- **Origine** : audit du 10 octobre 2026, AUD-SEC-03 (suite) — changement OpenSpec `completer-les-primitives-d-e-s-privees`
- **Remplace** : —
- **Remplacé par** : —

---

## Context

Le garde `scripts/architecture/check-private-io.py` interdit les appels bruts de création et d'écriture de fichiers hors des primitives d'E/S (`PrivateLocalStorage`, `DurableAtomicFile`, `ConfinedFileOpener`, `BoundedFileLease`) et fige les occurrences existantes dans une liste de tolérance nominative. La migration de ces occurrences exige d'écrire un fichier **en flux** : un snapshot de plusieurs centaines de Mo, un téléchargement ou l'extraction d'une archive ne tiennent pas en mémoire, et `writePrivateFile(Path, byte[])` n'en est pas capable.

Les sites à migrer ne demandent pas tous la même garantie :

- un fichier de plan de contrôle ou de snapshot est écrit **une fois** et doit être privé et vérifié ;
- l'extraction d'une archive crée des milliers de fichiers dans un arbre dont la racine est déjà privée. Durcir chaque fichier réécrit sa DACL à la première rencontre de l'objet : sous Windows, 61 ms par fichier mesuré, contre 0,46 ms pour créer sans durcir (2 000 fichiers d'1 Kio) ;
- sous Windows, « un répertoire que MINOS a rendu privé n'hérite pas de son parent » : durcir les fichiers d'un arbre que lit le conteneur du bac à sable casserait l'accès qu'il y obtient par héritage.

## Critères de décision

- Un fichier de plan de contrôle reste vérifié privé avant d'être rendu à l'appelant.
- L'extraction en masse reste utilisable (coût par fichier) et ne retire pas l'accès hérité du bac à sable.
- Aucun site brut nouveau dans `PrivateLocalStorage` (le garde reste un cliquet).
- La garantie la plus faible est nommée, jamais présentée comme la plus forte.

## Options évaluées

### Option A — Un seul niveau durci partout

Chaque fichier créé est durci et vérifié. Simple, une seule garantie. Rejetée : 61 ms par fichier pour une extraction, et l'accès hérité du conteneur Windows est perdu.

### Option B — Deux niveaux, trois ouvertures

- **Niveau durci**, deux ouvertures : `openPrivateOutputStream` (créer privé ou durcir l'existant, puis tronquer et ouvrir sans suivre de lien) et `createPrivateOutputStream` (création exclusive, durcie et vérifiée, pour les fichiers que MINOS relit dans un dossier inscriptible par le provider).
- **Niveau arbre privé**, une ouverture : `openNewTreeFile` (création exclusive sans suivre de lien, droits POSIX `0600` demandés à la création, ACL Windows héritée du dossier, **aucune** réécriture de DACL ni revérification du fichier).

### Option C — Retirer la création exclusive durcie

Deux ouvertures seulement. Rejetée : `BoundedProcessOutput.openTarget` et `DistributedArtifactBundleStore.copyBounded` refont exactement cette séquence à la main.

## Decision

Option B. Le niveau « arbre privé » est **partiel** par construction : la confidentialité d'un tel fichier est celle de sa racine, rendue privée par `ensurePrivateDirectory` ou, pour un arbre lu par un provider, acquise par héritage ; la primitive ne la revérifie pas par fichier. Cette déclaration figure dans la Javadoc et dans `docs/developer/quality-gates.md`. Règle de revue : tout appelant du niveau arbre nomme, dans son test, la racine privée dont il hérite.

## Do's and Don'ts

### Do

- Offrir deux niveaux : durci (`openPrivateOutputStream`, `createPrivateOutputStream`) et arbre privé (`openNewTreeFile`).
- Déclarer le niveau arbre comme partiel, dans la Javadoc et dans `docs/developer/quality-gates.md`.
- Tout appelant du niveau arbre nomme, dans son test, la racine privée dont il hérite.
- Réserver le niveau durci aux fichiers de plan de contrôle et de snapshot.

### Don't

- Ne pas utiliser le niveau durci pour la copie en masse.
- Aucun site brut nouveau dans `PrivateLocalStorage`.
- Ne pas présenter la garantie la plus faible comme la plus forte.
- Pas de primitive de copie : une copie est une lecture confinée suivie d'une écriture privée.

## Consequences

### Conséquences positives

- Les 23 sites qui écrivent en flux migrent sans copie en mémoire ni entrée de liste de plus ; `PrivateLocalStorage` garde ses cinq entrées (les ouvertures réutilisent ses sites bruts).
- L'extraction en masse reste utilisable et conserve l'accès hérité du bac à sable.
- Un appel brut de plus dans ces classes échoue au garde.

### Conséquences négatives

- Cinq points d'entrée de plus dans `com.minos.io` (trois ouvertures en flux, une lecture par canal, une publication de répertoire).
- Le niveau arbre n'est pas vérifié par fichier : une racine mal protégée expose ce que l'on y écrit. Le risque est déplacé vers la revue de l'appelant.

### Conséquences neutres / contraintes

- Le niveau durci coûte 61 ms par fichier créé et 0,95 ms par fichier déjà durci sous Windows ; il est réservé aux fichiers de plan de contrôle et de snapshot, jamais à la copie en masse.
- Aucune primitive de copie : une copie est une lecture confinée suivie d'une écriture privée, composée par l'appelant.

## Compliance and Enforcement

- Gate `scripts/architecture/check-private-io.py` (job `invariants`) et son auto-test `test_check_private_io.py` — aucun appel brut de création ou d'écriture hors des primitives ; les maxima de `PrivateLocalStorage` ne bougent pas.
- Règle Archgate `0061-deux-niveaux-de-confidentialite-pour-l-ecriture-de-fichiers-prives.rules.ts` / `private-local-storage-garde-ses-entrees` : refuse que la liste de tolérance nommée par `check-private-io.py` (constante `ALLOWLIST`) porte pour `PrivateLocalStorage` un autre nombre d'entrées que les cinq énoncées ci-dessus ; contrôle propre à Archgate, il complète le cliquet de `check-private-io.py`, qui borne les occurrences de chaque entrée mais accepte une entrée ajoutée.
- Règle Archgate `0061-deux-niveaux-de-confidentialite-pour-l-ecriture-de-fichiers-prives.rules.ts` / `trois-ouvertures-en-flux` : refuse l'absence de la déclaration `public static` de `openPrivateOutputStream`, `createPrivateOutputStream` ou `openNewTreeFile` dans `PrivateLocalStorage` ; contrôle propre à Archgate, aucun gate Python ne le fait.
- Tests `PrivateLocalStorageStreamsTest`, `ConfinedFileOpenerChannelTest`, `DurableAtomicFileMoveDirectoryTest` (`minos-engine`, build Maven des jobs `Verify`).
- Revue : tout appelant du niveau arbre nomme, dans son test, la racine privée dont il hérite.

---

## Méthode de validation

- `PrivateLocalStorageStreamsTest` (trois ouvertures, refus de lien, de nom pris et de parent lien ; héritage de l'ACL sans réécriture sous Windows), `ConfinedFileOpenerChannelTest`, `DurableAtomicFileMoveDirectoryTest`.
- `python scripts/architecture/check-private-io.py` : les maxima de `PrivateLocalStorage` ne bougent pas.

## References

- Changement OpenSpec : `completer-les-primitives-d-e-s-privees` (archivé sous `openspec/changes/archive/`)
- Suivi : [`docs/project/audits/2026-10-10/SUIVI.md`](../../project/audits/2026-10-10/SUIVI.md)
- ADR liés : [ADR-0038](archive/0038-aggregate-worker-resource-containment.md) (confinement des workers), [ADR-0060](0060-scip-java-windows-natif-bloque-indexation-java-par-docker.md) (arbre lu par le conteneur Windows)
