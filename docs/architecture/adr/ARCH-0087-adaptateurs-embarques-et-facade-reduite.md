---
id: ARCH-0087
title: "Adaptateurs embarqués par l'application et façade réduite : câblage par la racine de composition"
domain: architecture
rules: false
---

# Adaptateurs embarqués par l'application et façade réduite : câblage par la racine de composition

- **Statut** : Accepted (décision du propriétaire du 2026-11-24)
- **Date** : 2026-11-18
- **Origine** : audit du 15 novembre 2026, sprint 7, AUD-ARC-01 et AUD-ARC-02 — changements OpenSpec `cabler-les-adaptateurs-embarques-par-la-racine-de-composition`, `injecter-les-collaborateurs-des-cas-d-usage` et `retirer-la-racine-de-composition-de-la-facade`
- **Remplace** : —
- **Remplacé par** : —

## Context

[ARCH-0042](ARCH-0042-racine-de-composition.md) fait de `minos-bootstrap` le seul module non adaptateur qui connaît une classe concrète d'adaptateur. `minos-application` embarque pourtant trois familles d'adaptateurs :

- le client d'embeddings appris `OllamaEmbeddingProvider` (`java.net.HttpURLConnection`, Jackson) ;
- les treize classes `Java*` de l'analyseur de sources Java (`com.sun.source`, `javax.tools`), `FingerprintConstrainedJavaProgramGraphProvider` et son mémo `SourceStateKeyMemo` (qui nomme `JavaProgramGraphEngine.SourceStateKey`) ;
- le lecteur de sidecar `FileProgramGraphProvider`.

Ces classes ne dépendent que du domaine, du moteur et des ports `EmbeddingProvider`, `ProgramGraphProvider`, `ProgramGraphBudget` de `minos-application`. Les déplacer dans un module adaptateur exigerait de descendre ces ports et `DeterministicJson` dans `minos-engine`, d'ouvrir une racine de package `program` au moteur (A14), de porter le reactor à quinze ou seize modules et de déplacer tests et scopes JaCoCo ; neuf gates citent leurs fichiers par chemin littéral.

La façade `MinosApplication` tient la racine de composition et les ports du moteur (magasins, services d'indexation, runtimes des providers). Une méthode publique qui les rend donne à toute surface, à tout module de contrat et à tout code tiers qui tient une application un accès technique au moteur et aux adaptateurs, que seule une table à cliquet limiterait ; un cas d'usage qui reçoit l'application entière dépend de tout ce qu'elle expose.

## Decision

### 1. Adaptateurs embarqués : câblés par la racine, sans déplacement

1. Les adaptateurs embarqués restent à leur chemin dans `minos-application` et sont instanciés par `minos-bootstrap` seul, par trois fabriques abstraites du port `MinosApplicationComposer` :
   - `embeddingProvider(EmbeddingProviderSettings)` : fournisseur appris pour des réglages déjà lus et validés par l'application ; vide quand la racine ne fournit pas l'identifiant demandé, et l'application refuse alors le réglage (« unsupported semantic provider »), sans repli ;
   - `programGraphProviders(ProjectFingerprintSnapshotStore)` : liste de production des fournisseurs du graphe de programme, fournisseur de relations compris ;
   - `completeProgramGraphProviders(List<ProgramGraphProvider>)` : complétion d'une liste (analyseur de sources Java ajouté à côté du fournisseur de relations quand aucun analyseur Java n'est donné, lecteur de sidecar ajouté quand il manque ; les fournisseurs reçus gardent leur ordre et passent en premier). L'assembleur refuse une liste vide avant de demander la complétion.
2. Les fabriques sont abstraites : une valeur par défaut désactiverait une capacité en silence.
3. Deux listes fermées du garde de frontières, lues par sa règle **A19** (gate `scripts/architecture/check-module-boundaries.py`), tiennent la frontière ; elles sont les sections `infrastructureApiUsers` et `embeddedAdapterClasses` de `scripts/architecture/module-boundaries.json` : `INFRASTRUCTURE_API_USERS`, fichiers de `minos-application` qui nomment `java.net.HttpURLConnection` ou un type de `java.net.http`, `javax.tools` ou `com.sun.source` ; `EMBEDDED_ADAPTER_CLASSES`, classes d'adaptateur embarquées avec leur justification, nommées par aucune autre source de production de `minos-application`. La première est incluse dans la seconde : chaque classe de `INFRASTRUCTURE_API_USERS` figure, par son nom simple, dans `EMBEDDED_ADAPTER_CLASSES`, et une entrée d'adaptateur ne quitte la liste qu'après que sa classe a cessé de nommer l'API ou a quitté `minos-application`. Les deux listes ne font que rétrécir. Une classe d'adaptateur qui ne nomme aucune API du JDK (le lecteur de sidecar `FileProgramGraphProvider`) n'est protégée, contre le retrait de son entrée, que par la revue de la liste : le garde ne voit que l'état courant des sources.
4. **Cible conditionnelle** : le déplacement des adaptateurs dans un ou deux modules adaptateurs s'ouvre par un changement dédié quand un second langage de graphe de programme ou un second fournisseur appris arrive ([ARCH-0064](ARCH-0064-coeur-agnostique-du-langage-et-de-l-indexeur.md) § 5).

### 2. Façade réduite

1. Les cas d'usage reçoivent leurs collaborateurs (`Dependencies`) et non l'application ; la façade `MinosApplication` expose les cas d'usage, construits à la première demande.
2. `MinosApplication` ne déclare aucune méthode publique d'instance qui rend un type de `minos-engine` ou `MinosApplicationComposer`. Sa racine de composition et ses ports sont privés au package `com.minos.application` ; les surfaces passent par les services applicatifs et les cas d'usage de la façade. Ces accesseurs ne sont pas publics : rupture d'une classe interne, sans période de dépréciation, documentée dans `docs/user/java-api.md`.
3. Les tests des autres modules lisent un port d'une application ouverte par la classe d'accès de test `MinosApplicationTestAccess`, seule classe du `test-jar` de `minos-application`, admis en portée `test` seulement ; aucun jar livré ne la contient.

