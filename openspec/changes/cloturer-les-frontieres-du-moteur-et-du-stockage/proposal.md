# Proposal

## Why

Le sprint 10 de l'audit du 10 octobre 2026 laisse deux constats ouverts.

- **AUD-ARC-05** : le moteur ne lance plus aucun processus (SH-08, port `com.minos.io.PlatformAccessControl`, A12 vide). Mais la part de code concret de `minos-engine` reste à 73,2 % (69 fichiers de classes, 12 836 lignes sur 17 526), contre 72 % à l'audit. L'ADR 0068 l'assume sans mesure ni raison écrite. Par ailleurs, A12 ne voit pas `Runtime r = Runtime.getRuntime(); r.exec(...)` : le motif n'accepte `exec` que chaîné à `getRuntime()`.
- **AUD-ARC-12** : la convention de l'ADR 0068 § 5 vaut pour les nouveaux packages. SH-02 et SH-03 ont créé `com.minos.storage.codec` et `com.minos.storage.home` dans `minos-storage`, sous la racine `storage`, que possède aussi `minos-engine`. Rien n'empêche le moteur de créer à son tour un sous-package de `com.minos.storage`. Le nom d'un package de cette racine ne dirait alors plus son module.

## What Changes

- **A12 voit un `Runtime` tenu par un nom** : un `exec` appelé sur une variable, un champ ou un paramètre de type `Runtime` est un site de lancement, comme une référence `Runtime.getRuntime()::exec`. Les autres appels de `Runtime` (`availableProcessors`, `maxMemory`) ne sont pas des lancements.
- **Mesure du code concret du moteur, sans cliquet** : le garde affiche à chaque exécution le nombre de fichiers et de lignes de `minos-engine` par genre de déclaration (classe, interface, record, enum). Il n'échoue jamais sur cette mesure. Décision du propriétaire du 2026-10-28 : accepter la part par écrit plutôt que la plafonner. Un plafond refuserait les trois classes qu'extrait le changement actif `decouper-les-classes-de-plus-de-800-lignes`. Il accepterait aussi qu'une classe devienne un record sans retirer de code.
- **Sous-packages de `com.minos.storage`** : en production comme en test, seul `minos-storage` déclare un package sous `com.minos.storage.` ; le moteur ne garde que le package racine (les ports). Le nom d'un tel package désigne donc toujours `minos-storage`. Aucun package n'est déplacé : `codec` est l'emplacement que fixe l'ADR 0068 § 4, et § 5 interdit de renommer un package existant.
- **ADR 0068** : note de mise en œuvre (mesure, raison de l'absence de cliquet, A12 étendu, règle des sous-packages du stockage).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : l'exigence A12 est modifiée (exec sur un `Runtime` tenu par un nom) ; deux exigences sont ajoutées (sous-packages du stockage, mesure du code concret du moteur).

## Hors périmètre

- **Déplacer `hosted`** ou d'autres classes concrètes hors du moteur : l'ADR 0068 § 3 et sa mise en œuvre les y laissent.
- **Renommer `com.minos.storage.codec` ou `com.minos.storage.home`** : contraire à l'ADR 0068 § 4 et § 5 (voir design).
- **Même règle pour `com.minos.runtime`** (moteur) et `com.minos.runtime.local` (runtime local), ou pour `com.minos.program` (domaine et application) : hiérarchies de même forme, non relevées par le constat. Elles relèvent d'un changement séparé.
- **Règle Archgate `mcp-no-process-or-file-write`** (ADR 0017) : elle a le même angle mort pour `Runtime` tenu par un nom ; elle relève de la surface MCP, pas de ce changement.
- **Suivi d'audit** (`SUIVI.md`, `findings.json`, `ETAT-2026-10-13.md`) : mis à jour par la session de clôture du sprint.

## Impact

- **Modules du reactor** : aucun code Java modifié ; aucune dépendance Maven, aucun package, aucun cycle.
- **Surfaces publiques** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés).
- **ADR** : 0068, section « Mise en œuvre » complétée.
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py` et `test_check_module_boundaries.py` ; `scripts/remediation/check-p0-p2.py` (cite le garde) ; `python .claude/scripts/run_gates.py --fast` ; `archgate check`.
- **Plateformes** : scripts Python en bibliothèque standard, identiques sous Windows et Linux.
