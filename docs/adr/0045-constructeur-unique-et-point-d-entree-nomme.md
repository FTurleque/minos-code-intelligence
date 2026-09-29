# 0045 — Constructeur unique et point d'entrée nommé ; racine de composition regroupée par domaine

Status: Accepted (2026-09-29) — mis en œuvre ; historique, inventaire et preuves dans [`ARCHI-SUIVI.md`](../audit/ARCHI-SUIVI.md) (lot 2, A4).

Complète l'audit [`AUDIT-2026-09.md`](../audit/AUDIT-2026-09.md) (constat A4). Prolonge l'[ADR 0042](0042-racine-de-composition.md) (racine de composition, ordre d'initialisation) et l'[ADR 0044](0044-un-package-un-module.md), dont il garde toutes les frontières.

## Contexte

Deux classes de surface empilaient des constructeurs télescopiques : `LocalProjectArchitectureQuery` en déclarait 9 et `MinosCli` 10. Chacun ajoutait un paramètre et déléguait au suivant avec une valeur par défaut (`null` pour `MinosCli`, un analyseur neuf pour `LocalProjectArchitectureQuery`). Le bytecode montrait que 6 des 9 constructeurs de la première et 6 des 10 de la seconde n'avaient aucun appelant : chaque besoin de câblage avait ajouté une surcharge, aucune n'avait jamais été retirée.

`MinosApplication`, racine de composition (ADR 0042), expose 36 méthodes d'instance publiques hors `close()` : 35 accesseurs (dont 31 de services) et la fabrique `indexerRegistry(String)`, soit 32 méthodes de services. Ses services sont appelés par 13 classes de production et 23 classes de test réparties dans 7 modules. Elle est nommée par l'API publique (`Local*Api(MinosApplication)`), et ses accesseurs sont documentés comme signatures publiques (`docs/user/java-api.md` § Ruptures, lot A3).

L'audit relève aussi que `LocalProjectArchitectureQuery` recalcule tout à chaque appel, sans cache.

## Décision

### 1. Un constructeur unique et un point d'entrée nommé

Une classe de surface déclare **un seul constructeur**, qui reçoit tous ses collaborateurs, et **un point d'entrée nommé** qui fixe les valeurs par défaut, selon la convention du module :

- fabrique statique `defaults(...)` quand les valeurs par défaut sont fixes (`ProviderPlatformService.defaults`). Cas de `LocalProjectArchitectureQuery.defaults(ProjectRegistry, CodeKnowledgeSnapshotStore, ProjectDiscoveryService)`, constructeur privé ; les analyseurs sans état sont des champs initialisés en ligne ;
- `builder` quand la plupart des collaborateurs sont facultatifs (`MinosApplication.Builder`). Cas de `MinosCli.builder(ProjectSymbolQuery)`, constructeur privé `MinosCli(Builder)` ; un collaborateur non fourni désactive sa commande comme le faisait `null`, et un mutateur refuse `null`.

Aucun alias déprécié n'est laissé : les appelants, de production et de test, changent dans le même commit. Aucune visibilité n'est élargie, ni réduite au-delà des constructeurs supprimés : les mutateurs publics de `MinosCli.Builder` sont exactement ce qu'ouvraient les anciens constructeurs publics (`projectOperations`, `architectureQuery`, `impactQuery`, plus `builder` et `build`), les autres sont package-private. Un test par réflexion échoue si un second constructeur réapparaît.

### 2. `MinosApplication` regroupée par domaine, sans changer de signature

Les collaborateurs résolus par l'assembleur sont regroupés en deux records package-private (`Stores`, `Indexing`), les services dérivés en deux records privés (`Queries`, `Semantic`). Ces records sont de simples porteurs : ils ne valident rien et ne créent aucune instance. Le constructeur package-private passe de 24 à 9 paramètres. Les accesseurs publics sont rangés par domaine et délèguent au porteur ; ils gardent nom, type et instance retournée. L'ordre d'initialisation et de validation figé par `A2CompositionCharacterizationTest` ne change pas.

La surface publique **reste de 35 accesseurs** (plus `indexerRegistry(String)` et `close()`) : le constat « expose environ 30 services » est traité par décision, pas par déplacement.

Option écartée : des façades publiques `storage()`, `indexing()`, `queries()`, `semantic()` qui remplacent les accesseurs plats. Elle retirait 32 signatures publiques sans réduire le couplage, puisque chaque surface reçoit toujours l'objet entier et que les façades exposent les mêmes services. Sans alias, c'était une rupture en bloc, la deuxième en deux lots après A3. Elle obligeait aussi à réécrire les assertions des tests de caractérisation qui témoignent de l'absence de changement de comportement.

### 3. Pas de cache dans `LocalProjectArchitectureQuery`

Un cache n'est admis que si l'on prouve qu'il est invalidé par tout changement de ses entrées. Le snapshot actif est déjà en cache dans les magasins (fichier et PostgreSQL), sous la clé identifiant + `sha256` du pointeur actif. Ce pointeur est relu à chaque appel, donc les écritures des autres processus sont vues. La découverte du projet, elle, lit l'arborescence vivante, qu'aucune génération ne date. Toutes les vues d'architecture en dépendent. Un cache servirait donc une réponse périmée après une modification de l'arborescence et masquerait les échecs actuels. Il n'est pas ajouté. Seule une mémoïsation des analyses sous la clé (snapshot, découverte) serait prouvable ; le constat est renvoyé à A6, avec une mesure préalable.

## Conséquences

- Les constructeurs publics retirés, trois de `LocalProjectArchitectureQuery` et deux de `MinosCli`, sont des ruptures de source pour un code qui les appelait directement. Ils sont listés dans `docs/user/java-api.md` § Ruptures avec leurs remplaçants. Aucune signature de `com.minos.api` ni de `MinosApplication` ne change.
- Le contrat CLI stable (`MinosCli.run`, ADR 0016) et la sortie des commandes sont inchangés.
- La surface publique de `MinosApplication` reste de 35 accesseurs. Réduire le couplage demande des surfaces qui reçoivent des ports étroits plutôt que l'application entière, ce qui touche les constructeurs publics de `minos-api`. C'est un chantier distinct.
- Les autres classes à constructeurs multiples relevées par le balayage (`IndexingLifecycleService`, `LocalMinosApi`, `LocalProjectImpactQuery`…) suivront la même règle quand elles seront reprises. Elles ne sont pas traitées ici.
