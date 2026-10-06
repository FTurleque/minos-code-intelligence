# Prompt — A3, A4 et A6 : les constats Architecture de sévérité moyenne (à coller dans une session Claude Code à la racine du dépôt)

---

Tu es l'orchestrateur du chantier **Architecture — sévérité moyenne** de l'audit `docs/audit/AUDIT-2026-09.md` sur `minos-code-intelligence` (Java 24, Maven, wrapper `./mvnw`). Trois constats ouverts : **A3** (packages éclatés entre modules, alias CLI dépréciés), **A4** (classes-dieux et constructeurs télescopiques), **A6** (mémoire et scalabilité).

Les constats hauts A1 et A2 sont déjà clos : l'application ne dépend plus d'aucun adaptateur, le câblage vit dans `minos-bootstrap`, et `scripts/architecture/check-module-boundaries.py` verrouille les frontières. Ce chantier s'appuie sur cet acquis et ne doit pas le défaire.

Tu travailles avec **un agent d'implémentation** et **un agent de supervision qui tourne en parallèle du début à la fin**.

| Agent | Rôle |
|---|---|
| `impl-archi` | implémente A3, puis A4, puis A6, par commits réversibles |
| `verif-archi` | supervision continue : bug nouveau, régression, convention non respectée, duplication, code laissé en double après un déplacement. N'écrit pas de code de production |

`verif-archi` est lancé **en même temps** qu'`impl-archi`. Un constat bloquant renvoie l'implémenteur au travail avant qu'il n'avance.

## Règles non négociables

1. **Aucun changement de comportement.** Les tests de caractérisation d'A2 (`A2SurfaceCharacterizationTest`, `A2CompositionCharacterizationTest`, les 12 golden) doivent rester verts et **identiques**, du premier au dernier commit. Un golden qui bouge est une régression tant que le contraire n'est pas prouvé.
2. **Builds locaux ciblés** pendant le travail (`./mvnw -pl <module> -am test`). Un `clean verify` complet par lot, à la fin du lot.
3. **Branche par lot** : `archi/a3-packages`, `archi/a4-surfaces`, `archi/a6-scalabilite`, chacune depuis `develop`, dans son propre worktree. Les lots sont **séquentiels** : A3 déplace des fichiers, A4 touche des signatures, A6 touche des formats — les mener en parallèle produirait des conflits ingérables.
4. **Test rouge avant correctif** partout où un comportement est en jeu ; pour un déplacement pur, la preuve est la table de correspondance et les golden inchangés.
5. **Pas d'élargissement.** Ce qui sort du périmètre part dans `docs/audit/ARCHI-SUIVI.md`, section « à traiter plus tard ». Les résidus A7 et A8 (sévérité basse) n'en font pas partie — sauf A7, que A3 touche forcément (voir plus bas).
6. **Réponds en français.**

---

## Lot 1 — A3 : un package, un module

**État** : neuf packages sont éclatés entre plusieurs modules (`com.minos.cli` entre app et cli, `com.minos.dynamic` entre application, domain et engine, `com.minos.hosted` entre les mêmes, `com.minos.orchestration` entre application et engine, `com.minos.runtime` entre application et runtime-local, `com.minos.semantic`, `com.minos.store`, `com.minos.integration.nexus`…). Le chantier A2 a déplacé des ports vers `minos-engine` : **l'inventaire de l'audit est daté, refais-le** avant toute décision. S'y ajoutent les alias CLI dépréciés (`cli.ProjectOperations`, `cli.ProjectSymbolQuery`, `cli.LocalProject*`), que `MinosCliRunner` instancie encore, et les accès `package-private` entre jars relevés pendant A2.

**Règle de méthode, la plus importante du lot** : préfère **déplacer une classe d'un module à l'autre** plutôt que renommer son package. Un déplacement laisse le nom pleinement qualifié intact — donc aucune rupture pour un consommateur de l'API Java — et ne change que le jar qui la porte. Ne renomme un package que si aucun déplacement n'est possible ; dans ce cas, la rupture est documentée dans l'ADR et dans `docs/user/java-api.md`, et listée dans la PR.

**Attendu** :
- aucun package n'existe dans deux modules ;
- les accès `package-private` entre jars ont disparu (une visibilité de package qui traverse un jar est un couplage invisible au compilateur) ;
- les alias CLI dépréciés sont supprimés et leurs appelants pointent sur le type d'origine ;
- `check-module-boundaries.py` gagne une règle **« aucun package éclaté »** avec son cas d'auto-test, et — c'est **A7**, qu'on ferme au passage puisqu'on y est — sa liste de modules est confrontée aux `<modules>` du POM racine, et échoue si les deux divergent.

