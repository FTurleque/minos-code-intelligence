# Proposal

## Why

**AUD-QUA-10** (faible, sprint 8 de l'audit du 10 octobre 2026 ; résidu ouvert de Q15). `ConfinedFileOpener`, la primitive par laquelle tout le code de production ouvre un fichier sans suivre de lien, porte un champ statique, mutable et visible du package : `static volatile Runnable beforeOpenForTests` (`minos-engine/src/main/java/com/minos/io/ConfinedFileOpener.java:64`). Il est exécuté à **trois** endroits du chemin d'ouverture (`:131`, `:201`, `:225`), juste entre la dernière validation et l'ouverture — l'instant exact qu'un attaquant doit atteindre.

Au HEAD `949e1569` : 13 occurrences du nom, 4 en production (la déclaration et les trois appels) et 9 en test (`ConfinedFileOpenerTest`, `OpenRegularFileNoFollowTest`, qui le remettent à zéro en `@AfterEach`). C'est un état global de la JVM dans une classe de sécurité : un test qui oublie la remise à zéro perturbe les autres, et n'importe quel code du package `com.minos.io` peut y poser un traitement.

## What Changes

- **Le champ statique disparaît.** Le crochet devient un paramètre de deux surcharges package-private, une par entrée publique (`openConfinedRegularFile`, `openRegularFileNoFollow`) ; les méthodes publiques les appellent avec un crochet vide.
- **Les deux classes de test** passent leur crochet en argument ; plus de remise à zéro en `@AfterEach`.
- **Un test de structure** échoue si un champ statique non `final` réapparaît dans `ConfinedFileOpener`.
- **Signatures publiques inchangées.**

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : une exigence ajoutée (l'ouverture confinée ne dépend d'aucun état statique modifiable).

## Hors périmètre

- La partie « E/S dans le constructeur de `IndexingExecutionRequest` » de Q15 : l'audit la dit absente au HEAD ; elle est seulement consignée comme close dans le suivi.
- Le fichier archivé `docs/audit/archive/2026-09/AUDIT-2026-09.md` (Q15 en `:531`) : historique figé, non modifié.
- Tout changement de comportement de l'ouverture confinée (ordre des contrôles, classification des erreurs, repli sans `SecureDirectoryStream`).
- Les autres points d'injection de test du dépôt.

## Impact

- **Modules du reactor** : `minos-engine` (`com.minos.io.ConfinedFileOpener` et ses deux classes de test). Aucune dépendance ajoutée (ADR 0022).
- **Surfaces publiques** : aucune. L'API Java publique de `ConfinedFileOpener` garde ses trois méthodes ; 50 appels dans 29 fichiers de production ne changent pas.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/remediation/check-p0-p2.py` (`:140-141` et `:335` lisent des chaînes de `ConfinedFileOpener` et de ses appelants), `python scripts/remediation/check-audit-remediation-v2.py` (`:143`, `:153`), `python scripts/architecture/check-private-io.py` (la classe est une primitive), `python .claude/scripts/run_gates.py --fast`. Aucun scope JaCoCo ne cible `com/minos/io` dans `scripts/quality/check-jacoco.py` ; SonarCloud juge le nouveau code de `minos-engine`.
- **Plateformes** : Windows et Linux ; les deux branches d'ouverture (par `SecureDirectoryStream` quand le système en fournit un, sinon vérification sous descripteur tenu) portent chacune un appel du crochet.
