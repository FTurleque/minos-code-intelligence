# Proposal

## Why

Un seul répertoire illisible suffit à rendre un projet inindexable : la découverte, l'empreinte et l'inventaire des fichiers visibles relancent l'exception du JDK au lieu de consulter la politique d'exclusion. Cas typique : un volume Docker `pgdata/` en 0700 appartenant à `root`, ou une ACL Windows qui refuse la lecture, même quand `.gitignore` nomme ce répertoire ou qu'il est « durci » (`node_modules`, `target`…). Le JDK ouvre le répertoire **avant** `preVisitDirectory` : un répertoire durci illisible n'est donc jamais « sauté ». Aucune règle d'ignore n'y remédie ; le contournement est de changer les droits ou de déplacer le répertoire. Pour un répertoire **non** ignoré, l'échec fail-closed est défendable, mais il n'offre aucune issue à l'utilisateur. Deux défauts voisins de la même sémantique d'ignore et de l'ordre de capture sont traités avec lui : le BOM UTF-8 d'un `.gitignore` produit sous Windows fait perdre sa première règle, et la découverte précède l'empreinte, si bien qu'une structure créée entre les deux est promue en baseline sans avoir été indexée.

## What Changes

- **D01 (P2).** Un répertoire illisible **ignoré** (`.gitignore`, `.minosignore`) ou **durci** est écarté avec une trace d'avertissement bornée, dans la découverte, l'empreinte, l'inventaire des fichiers visibles **et la copie de travail du provider** (lacune de la fiche : sans ce dernier point `minos index` échoue encore après le correctif des trois visiteurs). Un répertoire illisible **non ignoré** reste un échec fail-closed, mais avec un message actionnable : nom relatif et suggestion `.minosignore`, sans chemin absolu. La racine du projet ou du scope illisible échoue toujours.
- **D10 (P3).** Le BOM UTF-8 en tête d'un fichier d'ignore est retiré, sur toutes les plateformes (Git le fait partout), de sorte que la première règle s'applique.
- **D06 (P3).** L'empreinte de référence est capturée **avant** la découverte, dans les deux chemins (`prepare` du CLI et `refreshLocked` du coordinateur), si bien qu'une structure apparue entre les deux empêche la promotion de la baseline.
- Aucune rupture : le seul changement observable est qu'un projet qui échouait sur un répertoire illisible ignoré réussit, et que le message d'échec d'un répertoire non ignoré devient lisible.

## Capabilities

### New Capabilities

- `decouverte-et-negociation`: découverte du projet, de ses modules et de ses racines de sources, empreinte de référence et sémantique d'ignore (`.gitignore`, `.minosignore`, répertoires durcis). Ce changement y inscrit les premières exigences de la capacité (`openspec/specs/` est vide) : tolérance aux répertoires illisibles, lecture des fichiers d'ignore, ordre de capture de l'empreinte.

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

## Constats d'audit couverts

| Constat | Qualification | Priorité | Traitement |
|---|---|---|---|
| MINOS-AUD-D01 — un répertoire illisible, même ignoré ou durci, fait échouer découverte, empreinte et statut | DÉFAUT CONFIRMÉ pour les ignorés et les durcis ; DÉCISION À CLARIFIER pour les non-ignorés | P2 retenue par le responsable (la fiche proposait P1 ; un contournement existe et la prémisse fail-closed est défendable pour un répertoire non ignoré) | exigences et tâches pour ignorés/durcis et message actionnable des non-ignorés ; tolérance totale des non-ignorés = question ouverte |
| MINOS-AUD-D10 — sémantique d'ignore divergente de Git sous Windows | DÉFAUT CONFIRMÉ (BOM) ; RISQUE (casse) | P3 | BOM : exigence et tâche ; casse : question ouverte, hors tâches bloquantes |
| MINOS-AUD-D12 — `NO_CHANGES` silencieux face aux liens symboliques et répertoires durcis | DÉCISION À CLARIFIER | P3 | question ouverte uniquement |
| MINOS-AUD-D06 — la découverte précède l'empreinte (TOCTOU) | DÉFAUT CONFIRMÉ (ordre des opérations) ; fenêtre étroite | P3 | exigence et tâche courte (inversion de deux lignes, deux sites) |

**Dépendance** : `diagnostiquer-statut-mcp-et-erreurs` traite D02 (le statut d'index dépend d'une découverte complète) et D03 (décodage du snapshot). Le présent changement ne rend pas le statut léger : il fait seulement que la découverte ne lève plus pour un répertoire illisible ignoré ou durci, ce dont le statut hérite. Les deux changements ne se bloquent pas mutuellement.

**Hors périmètre** : D02, D03 (changement `diagnostiquer-statut-mcp-et-erreurs`), D11 (budgets : décision), D13, D14 ; le saut de descente dans un répertoire ignoré sans règle de négation (optimisation, D11) ; la tolérance d'un **fichier** régulier non ignoré illisible (l'empreinte le lit pour le hacher : échec fail-closed inchangé) ; l'export NEXUS (parcours distinct, non examiné) ; le décodage UTF-16 d'un fichier d'ignore (Git ne le gère pas non plus) ; la casse des motifs (question ouverte).

## Impact

- **Modules du reactor touchés** : `minos-engine` (paquets `com.minos.discovery`, `com.minos.incremental`, `com.minos.source`), `minos-runtime-local` (`ProviderWorkspaceFiles`, copie de travail du provider), `minos-cli` (`LocalAutonomousIndexOperations.prepare`, inversion de deux lignes). Aucun nouveau module ; direction ADR-0022 inchangée (les trois modules dépendent déjà du bas vers le haut : `engine` ← `runtime-local`, `engine` ← `cli`).
- **Surfaces publiques** : **CLI** (`index`, `project inspect` : un projet avec répertoire illisible ignoré cesse d'échouer ; message d'échec amélioré pour un non-ignoré) ; **MCP** (`minos_index_status` en hérite, via l'inspection ; aucun changement de contrat ni de schéma) ; **API Java** (au plus une méthode publique additive sur `ProjectIgnoreRules`/`ProjectIgnorePolicy`, voir design ; additive, pas de rupture) ; IntelliJ et NEXUS : aucune.
- **ADR** : aucun nouvel ADR et aucun amendement requis. Références : ADR 0008 et 0026 (découverte), ADR 0014 (empreintes : « les empreintes décrivent ce qui a été indexé » justifie D06), ADR 0039 (reprise, empreinte par scope).
- **Gates à rejouer** : `scripts/remediation/check-mne.py` (exige les littéraux `visitFile(Path file` et `visitFileFailed(Path file` dans `ProjectDiscoveryService`), `check-mnd.py` (`ProjectIgnorePolicy` : `visibleFileNamesByRoot`, `containsVisibleExtension`, `scanVisibleFileNames`), `check-post-mne.py` (`ProviderWorkspaceFiles`, `ProjectIgnoreRules` : `root.resolve(".gitignore")`, `root.resolve(".minosignore")`, `BoundedInputStream`), `scripts/quality/check-remote-distributed-consistency.py`, `check-advanced-provider-consistency.py`, `check-polyglot-provider-consistency.py`. **Aucun scope JaCoCo** de `scripts/quality/check-jacoco.py` ne cible `com/minos/discovery/` ni `com/minos/incremental/` (grep fait) : seul `provider-execution-trust-boundary` (qui contient `ProviderWorkspaceFiles`, plancher 0,68/0,48) est concerné ; la couverture du nouveau code ne repose donc que sur ses tests.
