# Tracer un échec interne : brancher une surface

Un échec interne (une exception que la surface ne sait pas expliquer) est rendu opaque à l'appelant et tracé pour
l'opérateur. Le comportement vu de l'opérateur est décrit dans
[Diagnostiquer un échec interne](../user/diagnostic-d-un-echec-interne.md) ; la décision, dans
[SEC-0084](../architecture/adr/SEC-0084-journal-de-diagnostic-prive-et-opt-in-des-echecs-internes.md).

## Les composants

| Composant | Module, package | Rôle |
|---|---|---|
| `InternalFailureTrace` | `minos-domain`, `com.minos.diagnostics` | Rendu pur : référence, ligne stderr, texte d'une entrée de journal, bornes. Aucune E/S |
| `WorkerDiagnostic` | `minos-domain`, `com.minos.diagnostics` | Diagnostic qu'un worker envoie sur sa sortie, à grammaire fermée (voie WSL) |
| `InternalFailureJournal` | `minos-engine`, `com.minos.io` | Le journal privé : réglage, un fichier par entrée, purge, indisponibilité |
| `InternalFailureReporter` | `minos-application`, `com.minos.application.diagnostics` | Façade des surfaces ; son API ne nomme aucun type du moteur |

Une surface ne nomme ni `InternalFailureJournal` ni aucun type de `com.minos.io` : les tables A11 de
`scripts/architecture/check-module-boundaries.py` et le pom de la surface restent inchangés.

## Brancher une surface

1. Reconnaître l'échec interne dans la branche qui rend déjà l'erreur opaque ; ne jamais tracer une erreur d'usage ni un
   refus attendu.
2. Construire un `InternalFailureReporter` depuis le répertoire de données et l'environnement du processus
   (`new InternalFailureReporter("<surface>", home, System.getenv(), System.getProperties())`), une fois par processus ;
   à défaut de répertoire, `InternalFailureReporter.withoutJournal("<surface>")`.
3. Appeler `reporter.report("<opération>", exception)` : le nom d'opération est un libellé fixe (nom d'outil, de
   commande, de route), jamais une valeur de l'appelant. L'appel écrit l'entrée si le journal est actif et ne lève jamais.
4. Écrire `trace.line("<sujet>", "<clé>", "<opération>")` par le journal de la surface (stderr) et ajouter
   `trace.reference()` à la réponse opaque quand le contrat le permet.

Le journal est silencieux quand il est éteint ou indisponible : la réponse de la surface ne dépend pas de lui.

## Ce qui ne doit jamais entrer

Message d'exception non filtré, valeur d'argument, variable d'environnement, en-tête, chemin absolu. Le rendu applique
`PublicErrorMessages` aux messages et ne retient que les noms de classes, de méthodes, de fichiers sources et les numéros
de ligne.

## Tests

`InternalFailureTraceTest`, `InternalFailureJournalTest` (dont deux JVM filles), `InternalFailureReporterTest`, un test
par surface branchée (`MinosMcpInternalFailureTraceTest`, `CliInternalFailureTraceTest`,
`MinosApiInternalFailureTraceTest`, `WslLinuxWorkerDiagnosticTest`, `WslIndexWorkerDiagnosticTest`). Capturer les
journaux par `LogCapture` (`minos-engine`, `com.minos.testsupport`).

## Erreur d'usage ou échec interne : `UsageException`

Une surface (MCP, API Java) ne rend un message d'argument à son appelant que pour une `UsageException`
(`minos-domain`, `com.minos.diagnostics`), sous-classe non finale d'`IllegalArgumentException` : toute autre
`IllegalArgumentException` levée pendant l'appel, sous-classes du JDK comprises (`NumberFormatException`,
`InvalidPathException`, `PatternSyntaxException`), est un défaut interne, rendu opaque et tracé comme ci-dessus.

- Lever `UsageException` dans la validation d'une entrée de l'appelant : message non vide, qui nomme l'argument, **sans
  cause** (une cause fait traiter l'erreur comme interne) et sans recopier une valeur qui pourrait être un chemin ou un
  secret. Les surfaces filtrent encore le message par `PublicErrorMessages`.
- Lever une `IllegalArgumentException` simple pour un invariant interne : c'est un défaut, jamais un conseil à l'appelant.
- Une erreur d'usage typée hérite de `UsageException` (`DocumentNotFoundException`) ; un refus attendu qui n'est pas une
  erreur d'argument reste une `IllegalStateException` dédiée (`IndexChangedException`), que l'API Java rend `UNAVAILABLE`
  sans la tracer.
- Un `catch (IllegalArgumentException …)` existant reste valide : le type est un sous-type.

Une validation atteignable depuis un outil ou une méthode publique et restée en `IllegalArgumentException` simple devient
une erreur générique côté MCP et `EXECUTION_FAILURE` côté API : les tests `MinosMcpUsageErrorTest` et
`MinosApiUsageErrorTest` tiennent la table des entrées invalides documentées.