## Do's and Don'ts

### Do

- Demander à `MinosApplicationComposer` tout adaptateur embarqué dont l'application a besoin.
- Lire et valider les réglages dans `minos-application` avant d'appeler une fabrique du port.
- Retirer de `INFRASTRUCTURE_API_USERS` ou de `EMBEDDED_ADAPTER_CLASSES` toute entrée qui ne correspond plus aux sources.
- Exposer à une surface un service applicatif ou un cas d'usage de la façade, jamais un port du moteur.
- Dans un test d'un autre module, lire un port d'une application par `MinosApplicationTestAccess`, le `test-jar` de `minos-application` étant déclaré en portée `test`.

### Don't

- Instancier, dans une source de production de `minos-application` hors de la famille, une classe de `EMBEDDED_ADAPTER_CLASSES`.
- Ajouter à `minos-application` un fichier qui nomme une API d'infrastructure surveillée sans l'inscrire dans la table, avec la décision qui le justifie.
- Donner une valeur par défaut aux fabriques du port.
- Rendre publique une méthode d'instance de `MinosApplication` qui rend un type de `minos-engine` ou `MinosApplicationComposer`, ou inscrire une entrée dans `MINOS_APPLICATION_ENGINE_ACCESSORS`.
- Déclarer le `test-jar` de `minos-application` dans une portée autre que `test`, ou y publier une autre classe ou une déclaration de service.

## Consequences

### Positives

- La règle d'ARCH-0042 est vérifiée par un garde pour les adaptateurs restés dans l'application.
- Le constructeur public à deux arguments de `ProgramGraphService` et ses fabriques `productionProviders` disparaissent : un service ne choisit plus ses fournisseurs.

### Négatives

- Rupture de source et binaire de classes internes (`ProgramGraphService`, port `MinosApplicationComposer`, accesseurs de `MinosApplication`), documentée dans `docs/user/java-api.md` avec le remplacement de chaque accesseur.
- Les tests des modules consommateurs dépendent du `test-jar` de `minos-application`.
- Le reactor garde des adaptateurs dans un module applicatif ; leur emplacement physique ne dit pas leur rôle, la table du garde le dit.

### Risques

- Une implémentation tierce du port doit implémenter les trois fabriques.

## Compliance and Enforcement

- Contrôle automatique : règle **A19** de `scripts/architecture/check-module-boundaries.py` et ses auto-tests (`EmbeddedAdapterRuleTest` de `test_check_module_boundaries_adapters.py`), dont le cas qui refuse une classe de `INFRASTRUCTURE_API_USERS` absente de `EMBEDDED_ADAPTER_CLASSES`, même quand une autre source la nomme ; gates littéraux `check-semantic-retrieval-consistency.py`, `check-p0-p2.py`, `check-advanced-provider-consistency.py`, `check-java-ast-provider-consistency.py`, qui affirment l'instanciation dans `DefaultMinosApplicationComposer.java` ; tests `ProgramGraphProviderCompositionTest`, `ProgramGraphProviderAssemblyTest`, `LearnedEmbeddingProviderSettingsTest`, `DefaultMinosApplicationComposerTest`.
- Contrôle automatique de la façade : règle **A11** de `scripts/architecture/check-module-boundaries.py` — table `MINOS_APPLICATION_ENGINE_ACCESSORS` vide (cliquet à zéro), refus d'une méthode publique d'instance de `MinosApplication` qui rend `MinosApplicationComposer` (`public_composition_root_methods`), refus d'une surface de production qui atteint la racine de composition — et règle de portée du `test-jar` (`check_application_test_jar_scope`) ; auto-tests `MinosApplicationAccessorRatchetTest` de `test_check_module_boundaries_facade.py` et `ApplicationTestJarScopeRuleTest` de `test_check_module_boundaries.py` ; tests `MinosApplicationTestAccessTest` (`minos-application`) et `ApplicationTestAccessPackagingIT` (`minos-app`, contenu du `test-jar`, du jar du module et du jar ombré). La compilation du reactor refuse tout appel d'un accesseur retiré hors du package.
- Revue : une nouvelle classe d'adaptateur dans `minos-application` passe par la table du garde avec sa justification, ou va dans un module adaptateur.
- Revue : le retrait d'une entrée de `EMBEDDED_ADAPTER_CLASSES` dont la classe ne nomme aucune API du JDK (`FileProgramGraphProvider`) n'est contrôlé que par la revue.

## Mise en œuvre

- Décision 1 : changement OpenSpec `cabler-les-adaptateurs-embarques-par-la-racine-de-composition`.
- Décision 2.1 : changement OpenSpec `injecter-les-collaborateurs-des-cas-d-usage`.
- Décisions 2.2 et 2.3 : changement OpenSpec `retirer-la-racine-de-composition-de-la-facade` (AUD-ARC-02).

## References

- [ARCH-0042](ARCH-0042-racine-de-composition.md) : racine de composition.
- [ARCH-0057](ARCH-0057-finaliser-les-frontieres-hexagonales.md) : frontières hexagonales et façade applicative.
- [ARCH-0068](ARCH-0068-modules-dependances-et-propriete-des-packages.md) : modules, dépendances et propriété des packages.
- arc42 : `docs/architecture/arc42/05-vue-blocs.md` (`minos-application`), `docs/architecture/arc42/09-decisions.md`.
