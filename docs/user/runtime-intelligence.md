# Runtime & Dynamic Intelligence — M26

M26 rapproche des observations d’exécution d’un snapshot statique MINOS sans prétendre qu’une trace partielle décrit tout ce qui s’est exécuté.

## Contrat de vérité

Chaque import et chaque lecture porte :

```text
nature     OBSERVED_PARTIAL
exhaustive false
```

Une observation absente signifie uniquement « non observée dans les sessions sélectionnées ». Elle ne prouve jamais la non-exécution d’un symbole, d’une ligne ou d’un appel. Les observations runtime ne modifient ni le snapshot statique actif, ni les capabilities d’un provider.

## Format d’import v1

Le fichier est un TSV UTF-8 strict, sans BOM, sans ligne vide interne, borné à 64 MiB et un million d’observations. Les neuf premières lignes sont ordonnées :

```text
minos-runtime-observation-v1
session\t<session-id>
project\t<project-uuid>
snapshot\t<active-snapshot-id>
started\t<ISO-8601 instant>
ended\t<ISO-8601 instant>
collector\t<collector-id>\t<collector-version>
environment\t<environment-id>
completeness\tPARTIAL
```

Trois observations sont acceptées :

```text
symbol\t<symbol-key?>\t<qualified-name?>\t<file-id?>\t<line?>\t<hits>\t<duration-nanos>
call\t<src-key?>\t<src-qname?>\t<src-file?>\t<src-line?>\t<dst-key?>\t<dst-qname?>\t<dst-file?>\t<dst-line?>\t<hits>\t<duration-nanos>
line\t<file-id>\t<line>\t<hits>
```

Au moins une identité `symbol-key`, `qualified-name` ou `file-id` est requise par référence. Les `file-id` sont relatifs au projet, normalisés avec `/`, sans chemin absolu, `.` ou `..`. Les compteurs sont positifs et bornés ; les durées sont non négatives et bornées. La fenêtre temporelle maximale est de 366 jours.

`PARTIAL` est la seule complétude acceptée en M26. `COMPLETE` échoue fermé : MINOS ne dispose pas d’une preuve universelle permettant ce claim.

## Importer

Le projet doit déjà être enregistré et posséder un snapshot actif :

```powershell
minos.cmd runtime import my-project --file .\runtime.tsv --format json
```

L’UUID projet et le `snapshot` du fichier doivent correspondre exactement au registre et au snapshot actif. La réponse expose le SHA-256 source, les nombres de références `RESOLVED`, `AMBIGUOUS` et `UNRESOLVED`, l’heure d’import et `alreadyPresent`.

Une même `sessionId` et le même fichier constituent un import idempotent. Réutiliser cette identité avec un contenu différent est refusé : une session acceptée est immuable.

## Lire les sessions et rapports

```powershell
minos.cmd runtime sessions my-project --limit 20 --format json
minos.cmd runtime report my-project --session run-2026-07-29 --limit 20 --format json
minos.cmd runtime symbol my-project --symbol <static-symbol-id> --session run-2026-07-29 --format json
```

Le rapport agrège uniquement les sessions du snapshot actif et fournit :

- ratio de symboles statiques ayant une corrélation runtime observée ;
- lignes observées, hits et durées déclarées ;
- hot paths et appels observés, bornés et déterministes ;
- nombres de corrélations résolues, ambiguës et non résolues ;
- provenance temporelle, collector, environnement, source SHA-256 et snapshot.

Le rapport symbole fournit les exécutions, lignes et appels entrants/sortants observés. `absenceMeaning: NOT_OBSERVED_IN_SELECTED_PARTIAL_SESSIONS` empêche de confondre absence de trace et preuve de non-exécution.

Si le snapshot actif a changé, la sélection explicite d’une session ancienne échoue avec un diagnostic d’alignement. La session est conservée comme évidence immuable, mais elle n’est pas fusionnée silencieusement avec le nouveau snapshot.

## Stockage local

Les sessions corrélées sont stockées sous `MINOS_HOME/runtime-observations`. Le store est local, verrouillé, publié atomiquement, vérifié par SHA-256 et borné par défaut à 128 sessions et 1 GiB par projet, avec 64 MiB maximum par session encodée. Les fichiers et répertoires symboliques sont refusés sur la frontière de stockage gérée.

## MCP read-only

Le MCP expose :

```text
minos_runtime_sessions
minos_runtime_report
minos_runtime_symbol
```

Il n’expose pas l’import. Cette séparation garde les clients MCP en lecture seule et réserve toute mutation à une action opérateur explicite via la CLI.

## Limites M26

M26 ne fournit pas un agent universel, un profiler CPU natif, une causalité distribuée exhaustive, un contrôle multi-tenant ou une collecte hébergée. Il ne déduit pas CFG, def-use, interprocedural data-flow ou security capabilities d’une trace. Ces faits restent régis par leurs providers et leur qualification propres.
