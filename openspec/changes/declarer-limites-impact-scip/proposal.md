# Proposal

## Why

Sur tout snapshot issu d'un indexeur SCIP (Java, TypeScript, Go, Rust, C#, C/C++), l'analyse
d'impact, l'architecture factuelle et la recherche d'appelants ne s'appuient que sur les relations
persistées. Or les occurrences SCIP, qui forment l'essentiel d'un index, ne deviennent jamais des
arêtes symbole vers symbole : aucun producteur de `CALLS`, `IMPORTS`, `EXTENDS` ni `INSTANTIATES`
n'existe dans `src/main` (relu et confirmé au HEAD). Un appelant ordinaire ou un module consommateur
est donc absent des résultats, et rien dans la sortie ne le dit : l'utilisateur lit seulement
`DYNAMIC_DISPATCH_NOT_PROVEN`, `REFLECTION_NOT_PROVEN` et `RUNTIME_CONFIGURATION_NOT_PROVEN`, et
conclut à tort que l'absence de résultat est une absence de chemin statique. Pour un outil d'impact,
c'est le faux négatif silencieux, le défaut le plus grave. Le catalogue des indexeurs déclare déjà
« CALLS relations are not emitted explicitly » au niveau fournisseur, mais cette limite n'atteint
jamais la sortie des utilisateurs. MINOS est « capability-honest » (`openspec/config.yaml`) : une
capacité absente ou non qualifiée n'est jamais présentée comme acquise.

