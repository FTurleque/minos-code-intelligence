---
id: ARCH-0087
title: "Adaptateurs embarqués par l'application et façade réduite : câblage par la racine de composition"
domain: architecture
rules: false
---

# Adaptateurs embarqués par l'application et façade réduite : câblage par la racine de composition

- **Statut** : Proposed
- **Date** : 2026-11-18
- **Origine** : audit du 15 novembre 2026, sprint 7, AUD-ARC-01 et AUD-ARC-02 — changements OpenSpec `cabler-les-adaptateurs-embarques-par-la-racine-de-composition` et `injecter-les-collaborateurs-des-cas-d-usage`
- **Remplace** : —
- **Remplacé par** : —

## Context

[ARCH-0042](ARCH-0042-racine-de-composition.md) fait de `minos-bootstrap` le seul module non adaptateur qui connaît une classe concrète d'adaptateur. `minos-application` embarque pourtant trois familles d'adaptateurs :

- le client d'embeddings appris `OllamaEmbeddingProvider` (`java.net.HttpURLConnection`, Jackson) ;
- les treize classes `Java*` de l'analyseur de sources Java (`com.sun.source`, `javax.tools`), `FingerprintConstrainedJavaProgramGraphProvider` et son mémo `SourceStateKeyMemo` (qui nomme `JavaProgramGraphEngine.SourceStateKey`) ;
- le lecteur de sidecar `FileProgramGraphProvider`.

Ces classes ne dépendent que du domaine, du moteur et des ports `EmbeddingProvider`, `ProgramGraphProvider`, `ProgramGraphBudget` de `minos-application`. Les déplacer dans un module adaptateur exigerait de descendre ces ports et `DeterministicJson` dans `minos-engine`, d'ouvrir une racine de package `program` au moteur (A14), de porter le reactor à quinze ou seize modules et de déplacer tests et scopes JaCoCo ; neuf gates citent leurs fichiers par chemin littéral.

`MinosApplication` expose aussi `compositionRoot()` et quinze accesseurs publics typés moteur (table `MINOS_APPLICATION_ENGINE_ACCESSORS` du garde, règle A11) ; les cas d'usage d'indexation, d'indexation distante et d'export reçoivent l'application entière par des fabriques `of(MinosApplication)`.

## Decision

### 1. Adaptateurs embarqués : câblés par la racine, sans déplacement

1. Les adaptateurs embarqués restent à leur chemin dans `minos-application` et sont instanciés par `minos-bootstrap` seul, par trois fabriques abstraites du port `MinosApplicationComposer` :
   - `embeddingProvider(EmbeddingProviderSettings)` : fournisseur appris pour des réglages déjà lus et validés par l'application ; vide quand la racine ne fournit pas l'identifiant demandé, et l'application refuse alors le réglage (« unsupported semantic provider »), sans repli ;
   - `programGraphProviders(ProjectFingerprintSnapshotStore)` : liste de production des fournisseurs du graphe de programme, fournisseur de relations compris ;
   - `completeProgramGraphProviders(List<ProgramGraphProvider>)` : complétion d'une liste (analyseur de sources Java ajouté à côté du fournisseur de relations quand aucun analyseur Java n'est donné, lecteur de sidecar ajouté quand il manque ; les fournisseurs reçus gardent leur ordre et passent en premier). L'assembleur refuse une liste vide avant de demander la complétion.
2. Les fabriques sont abstraites : une valeur par défaut désactiverait une capacité en silence.
3. Deux listes fermées du garde de frontières (règle **A19** de `scripts/architecture/check-module-boundaries.py`) tiennent la frontière : `INFRASTRUCTURE_API_USERS`, fichiers de `minos-application` qui nomment `java.net.HttpURLConnection` ou un type de `java.net.http`, `javax.tools` ou `com.sun.source` ; `EMBEDDED_ADAPTER_CLASSES`, classes d'adaptateur embarquées avec leur justification, nommées par aucune autre source de production de `minos-application`. Les deux listes ne font que rétrécir.
4. **Cible conditionnelle** : le déplacement des adaptateurs dans un ou deux modules adaptateurs s'ouvre par un changement dédié quand un second langage de graphe de programme ou un second fournisseur appris arrive ([ARCH-0064](ARCH-0064-coeur-agnostique-du-langage-et-de-l-indexeur.md) § 5).

