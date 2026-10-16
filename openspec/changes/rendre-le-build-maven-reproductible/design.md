# Design

## Context

Sprint 5 de l'audit du 10 octobre 2026 : **AUD-DEP-11**. HEAD analysé : `2d1cc214` (`develop`). Le wrapper Maven fait foi (Maven 3.10.0 d'après le relevé ; la politique dit « 3.9+ par le wrapper »).

## État vérifié au HEAD

### AUD-DEP-11 — confirmé

- Aucune occurrence de `outputTimestamp` ni de `requirePluginVersions` dans le dépôt, hors `docs/audit/`.
- `pom.xml:183-198` (l'audit citait `:182-196`) : enforcer avec `requireJavaVersion [24,25)` et `requireMavenVersion [3.9,4.0)` seulement.
- `minos-app/pom.xml:57-66` : shade, sans horodatage fixé.
- Jamais déclarés dans les 15 POM : `maven-clean-plugin`, `maven-resources-plugin`, `maven-install-plugin`, `maven-deploy-plugin`, `maven-site-plugin`. `maven-surefire-plugin` et `jacoco-maven-plugin`, sans version dans les modules, héritent de la racine (`pom.xml:208-209`, `:221-222`).
- Aucun `check-*.py` ne lit l'enforcer.

**Non mesuré** : aucun Maven lancé pendant l'analyse. Le comportement de `requirePluginVersions` avec le Maven du wrapper, et le résultat d'une double construction (quelles entrées du jar diffèrent réellement), sont à établir en tâche 1.1.

### Écarts avec l'action proposée par l'audit

1. **`requirePluginVersions` contrôle aussi les phases `clean`, `deploy` et `site`.** Il faut donc épingler `maven-deploy-plugin` et `maven-site-plugin` même s'ils ne sont jamais exécutés, sinon la règle échoue. L'audit n'en cite que trois.
2. **Sites oubliés : les scripts qui construisent un livrable.** `scripts/release/build-windows-distribution.ps1:196` et `:223` et `scripts/ci/qualify-docker-release.sh:28` lancent Maven ; si la date n'est fixée que « par le script de release », l'image Docker de qualification reste non reproductible.
3. **La reproductibilité ne peut être exigée que des JAR.** Zip, installateur et image `jpackage` ne relèvent pas de `project.build.outputTimestamp`. L'exigence le dit, pour ne pas promettre plus.
4. **Conflit de fichier avec `migrer-le-socle-vers-java-25`** : même bloc `enforcer` du POM racine. Ordre de fusion à fixer ; aucune dépendance logique.
5. **Une date écrite en dur dans le POM vieillit.** L'audit propose une propriété « fixée par le script de release ». Si le POM porte une valeur fixe, un build local et un build de release du même commit divergent dès que l'un des deux la surcharge. La valeur doit venir **du commit** dans tous les chemins (D1).

## Goals / Non-Goals

**Goals** : deux constructions du même commit, sur la même plateforme, donnent le même jar ombré ; aucun plugin n'a une version implicite ; la preuve est automatique.

**Non-Goals** : reproductibilité des installateurs et des images ; identité binaire garantie entre systèmes ; montée de plugins.

## Mesures de la tâche 1.1 (2026-10-15, poste Windows, JDK 25)

- Versions résolues par le wrapper (`help:effective-pom`) : `maven-clean-plugin` 3.5.0, `maven-resources-plugin` 3.5.0, `maven-jar-plugin` 3.5.1 (propriété existante, sans entrée dans `pluginManagement`), `maven-install-plugin` 3.2.0, `maven-deploy-plugin` 3.2.0, `maven-site-plugin` 3.22.0. `requirePluginVersions` seul les nomme tous (rouge à la racine puis sur `minos-domain`).
- **Écart avec l'état vérifié de l'analyse : le wrapper (Maven 3.10.0) fixe déjà `project.build.outputTimestamp=1980-02-01T00:00:00Z` par défaut.** Deux constructions du HEAD `83dd1fbb` par le wrapper donnent le même jar ombré (`803369f7…`). Le défaut n'est **pas** reproduit avec le wrapper. Il l'est avec Maven 3.9.11, que l'enforcer accepte (`[3.9,4.0)`) : la propriété est absente (`help:evaluate` → `null`) et 1 119 entrées du jar ombré diffèrent par leur date. La reproductibilité dépendait donc de la version de Maven ; elle est désormais écrite dans le POM.
- Jar Linux contre jar Windows : non comparé (aucun artefact Linux du même commit sur le poste) ; tâche 3.5.

## Decisions

### D1. Source de l'horodatage — **option A retenue par le propriétaire le 2026-10-15**

- **Option A (recommandée) : date du commit, calculée par le build.** `project.build.outputTimestamp` reçoit la date du dernier commit par une propriété que le wrapper lit d'un fichier de configuration Maven du dépôt ou que les scripts passent (`-Dproject.build.outputTimestamp=<date ISO du commit>`), avec une **valeur par défaut fixe et ancienne** dans le POM pour un build sans Git (archive source). Les trois scripts qui construisent un livrable passent la date du commit ; la CI aussi.
- **Option B : valeur fixe dans le POM**, mise à jour à chaque release par la procédure de release. Plus simple ; les jars de développement portent tous la date de la dernière release, ce qui est sans conséquence fonctionnelle mais déroutant.

Dans les deux cas, aucun plugin tiers n'est ajouté pour lire Git (pas de nouvelle dépendance de build sans raison écrite).

### D2. Épingler ce qui est résolu aujourd'hui

`pluginManagement` de la racine reçoit les cinq plugins manquants, à la version **effectivement résolue par le wrapper au HEAD** (relevée par `./mvnw help:effective-pom` en tâche 1.1, pas choisie de mémoire). `requirePluginVersions` est ajouté à l'enforcer avec ses options par défaut (refus de `LATEST`, `RELEASE` et des instantanés).

### D3. La preuve : double construction comparée

Un script (`scripts/quality/check-reproducible-build.py`) construit deux fois le jar ombré dans deux répertoires de travail propres, à la même date de commit, et compare les SHA-256 ; en cas d'écart, il liste les entrées qui diffèrent. Son auto-test (comparaison de deux archives simulées) est câblé dans `invariants` ; la double construction réelle, coûteuse, tourne dans un job dédié de `pr-ci.yml` ou dans un workflow de release (D4), **sans** ajouter une seconde exécution du `verify` complet (`check-single-execution.py`) : `package -DskipTests` sur `minos-app` et ses amonts.

### D4. Où tourne la preuve — **recommandation retenue par le propriétaire le 2026-10-15** (`reproducible-build.yml` : PR qui touchent le build Maven, `workflow_dispatch`, appel par `release-windows.yml` avant `publish`)

Recommandation : job non exigé de `pr-ci.yml`, limité aux PR qui touchent un `pom.xml` ou la configuration Maven du dépôt, et toujours exécuté par le workflow de release avant publication. Alternative : à chaque PR (plus sûr, plus long). Il ne devient pas un check exigé sans passer par `.github/required-checks.json` et un premier run.

### D5. Linux et Windows : mesurer, puis dire

La tâche 1.1 compare le jar ombré construit sous Linux et sous Windows au même commit. S'ils sont identiques, c'est écrit ; sinon l'écart (fins de ligne de ressources filtrées, ordre d'entrées) est décrit et l'exigence reste « par plateforme ».

## Direction des dépendances

Aucun module, aucune dépendance. `check-module-boundaries.py` est rejoué parce qu'il contrôle la mise en page de build des POM, profils compris.

## Qualification des capacités

- Jar ombré reproductible sur une plateforme : **non qualifiée** au HEAD ; qualifiée par D3.
- Identité entre plateformes : **non qualifiée**, mesurée en 1.1 puis déclarée.
- Autres livrables : **non supportée**, déclarée.

## Windows et Linux

Les scripts PowerShell de release passent la date du commit sous Windows (`git log -1 --format=%cI`, argument `-D` cité) ; le script de qualification Docker sous Linux. Les `.ps1` restent en CRLF.

## Risks / Trade-offs

- Une entrée non déterministe résiduelle (fichier généré contenant une date, ordre de fusion des services à l'ombrage) peut résister à l'horodatage : la tâche 1.1 liste les entrées qui diffèrent avant de promettre.
- `requirePluginVersions` peut révéler un plugin tiré indirectement : chaque ajout à `pluginManagement` est une version relevée, pas inventée.
- Temps de CI : deux `package` de plus sur les PR concernées.
