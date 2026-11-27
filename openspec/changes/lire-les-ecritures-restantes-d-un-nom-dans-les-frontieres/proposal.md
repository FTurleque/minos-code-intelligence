# Proposal

## Why

Suite du constat **AUD-ARC-17** de l'audit `docs/project/audits/2026-11-15/` : le design du changement `lire-les-noms-coupes-autour-d-un-point-dans-les-frontieres` nomme trois écritures qu'il ne traite pas (écarts 2, 3 et 6). Une sonde sur arbres temporaires les confirme :

1. un **échappement Unicode** (`\u002e`) cache un nom ou un mot aux règles qui lisent le Java ; un `\u000a` termine un commentaire de ligne pour le compilateur, pas pour le garde ;
2. une **annotation de type dans un nom qualifié** (`com.minos.hosted.@Nullable HostedRole`) cache ce nom hors de la façade ;
3. **A19** laisse passer le joker sur un package d'API d'infrastructure (`import java.net.http.*;`), et `HttpURLConnection` importé par `import java.net.*;`.

Un cliquet qui ne lit pas une écriture laisse la dette qu'il mesure s'accroître sans échec.

## What Changes

- `java_escapes.py` (nouveau) : le décodage des échappements Unicode et des fins de ligne qu'ils produisent, partagé par `check-module-boundaries.py` et `check-private-io.py`, qui lit ainsi un retour chariot échappé comme le compilateur.
- Les règles de `check-module-boundaries.py` qui lisent un source Java le lisent échappements décodés.
- La lecture des noms sort une annotation du nom qualifié qu'elle interrompt, sans la faire disparaître du texte lu.
- A19 compte le joker sur `java.net.http`, `javax.tools` et `com.sun.source`, et `HttpURLConnection` nommé sous un joker de `java.net`.
- Les auto-tests reçoivent ces cas ; `docs/developer/quality-gates.md` les nomme.

Aucun code Java, aucun workflow, aucune sortie publique ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : trois exigences ajoutées (échappements Unicode, annotation dans un nom qualifié, joker d'infrastructure en A19).

## Hors périmètre

- La mesure du code concret du moteur (AUD-ARC-05), non cliquetée, qui compte les lignes telles qu'elles sont écrites.
- `check-comment-language.py`, qui a son propre décodage et n'est pas une règle de frontière.
- La règle Archgate d'ARCH-0027 sur les imports du plugin.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Un échappement cache un nom à A2 et un mot à A12, A16, A18 | [Existant vérifié] | sonde sur arbres temporaires, design « Sonde avant correction » |
| Une annotation dans un nom qualifié passe A2 et A11 (surfaces) | [Existant vérifié] | même sonde |
| A19 accepte trois jokers d'infrastructure et `HttpURLConnection` sous `java.net.*` | [Existant vérifié] | même sonde |
| Le verdict du dépôt ne change pas | [Existant vérifié] | sortie du garde identique avant et après (design, preuves) |

## Impact

- **Modules du reactor** : aucun code de production.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé ni amendé. La mise à jour d'ARCH-0087 (« Compliance and Enforcement ») reste la tâche manuelle ouverte du sprint 11 ; une ligne s'y ajoute.
- **Gates à rejouer** : `check-module-boundaries.py`, les cinq `scripts/architecture/test_check_module_boundaries*.py`, `test_gate_runner.py`, `check-private-io.py`, `test_check_private_io.py`, `check-ci-wiring.py`, `check-comment-language.py`, `check-current-docs.py`, `check-capability-dossiers.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : lecture de texte, bibliothèque standard ; rien de propre à Windows ou Linux.
- **Documentation durable et diagrammes** : `docs/developer/quality-gates.md` ; `docs/architecture/diagrams/capacites/frontieres-de-modules.md` à l'archivage.
- **Dépendance** : s'applique après `lire-les-noms-coupes-autour-d-un-point-dans-les-frontieres`, dont le code est la base ; archiver dans le même ordre.

## Questions ouvertes

Aucune bloquante.