Ce changement est le **premier lot, d'honnêteté de capacité** : déclarer la limite partout où elle
fausse la lecture. Il ne dérive PAS de relations depuis les occurrences (lot ultérieur conditionnel,
qui exige un amendement d'ADR).

## What Changes

- **Impact** : nouvelle limitation `OCCURRENCE_REFERENCES_NOT_PROJECTED` dans le rapport d'impact
  quand le snapshot contient des occurrences résolues non définitionnelles que le graphe de
  relations ne projette pas ; elle atteint CLI, MCP, API Java et `minos_impact_v2` (qui conserve
  l'impact de base) sans changement de schéma.
- **Architecture** : le graphe de dépendances d'architecture (et la vue composée, les formats
  JSON, texte, Mermaid et DOT, le DTO de l'API Java) porte une liste de limitations additive et
  déclare la même limite ; le message de preuve précise que l'agrégat ne couvre que les `DEPENDS_ON`
  persistés.
- **Appelants et appelés** : `find-callers`, `find-callees` (CLI) et `minos_find_callers`,
  `minos_find_callees` (MCP) déclarent, additivement, l'absence de relations d'appel quand le
  snapshot n'en contient aucune, au lieu de renvoyer une liste vide muette.
- **Module des symboles (F-04)** : `find-symbol --module` ne renvoie plus silencieusement zéro
  résultat sur un snapshot sans module renseigné (refus explicite), puis l'indexation autonome
  renseigne `Symbol.moduleId` selon la même règle que l'architecture.
- **Tests liés (F-02)** : la classification « fichier de test » reconnaît les conventions Go,
  Python, C#, C/C++ (avec le même garde-fou « jamais le mot nu ») ; le rôle SCIP `Test` comme signal
  supplémentaire est un lot conditionnel.
- Goldens de caractérisation régénérés de façon explicite et additive (clé `limitations`), sans
  dépendance à l'hôte.
- **Pas de rupture de contrat** : champs, clés JSON et valeurs d'énumération ajoutés, jamais
  retirés ni renommés.

## Capabilities

### New Capabilities

- `tests-lies-et-impact` : l'analyse d'impact conservatrice et les tests liés déclarent leurs limites
  de couverture (références par occurrence non projetées) et classent comme tests les fichiers des
  conventions polyglottes qualifiées.
- `program-graph-et-architecture` : l'architecture factuelle et les requêtes d'appelants/appelés
  déclarent les relations absentes du snapshot ; le filtre par module des symboles est honnête et
  renseigné par l'indexation autonome.

### Modified Capabilities

<!-- Aucune : openspec/specs/ est vide, tous les deltas sont des ajouts sur des capacités nouvelles. -->

## Constats d'audit couverts

| Constat | Titre | Qualification | Priorité | Traitement dans ce changement |
|---|---|---|---|---|
| MINOS-AUD-F01 | Impact, appelants et dépendances inter-modules aveugles aux occurrences SCIP, sans limitation déclarée | DÉFAUT CONFIRMÉ | P1 | Premier lot : déclaration de la limite (impact, architecture, appelants). La dérivation occurrence vers relation est un lot ultérieur conditionnel, hors tâches bloquantes. |
| MINOS-AUD-F04 | `Symbol.moduleId` jamais renseigné par l'indexation autonome | DÉFAUT CONFIRMÉ | P2 | Refus explicite de `--module` sur snapshot sans module, puis renseignement à l'indexation (mécanisme corrigé par rapport à la fiche, voir design). |
| MINOS-AUD-F02 | Tests liés : classification « test » non polyglotte et ancre unique par fichier | DÉFAUT CONFIRMÉ | P2 | Classification de chemin étendue. L'ancre unique par fichier dépend de la plage englobante (cause racine de F01) : lot ultérieur conditionnel, déclaration de la limite en question ouverte. |
| MINOS-AUD-F03 | Corrélation runtime ligne vers symbole : seule la plage de l'identifiant est connue | DÉFAUT CONFIRMÉ | P2 | Lié à F01 (même cause racine : plage englobante non ingérée). Aucune tâche bloquante ; la correction passe par le lot ultérieur conditionnel. Aucune fausse affirmation aujourd'hui (tout est `OBSERVED_PARTIAL`). |

## Hors périmètre

- La **dérivation occurrence vers relation** (lecture de `enclosing_range` et `enclosing_symbol`,
  relations `REFERENCES` de nature `DERIVED`) : elle change la sémantique des relations normalisées
  et exige l'amendement des ADR 0010 et 0015. Elle figure en « Questions ouvertes » du design et en
  lot ultérieur conditionnel.
- Le stockage d'une plage englobante dans le snapshot (changement de format v3, ADR-0046 et
  ADR-0047) et donc la correction de F03.
- La modification des providers SCIP eux-mêmes ou de leur profil de capacités.
- Les autres constats de l'annexe F (F05 à F14), en particulier F12 (chemin explicatif de
  l'impact) et F13.
- L'extension de la méthode `findRelationships` de l'API Java : la liste reste inchangée (voir
  design, un champ de limitations exige une nouvelle méthode, hors de ce lot).
- Le client IntelliJ et NEXUS : aucun changement de leur contrat.
- Toute rédaction d'ADR (voir ci-dessous).

## Impact

- **Modules du reactor touchés** : `minos-engine` (requêtes de relations, dérivation des tests
  liés, point commun du critère de module, prédicat partagé de limitation), `minos-application`
  (impact, architecture, rendu des sorties), `minos-provider-scip` et `minos-bootstrap`
  (renseignement du module à l'indexation autonome, composition), `minos-cli`, `minos-mcp`,
  `minos-api` (champ additif du DTO d'architecture), `minos-app` (goldens de caractérisation).
  `minos-domain`, `minos-storage-*`, `minos-runtime-local`, `minos-nexus`, `minos-integration-git`
  inchangés.
- **Surfaces publiques impactées, toutes de façon additive** : CLI (`impact`, `architecture`,
  `find-callers`, `find-callees`, `find-symbol --module`), MCP (`minos_impact`, `minos_impact_v2`,
  `minos_architecture`, `minos_architecture_graph`, `minos_find_callers`, `minos_find_callees`),
  API Java (`ArchitectureDto` : composant additif avec constructeur historique conservé ;
  `ImpactReportDto` : aucune modification, la liste `limitations` existe déjà). IntelliJ : le
  protocole CLI JSON gagne des clés additives, que le client doit ignorer (à vérifier dans les
  tests du client, hors reactor). NEXUS : aucun changement.
- **Gates** : `scripts/remediation/check-*.py` et `scripts/m*/check-*.py` (grep fait, aucune chaîne
  littérale sur les classes touchées hors `ScipProjectSnapshotLifecycle` dans `check-post-mne.py`),
  `scripts/architecture/check-module-boundaries.py`, scopes JaCoCo `advanced-impact-security`,
  `program-graph-analysis`, `persistence-cache-indexes`, `m24-polyglot-provider-platform` (rouge
  connu et hors régression sous Windows : comparer avec `develop` avant d'investiguer).
- **ADR** : aucun nouvel ADR n'est requis pour ce premier lot, qui applique ADR-0015 (« aucun
  résultat présenté comme preuve d'exhaustivité ») et le cadre d'ADR-0036 sans les modifier. Le
  **lot ultérieur conditionnel** (dérivation occurrence vers relation) **amende ADR-0010 et
  ADR-0015** ; l'amendement, à proposer par l'utilisateur, doit précéder toute tâche de ce lot.
  Le mécanisme de renseignement du module (F04) doit être validé contre ADR-0022 ; si le porteur
  de la règle de module change de module du reactor, un ADR serait à proposer (statut Proposed).
  Aucun ADR n'est rédigé ici.