**Tests** : les golden inchangés, la suite complète des modules touchés verte, et le nouveau contrôle prouvé rouge sur l'état d'avant.

---

## Lot 2 — A4 : surfaces d'objet

**État** : `MinosApplication` expose une trentaine de services ; `LocalProjectArchitectureQuery` a neuf constructeurs télescopiques et recalcule découverte, snapshot et topologie à chaque appel, sans cache ; `MinosCli` a neuf constructeurs.

**Attendu** :
- les constructeurs télescopiques disparaissent au profit d'un constructeur unique plus un point d'entrée nommé (fabrique ou `builder`, en suivant ce que le code utilise déjà) ;
- **aucun alias déprécié n'est laissé derrière.** On vient de supprimer ceux de la CLI parce qu'ils ne mouraient jamais : ne recrée pas le problème. Les appelants sont mis à jour dans le même commit ;
- si une signature publique de `minos-api` doit changer, c'est une rupture : elle est listée, justifiée, documentée dans `docs/user/java-api.md`, et elle apparaît en tête du corps de la PR ;
- `MinosApplication` est regroupée par domaine d'accesseurs (ou déléguée à des façades cohérentes) sans changer ce que chaque accesseur retourne.

**Le cache de `LocalProjectArchitectureQuery` est conditionnel** : tu ne l'ajoutes que si tu peux prouver qu'il est invalidé par tout changement de snapshot actif et qu'il ne peut jamais servir une réponse périmée. Si la preuve n'est pas nette, tu ne le fais pas : tu l'écris dans le suivi et il repart en constat de performance. Un cache qui répond faux vaut bien pire qu'un recalcul lent.

**Tests** : un test par point d'entrée refondu, les golden inchangés, et un test qui échoue si un ancien constructeur réapparaît.

---

## Lot 3 — A6 : mémoire et scalabilité

**État** (statut PLAUSIBLE dans l'audit : à confirmer par la mesure avant de coder) : les snapshots sont chargés entièrement en mémoire (`InMemoryCodeKnowledgeStore`, cache de 512 Mo) ; le codec écrit les chaînes en UTF-16, ce qui double leur taille sur disque ; le plafond des snapshots persistés (256 Mo) est inférieur à la limite des artefacts SCIP (512 Mo), donc l'échec arrive tard ; `HybridSearchService` re-normalise tout le corpus à chaque requête ; `ImpactAnalysisService` n'applique `maxResults` qu'après une traversée complète.

**Commence par mesurer.** Le dépôt a un répertoire `benchmarks/` : sers-t'en, ou ajoutes-y ce qu'il faut. Produis des chiffres sur un projet réaliste — taille du snapshot sur disque et en mémoire, temps et allocations d'une requête hybride, coût d'une analyse d'impact profonde. **Sans mesure avant/après, aucun changement de ce lot n'est recevable.**

**Puis traite dans cet ordre, en t'arrêtant là où le rapport bénéfice/risque se retourne** :
1. **Alignement des plafonds** : un artefact SCIP qui ne pourra pas être persisté doit être refusé tôt, avec un message clair, plutôt qu'après le travail d'ingestion.
2. **Encodage des chaînes** : passer le codec en UTF-8. C'est un **changement de format persisté** : version de format obligatoire, lecture des deux formats ou reconstruction explicite à la lecture, et test qui ouvre un snapshot écrit par la version précédente. Un snapshot illisible après mise à jour est un incident, pas une optimisation.
3. **Traversée d'impact** : appliquer les bornes pendant la traversée, pas après, sans changer les résultats retournés pour les cas déjà bornés — les golden le prouvent.
4. **Recherche hybride** : normalisation mise en cache ou index inversé, à condition que le classement reste identique. Si le classement bouge, c'est un changement de comportement produit : tu t'arrêtes et tu me demandes.
5. **Snapshot paginé ou mappé en mémoire** : c'est une refonte, pas une correction. Si la mesure la justifie, écris un ADR et arrête-toi là. Ne l'implémente pas dans ce chantier.

---

## Consigne pour `verif-archi` (supervision continue, en parallèle)

> Tu inspectes après chaque commit annoncé, et au moins toutes les dix minutes, avec `git diff` et `git log -p` sur le worktree du lot en cours. Tu n'écris pas de code de production. **Vérifie chaque constat contre le fichier réel du dépôt avant de le déclarer** — un diff lu sur une copie périmée produit de faux bloquants.
>
> **Bug nouveau et régression** : un déplacement qui change une résolution de ressource (`getResource`, `ServiceLoader`, `META-INF/services`, chemins de test) ; une visibilité élargie « pour que ça compile » (`public` là où `package-private` suffisait, ou l'inverse qui casse à l'exécution) ; un ordre d'initialisation ou de fermeture modifié ; une exception avalée ; une ressource non fermée ; un format persisté changé sans version ni test de relecture ; un cache qui peut servir une réponse périmée ; une borne appliquée trop tôt qui change un résultat.
>
> **Conventions** : la convention qui fait foi est celle du module touché, pas la tienne. Vérifie le nommage des classes et des packages, l'emplacement des ports et des adaptateurs (règles A2), la langue de la Javadoc (ne pas mélanger français et anglais dans un même fichier), les messages d'erreur passés par `PublicErrorMessages` et sans chemin absolu, `Locale.ROOT` sur toute comparaison de casse, les I/O sous `MINOS_HOME` par `PrivateLocalStorage`/`DurableAtomicFile`/`ConfinedFileOpener`, aucun `instanceof` sur un fournisseur, aucun nouveau numéro de jalon dans un nom de fichier (ADR 0043).
>
> **Duplication** : c'est le risque propre à ce chantier. Une classe déplacée dont la version d'origine reste en place ; deux implémentations du même port qui coexistent sans que l'ancienne soit retirée ; un helper recopié une fois de plus (`requireText`, `sha256`, l'écriture JSON à la main — l'audit en compte déjà quinze, quatre et quatre : **une copie supplémentaire est un bloquant**, la bonne réponse est de réutiliser l'existant) ; un test dupliqué au lieu d'être déplacé. Après chaque lot, compte les classes du module avant et après : un total qui monte alors que le lot était un déplacement mérite une explication.
>
> **Périmètre et acquis** : aucun fichier hors du lot en cours, aucune dépendance Maven ajoutée sans justification, aucune règle de `check-module-boundaries.py` assouplie, et les frontières issues d'A2 toujours vertes.
>
> Après chaque commit : exécute les gates (`check-module-boundaries.py`, `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`, `check-jacoco.py` quand le lot a tourné) et compare aux chiffres relevés avant le premier commit. Rejoue deux mutations témoins — une violation de frontière, un package éclaté — et vérifie qu'elles sortent en erreur, puis `git checkout -- .`.
>
> Chaque constat : identifiant, fichier et ligne, ce qui casse, un scénario concret, une sévérité (bloquant / à corriger / remarque). Tenue à jour de `docs/audit/ARCHI-SUIVI.md`.

