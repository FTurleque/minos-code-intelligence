# Design

## Context

Constats de départ (audit du 8 octobre 2026, `docs/quality/code-audit-constats.md` § 5) : H01 (rapports SpotBugs Maven sans statistiques de classes), H02 (faux rapprochements CPE), H04 (clé NVD refusée), H06 (Gitleaks hors CI), H08 (usages transitifs non déclarés), H09 (plugin IntelliJ hors contrôles), H21 (alertes SpotBugs du plugin). Mesures et commandes : `docs/quality/code-audit-couverture.md`.

## Goals / Non-Goals

**Goals** : que chaque outil d'audit couvre tout son périmètre applicable et le **prouve** par un décompte ; qu'un échec d'outil ne soit jamais lu comme un résultat propre ; que chaque composant non pris en charge reste visible.

**Non-Goals** : rendre un outil bloquant en PR ; corriger les constats ; introduire une nouvelle règle d'architecture obligatoire.

## Decisions

1. **ArchUnit dans `minos-app`, pas dans un nouveau module.** `minos-app` dépend déjà de tous les modules (assemblage) ; un module de tests d'architecture ajouterait une entrée au réacteur et à `check-module-boundaries.py` sans bénéfice. Le test lit les `target/classes` de chaque module depuis la racine (Surefire `workingDirectory`), ce qui associe chaque classe à **son** module sans heuristique de package (`com.minos.runtime` est dans `minos-engine`, `com.minos.runtime.local` dans `minos-runtime-local`). Alternative écartée : importer le classpath de test, qui ne dit pas d'où vient une classe.
2. **Garde d'import complet.** Le test compte les `.class` sur disque et exige qu'elles soient toutes importées ; ArchUnit fait en plus échouer une règle à sujet vide. Une sortie absente, vide ou périmée fait donc échouer le test au lieu de laisser passer les règles.
3. **Règles établies seulement.** Six règles reprennent des décisions acceptées (ADR 0022, 0042 A2, 0044). La lecture stricte de `ALLOWED_DEPENDENCIES` (une classe n'utilise que les modules déclarables) n'est décidée par aucun ADR : elle reste une mesure `@EnabledIfSystemProperty(named = "minos.audit.archunit.strict")`.
4. **Dependency-Check en deux profils non hérités** (`aggregate` à la racine), comme `d2-security` de MORPHEUS : la vue livrée ne mélange pas l'outillage de test. `failBuildOnCVSS=11` (audit, pas contrôle), `failOnError=true` (une erreur n'est pas un résultat). Clé NVD par **nom** de variable (`-DnvdApiKeyEnvironmentVariable`), jamais par valeur. Repli documenté quand la clé manque : mise à jour anonyme par 12.2.2 (anomalie amont #8715 de 13.0.0), base hors dépôt.
5. **Preuve du périmètre SpotBugs par la ligne de commande.** Le plugin Maven 4.10.4.1 n'écrit pas de `ClassStats` ; la ligne de commande (`FindBugs2 -xml:withMessages`) si. Les alertes sont identiques (`instanceHash`) : la relecture prouve le périmètre sans changer les résultats. `audit-report-summary.py --strict` refuse un rapport sans preuve.
6. **Plugin IntelliJ.** Tant que le plugin n'a pas de wrapper Gradle, l'audit le traite par un harnais hors dépôt (javac contre l'IDE cible, JUnit Platform, SpotBugs CLI, POM de reconstitution des dépendances). La cible est un job Gradle dans `code-audit.yml`.

## Risks / Trade-offs

- [ArchUnit lit des sorties de compilation] → un `-pl minos-app` sans `-am` sur un arbre non compilé échoue (voulu, message explicite).
- [PIT sur tout le réacteur est long] → exécution par module et reprise par `-rf :<module>` ; l'historique incrémental de PIT exige un greffon commercial depuis 1.30.
- [Faux positifs Dependency-Check] → suppressions ciblées par purl et CPE, `failBuildOnUnusedSuppressionRule=true`, jamais par motif global.
- Windows et Linux : toutes les mesures du 8 octobre viennent de Windows ; `code-audit.yml` tourne sur `ubuntu-24.04` et n'a pas encore été exécuté.

## Direction des dépendances (ADR 0022)

Aucune dépendance de production ajoutée. ArchUnit est en portée test de `minos-app`, le module d'assemblage, qui dépend déjà de tous les modules.
