# 0045 — Constructeur unique et point d'entrée nommé ; racine de composition regroupée par domaine

Status: Proposed — conception, en attente d'arbitrage avant implémentation ; inventaire et options dans [`ARCHI-SUIVI.md`](../audit/ARCHI-SUIVI.md) (lot 2, A4).

Complète l'audit [`AUDIT-2026-09.md`](../audit/AUDIT-2026-09.md) (constat A4). Prolonge l'[ADR 0042](0042-racine-de-composition.md) (racine de composition, ordre d'initialisation) et l'[ADR 0044](0044-un-package-un-module.md), dont il garde toutes les frontières.

## Contexte

Deux classes de surface empilent des constructeurs télescopiques : `LocalProjectArchitectureQuery` en déclare 9 et `MinosCli` 10. Chacun ajoute un paramètre et délègue au suivant avec une valeur par défaut (`null` pour `MinosCli`, un analyseur neuf pour `LocalProjectArchitectureQuery`). Le bytecode montre que 6 des 9 constructeurs de la première et 6 des 10 de la seconde n'ont aucun appelant : chaque besoin de câblage a ajouté une surcharge, aucune n'a jamais été retirée.

`MinosApplication`, racine de composition (ADR 0042), expose 36 méthodes d'instance publiques. Ses 32 accesseurs de services sont appelés par 13 classes de production et 23 classes de test réparties dans 7 modules. Elle est nommée par l'API publique (`Local*Api(MinosApplication)`), et ses accesseurs sont documentés comme signatures publiques (`docs/user/java-api.md` § Ruptures, lot A3).

L'audit relève aussi que `LocalProjectArchitectureQuery` recalcule tout à chaque appel, sans cache.

## Décision (proposée)

### 1. Un constructeur unique et un point d'entrée nommé

Une classe de surface déclare **un seul constructeur**, qui reçoit tous ses collaborateurs, et **un point d'entrée nommé** qui fixe les valeurs par défaut, selon la convention du module :

- fabrique statique `defaults(...)` quand les valeurs par défaut sont fixes (`ProviderPlatformService.defaults`). Cas de `LocalProjectArchitectureQuery.defaults(ProjectRegistry, CodeKnowledgeSnapshotStore, ProjectDiscoveryService)`, constructeur privé ; les analyseurs sans état deviennent des champs initialisés en ligne ;
- `builder` quand la plupart des collaborateurs sont facultatifs (`MinosApplication.Builder`). Cas de `MinosCli.builder(ProjectSymbolQuery)`, constructeur privé `MinosCli(Builder)` ; un collaborateur absent désactive sa commande comme le faisait `null`.

Aucun alias déprécié n'est laissé : les appelants, de production et de test, changent dans le même commit. Aucune visibilité n'est élargie. Un test par réflexion échoue si un second constructeur réapparaît.

### 2. `MinosApplication` regroupée par domaine sans changer de signature (option recommandée)

Les collaborateurs et services de `MinosApplication` sont regroupés en porteurs internes par domaine (stockage, indexation, requêtes, sémantique). Les accesseurs publics sont rangés par domaine et délèguent au porteur ; ils gardent nom, type et instance retournée. L'ordre d'initialisation figé par `A2CompositionCharacterizationTest` ne change pas.

L'option écartée, des façades publiques `storage()`, `indexing()`, `queries()`, `semantic()` qui remplacent les accesseurs plats, retirerait 32 signatures publiques sans réduire le couplage : chaque surface reçoit toujours l'objet entier, et les façades exposent les mêmes services. Sans alias, ce serait une rupture en bloc, et elle réécrirait les assertions des tests de caractérisation qui témoignent de l'absence de changement de comportement.

### 3. Pas de cache dans `LocalProjectArchitectureQuery`

Un cache n'est admis que si l'on prouve qu'il est invalidé par tout changement de ses entrées. Le snapshot actif a une identité observable à faible coût et sûre entre processus : le `SnapshotDescriptor` relu à chaque appel, déjà utilisé comme clé par les magasins. La découverte du projet, elle, lit l'arborescence vivante, qu'aucune génération ne date. Toutes les vues d'architecture en dépendent. Un cache servirait donc une réponse périmée après une modification de l'arborescence et masquerait les échecs actuels. Il n'est pas ajouté ; une mémoïsation limitée aux analyses reste possible, sous condition d'une mesure préalable.

## Conséquences

- Les constructeurs publics retirés, trois de `LocalProjectArchitectureQuery` et deux de `MinosCli`, sont des ruptures de source pour un code qui les appelait directement. Ils sont listés dans `docs/user/java-api.md` § Ruptures. Aucune signature de `com.minos.api` ne change.
- Le contrat CLI stable (`MinosCli.run`, ADR 0016) et la sortie des commandes sont inchangés.
- Avec l'option 2, la surface publique de `MinosApplication` reste de 36 méthodes. Réduire le couplage demande des surfaces qui reçoivent des ports étroits plutôt que l'application entière, ce qui touche les constructeurs publics de `minos-api`. C'est un chantier distinct.
- Les autres classes à constructeurs multiples relevées par le balayage (`IndexingLifecycleService`, `LocalMinosApi`, `LocalProjectImpactQuery`…) suivent la même règle quand elles seront reprises. Elles ne sont pas traitées ici.