---

## Fin de chantier : ouvrir les PR

**À la fin, tu ouvres les pull requests — c'est demandé explicitement et ce n'est pas optionnel.** Une PR par lot, vers `develop`, dans cet ordre : A3, puis A4, puis A6, chacune ouverte seulement quand son lot est complet, inspecté sans constat ouvert, et son `clean verify` vert en local. Ouvre-les **l'une après l'autre** : la suivante est rebasée sur la précédente une fois celle-ci verte, pour ne pas faire tourner trois pipelines sur des bases divergentes.

Chaque PR est ouverte **en brouillon** et son corps contient, dans cet ordre :

1. le constat traité et son énoncé dans l'audit ;
2. les ruptures d'API publique, s'il y en a, en tête et en clair — ou « aucune » ;
3. ce qui a changé, par commit ;
4. la preuve : golden inchangés, gates verts, tests rouges→verts, et pour A6 les chiffres avant/après ;
5. les constats levés par `verif-archi` et leur résolution ;
6. ce qui a été laissé de côté, avec son renvoi.

Puis tu me rends la main avec : les liens des trois PR, l'état de leur CI, et la liste de ce qui reste ouvert. Tu ne fusionnes rien.

## Définition de terminé

- aucun package éclaté, aucun accès package-private entre jars, alias CLI supprimés, règle et auto-test en place, A7 fermé au passage ;
- aucun constructeur télescopique dans les classes visées, aucun alias déprécié laissé derrière, ruptures d'API documentées ;
- A6 mesuré avant et après, changements limités à ce que la mesure justifie, format persisté versionné et relu par un test ;
- les 12 golden identiques, tous les gates verts, aucun test désactivé ;
- `docs/audit/ARCHI-SUIVI.md` à jour ;
- **les trois PR sont ouvertes** et leurs liens me sont donnés.

---

*Références : `docs/audit/AUDIT-2026-09.md` (A3, A4, A6, A7), `docs/adr/0042-racine-de-composition.md`, `docs/adr/0043-retrait-des-artefacts-de-jalon.md`, `scripts/architecture/check-module-boundaries.py`, `benchmarks/`. Méthode et format de suivi : `SPRINT-2-SUIVI.md` et `CI-HYGIENE-SUIVI.md`.*
