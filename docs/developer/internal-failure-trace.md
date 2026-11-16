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