### 2. Façade réduite (proposition, en attente du propriétaire)

1. Les cas d'usage reçoivent leurs collaborateurs (`Dependencies`) et non l'application ; la façade `MinosApplication` expose les cas d'usage, construits à la première demande.
2. `compositionRoot()` et les accesseurs publics typés moteur cessent d'être publics (rupture d'une classe interne, sous le régime de `docs/user/java-api.md`) ; recommandation : retrait en une version, sans période de dépréciation. Options écartées : dépréciation pendant une version mineure ; statu quo sous cliquet.
3. Les tests des autres modules lisent un port de l'application par une classe d'accès de test publiée dans le `test-jar` de `minos-application`, admis en portée `test` seulement.

## Do's and Don'ts

### Do

- Demander à `MinosApplicationComposer` tout adaptateur embarqué dont l'application a besoin.
- Lire et valider les réglages dans `minos-application` avant d'appeler une fabrique du port.
- Retirer de `INFRASTRUCTURE_API_USERS` ou de `EMBEDDED_ADAPTER_CLASSES` toute entrée qui ne correspond plus aux sources.

### Don't

- Instancier, dans une source de production de `minos-application` hors de la famille, une classe de `EMBEDDED_ADAPTER_CLASSES`.
- Ajouter à `minos-application` un fichier qui nomme une API d'infrastructure surveillée sans l'inscrire dans la table, avec la décision qui le justifie.
- Donner une valeur par défaut aux fabriques du port.

## Consequences

### Positives

- La règle d'ARCH-0042 est vérifiée par un garde pour les adaptateurs restés dans l'application.
- Le constructeur public à deux arguments de `ProgramGraphService` et ses fabriques `productionProviders` disparaissent : un service ne choisit plus ses fournisseurs.

### Négatives

- Rupture de source et binaire de classes internes (`ProgramGraphService`, port `MinosApplicationComposer`), documentée dans `docs/user/java-api.md`.
- Le reactor garde des adaptateurs dans un module applicatif ; leur emplacement physique ne dit pas leur rôle, la table du garde le dit.

### Risques

- Une implémentation tierce du port doit implémenter les trois fabriques.

## Compliance and Enforcement

- Contrôle automatique : règle **A19** de `scripts/architecture/check-module-boundaries.py` et ses auto-tests (`EmbeddedAdapterRuleTest` de `test_check_module_boundaries.py`) ; gates littéraux `check-semantic-retrieval-consistency.py`, `check-p0-p2.py`, `check-advanced-provider-consistency.py`, `check-java-ast-provider-consistency.py`, qui affirment l'instanciation dans `DefaultMinosApplicationComposer.java` ; tests `ProgramGraphProviderCompositionTest`, `ProgramGraphProviderAssemblyTest`, `LearnedEmbeddingProviderSettingsTest`, `DefaultMinosApplicationComposerTest`.
- Revue : une nouvelle classe d'adaptateur dans `minos-application` passe par la table du garde avec sa justification, ou va dans un module adaptateur.

## Mise en œuvre

- Décision 1 : changement OpenSpec `cabler-les-adaptateurs-embarques-par-la-racine-de-composition`.
- Décision 2 : changement OpenSpec `injecter-les-collaborateurs-des-cas-d-usage`.

## References

- [ARCH-0042](ARCH-0042-racine-de-composition.md) : racine de composition.
- [ARCH-0057](ARCH-0057-finaliser-les-frontieres-hexagonales.md) : frontières hexagonales et façade applicative.
- [ARCH-0068](ARCH-0068-modules-dependances-et-propriete-des-packages.md) : modules, dépendances et propriété des packages.
- arc42 : `docs/architecture/arc42/05-vue-blocs.md` (`minos-application`), `docs/architecture/arc42/09-decisions.md`.
