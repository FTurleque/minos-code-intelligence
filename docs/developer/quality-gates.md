# Quality gates MINOS

MINOS mesure la couverture et qualifie les frontières critiques avec des gates reproductibles. Un pourcentage global n'est pas un objectif produit : les seuils ciblent les responsabilités dont une régression serait significative.

Dernière réconciliation : **9 août 2026**, campagne post-audit #132 / PR #135.

## Gate de PR autoritatif

`.github/workflows/pr-ci.yml` s'exécute sur les PR et pushes `main` / `develop`.

La matrice obligatoire couvre :

- Ubuntu : Java 24, PostgreSQL/pgvector Testcontainers réel et fail-closed, Maven `clean verify`, JaCoCo complet et Product Facts ;
- Windows : Java 24, Maven `clean verify`, JaCoCo hors scope PostgreSQL déjà qualifié sur Linux et Product Facts ;
- OSV Scanner bloquant ;
- vérification de l'immutabilité des références GitHub Actions ;
- installation de `bubblewrap`, util-linux et du profil AppArmor officiel `bwrap-userns-restrict` sur Ubuntu afin que la qualification du worker sandbox Linux puisse réellement exercer les namespaces non privilégiés.

Les workflows spécialisés M19, M20, IntelliJ et Windows Installer complètent cette matrice selon les chemins modifiés. Les workflows de publication restent séparés et ne remplacent jamais la qualification de PR.

## Supply-chain des workflows

Le gate reproductible est :

```text
python scripts/quality/check-workflow-pins.py
```

Toute action externe durable doit être référencée par un SHA de commit immuable. Le commentaire de version (`# vN`) reste présent pour la lisibilité humaine. Les installations Chocolatey utilisées dans le packaging doivent également fixer leur version.

La supply-chain produit applique le même principe : images de base par digest OCI, launcher Coursier par commit immuable + SHA-256 attendu, et binaires providers téléchargés avec checksum attendu avant exécution.

## JaCoCo

Le reactor exécute `jacoco:prepare-agent` et produit un rapport par module pendant `verify`. `minos-app` produit en plus le rapport agrégé :

```text
target/site/jacoco-aggregate/index.html
target/site/jacoco-aggregate/jacoco.xml
```

Le gate reproductible est :

```text
python scripts/quality/check-jacoco.py
```

Le résultat machine-readable est écrit par défaut dans :

```text
target/m21-quality/jacoco-gate.json
```

### Seuils ciblés actuels

| Scope | Ligne | Branche |
|---|---:|---:|
| domaine / invariants | 35 % | 20 % |
| persistance + cache + indexes | 50 % | 35 % |
| résolution projet | 70 % | 50 % |
| API publique | 31 % | 21 % |
| mapping MCP | 30 % | 20 % |
| Program Graph | 50 % | 30 % |
| provider Java avancé | 45 % | 25 % |
| impact / sécurité avancés | 47 % | 27 % |
| semantic vector store | 45 % | 20 % |
| provider sémantique Ollama | 52 % | 32 % |
| recherche hybride sémantique | 50 % | 30 % |
| API avancée M19/M20 | 45 % | 25 % |
| catalogue MCP M19/M20 | 50 % | 30 % |
| plateforme provider polyglotte M24 | 30 % | 15 % |
| indexation remote/distribuée M25 | 47 % | 27 % |
| runtime dynamique M26 | 55 % | 35 % |
| control plane hosted/team M27 | 45 % | 25 % |
| routing backend M29 | 55 % | 30 % |
| sélection storage M30 | 52 % | 32 % |
| PostgreSQL/pgvector M30 | 47 % | 27 % |

Le seuil API publique reste supérieur au baseline historique 30/20 tout en restant soutenu par la couverture mesurée. Une baisse de seuil exige une justification documentée dans la PR. Une hausse doit être soutenue par des tests qui prouvent un comportement utile, pas par du code artificiellement exercé pour augmenter un compteur.

## Preuves fonctionnelles séparées

Une ligne couverte ne prouve pas un contrat fonctionnel. JaCoCo reste complémentaire des preuves suivantes :

- replays CLI/API/MCP ;
- fixtures providers et Program Graph ;
- précision/rappel et vérités terrain contrôlées ;
- promotion de snapshot et états STALE/recovery ;
- budgets de contexte ;
- PostgreSQL/pgvector réel ;
- OSV et supply-chain ;
- packaging et smoke tests ;
- IntelliJ Plugin Verifier ;
- installateur Windows exact-head ;
- tests négatifs de confinement et de sandbox OS.

## Qualification sandbox OS

Le backend worker n'annonce `OS_ENFORCED` que si la primitive actuelle peut réellement être exercée.

- Linux : `bubblewrap` + namespaces OS, racine hôte en lecture seule, capacités supprimées, limites `prlimit`, network namespace isolé pour `DENY` et sonde de capacité au runtime ;
- Windows : AppContainer avec ensemble de capabilities vide pour `DENY` ou seule capability `internetClient` pour `ALLOW`, validation `TokenIsAppContainer`, ACL temporaires sur les racines gérées par MINOS et Job Object pour mémoire/processus/CPU/kill-on-close ;
- absence de primitive qualifiée : backend process-only conservé pour le diagnostic, mais `ALLOW` et `DENY` sont rejetés avant toute exécution remote du provider.

La campagne #135 ajoute une preuve exact-head Linux/Windows qui interdit explicitement les skips et exécute également le chemin réel `ProcessIndexerExecutor → sandbox → provider → artefact`.

## SonarCloud

SonarCloud est une preuve complémentaire lorsqu'il est configuré par le dépôt/service et exécuté sur le candidat concerné. Il ne remplace ni Maven, ni les scopes JaCoCo ciblés, ni les gates fonctionnels. Aucune configuration Sonar ou secret ne doit être inventé dans une PR uniquement pour fabriquer un PASS.

## Exclusions et limites

Aucune classe critique explicitement ciblée n'est exclue du gate. Les classes d'assemblage, DTO simples, renderers et adapters non listés restent visibles dans le rapport agrégé mais ne portent pas nécessairement de seuil individuel.

La règle durable reste : **prouver les comportements critiques, échouer de façon fail-closed lorsque la preuve manque, puis relever progressivement les seuils quand les tests le justifient**.
