# Rapport M0 — Glean C1 CLI

Date : 22 juillet 2026

Statut : **TERMINÉ — C1 qualifiée avec incompatibilité SCIP et coût opérationnel élevé**

## Objectif

Qualifier le chemin Glean le plus court sur un vrai `index.scip` sans modifier
les contrats `CodeKnowledgeStore`, `SymbolQueryService`, `find_symbol` ou
`find_usages` :

```text
index.scip
    -> indexeur SCIP intégré à Glean
    -> base Glean locale
    -> requêtes Angle via la CLI
```

Cette étape mesure C1. Elle ne décide pas encore de C2 Thrift ni de C3 sidecar.

## Sources officielles vérifiées

- construction : <https://glean.software/docs/building/> ;
- Docker : <https://glean.software/docs/docker/> ;
- CLI : <https://glean.software/docs/cli/> ;
- requêtes : <https://glean.software/docs/query/intro/> ;
- dépôt : <https://github.com/facebookincubator/Glean> ;
- paquet stable : <https://hackage.haskell.org/package/glean-0.2.0.1>.

Constats confirmés le 22 juillet 2026 :

- le build Glean reste testé uniquement sous Linux ;
- Ubuntu 24.04 est utilisé par la CI officielle ;
- GHC 9.2.8, 9.4.7 et 9.6.7 figurent dans sa matrice ;
- l'image Docker officielle est encore signalée comme non fonctionnelle ;
- la dernière version stable Hackage est `0.2.0.1` ;
- l'API de requêtes non-Haskell reste Thrift ;
- le paquet `0.2.0.1` contient un indexeur SCIP Haskell intégré ;
- `glean index scip` peut consommer un `index.scip` existant sans imposer le
  convertisseur Rust externe `scip-to-glean`.

Le convertisseur Rust reste une variante optionnelle à comparer uniquement si
l'indexeur intégré échoue ou si une différence mesurable le justifie.

## Environnement retenu

```text
Windows hôte     Windows 10
Distribution     Ubuntu 24.04.3 LTS
Virtualisation   WSL2
Architecture     x86_64
Glean            0.2.0.1
GHC              9.4.7, paquet Ubuntu et version testée par Glean
Cabal            3.8.1.0, paquet Ubuntu
Docker           non utilisé
```

Les dépendances Linux de compilation sont installées uniquement dans la
distribution Ubuntu WSL2. Le binaire Glean et les caches Cabal sont placés dans
le répertoire utilisateur Linux :

```text
~/.minos-m0/glean/
```

Le `PATH` Windows et le `PATH` utilisateur persistant ne sont pas modifiés.

## Protocole C1

Premier dataset :

```text
fixtures/java/java-simple/.minos-m0/scip-java/index.scip
```

Artefacts locaux attendus :

```text
.minos-m0/experiments/glean-c1/install.txt
.minos-m0/experiments/glean-c1/environment.txt
.minos-m0/experiments/glean-c1/java-simple/
```

Mesures minimales :

```text
install_complexity
install_time
install_disk_size
index_ingestion_time
database_disk_size
query_latency
startup_time
process_count
failure_recovery
```

Requêtes fonctionnelles minimales : symboles, définitions, références,
implémentations et au moins un prédicat dérivé SCIP.

## Règles de décision

- un échec d'installation ou d'ingestion est conservé comme résultat M0 ;
- aucune limitation Glean ne doit modifier la toolchain Java 24 de MINOS ;
- aucun type Glean, Angle ou Thrift ne doit fuiter dans `domain`, `store` ou
  `query` ;
- le backend mémoire E1 reste la baseline de contrôle ;
- aucune adoption de Glean n'est décidée avant les mesures.

## Résultats

### Dépendances système WSL2

Commande réellement exécutée via le runner avec
`-InstallSystemDependencies` :

```text
apt-get update
apt-get install -y <dépendances officielles Glean + ghc + cabal-install>
```

Résultat :

```text
exit                       0
durée murale               284 640,194 ms
nouveaux paquets           277
paquets mis à jour         20
archives annoncées         358 MB
espace additionnel annoncé 1 977 MB
```

La distribution WSL est la seule installation système modifiée. Aucun paquet
n'a été installé sous Windows et Docker n'a pas été utilisé.

### Premier build Cabal

Commande initiale :

```text
cabal install glean-0.2.0.1 --installdir=... --install-method=copy
```

Résultat réel :

```text
exit                 1
durée murale         5 866 170,752 ms
phase atteinte       compilation de glean:exe:glean réussie
échec final          copie de gen-bytecode-cpp depuis le store Cabal
binaire publié       non
environment.txt      non publié
```

Cabal a résolu et compilé le graphe Glean, y compris `folly-clib`, les
bibliothèques Glean, `glean-server` et `glean:exe:glean`. L'installation du
paquet complet a ensuite tenté de copier tous ses exécutables. Le chemin de
store attendu pour l'exécutable interne `gen-bytecode-cpp` n'existait plus :

```text
copyFile: .../bin/gen-bytecode-cpp: does not exist
```

Le journal est conservé localement dans :

```text
.minos-m0/experiments/glean-c1/install-attempt-1-failed.txt
```

Ce résultat ne valide pas l'installation Glean. La correction minimale du
runner cible uniquement l'exécutable requis par C1 :

```text
glean-0.2.0.1:exe:glean
```

Cette reprise doit réutiliser le store Cabal déjà compilé, puis vérifier
réellement `glean --version` et `glean index scip --help` avant toute ingestion.

### Deuxième tentative — cible distante trop qualifiée

La première correction a tenté de sélectionner directement le composant sur le
paquet Hackage distant :

```text
cabal install glean-0.2.0.1:exe:glean
```

Résultat réel :

```text
exit                 1
phase atteinte       mise à jour Hackage réussie
échec final          curl (3), URL/port invalide
binaire publié       non
environment.txt      non publié
```

Cabal 3.8.1 a interprété la chaîne complète comme une cible distante à
télécharger. Les variantes `glean:exe:glean` et `exe:glean` ont été vérifiées
en `--dry-run` : la première est encore traitée comme un téléchargement et la
seconde comme le paquet distant inexistant `exe`.

La documentation Cabal 3.8.1 distingue les deux opérations : un paquet Hackage
peut être installé comme cible distante, tandis que la sélection d'un composant
avec `exe:<nom>` est documentée pour un projet local. Le runner applique donc
le chemin reproductible suivant :

```text
cabal get glean-0.2.0.1
cd glean-0.2.0.1
cabal install exe:glean
```

La source exacte reste sous `~/.minos-m0/glean/source/`, le store Cabal existant
est réutilisé et seul le binaire requis par C1 doit être publié.

### Troisième tentative — défaut de copie Cabal confirmé

La source `glean-0.2.0.1` a été récupérée et le composant local a été résolu :

```text
cabal get glean-0.2.0.1              succès
cabal install exe:glean              dépendances « Up to date »
```

L'installation a néanmoins tenté de copier d'autres exécutables du paquet,
dont `disassemble` et `gen-bytecode-cpp`, puis a reproduit l'absence du binaire
interne `gen-bytecode-cpp` dans le store. L'échec ne concerne toujours pas la
compilation de `glean`.

Le store Cabal contient exactement un binaire Glean correspondant à la version
et aux flags retenus :

```text
taille                    111 394 488 octets
glean --version           exit 0
glean index scip --help   exit 0
```

Le runner évite désormais l'étape défectueuse `cabal install` : il exécute
`cabal build exe:glean`, résout le binaire via `cabal list-bin` ou, pour le
store déjà construit, via son identifiant exact, puis le copie sous
`glean.partial`. Le binaire partiel est vérifié avant le renommage atomique en
`glean`.

### Installation locale obtenue

La publication directe depuis l'unique binaire du store a réussi :

```text
exit                         0
durée de reprise             3 698 ms
binaire                      111 394 488 octets
répertoire versionné         178 554 512 octets
store Cabal                  4 516 378 499 octets
glean --version              exit 0, sortie « glean »
glean index scip --help      exit 0
```

La commande `--version` de ce build affiche seulement le nom de l'exécutable ;
la version `0.2.0.1` est garantie par la source Hackage, le plan Cabal et le
chemin versionné publiés dans `environment.txt`.

### Première ingestion — incompatibilité des plages SCIP typées

Commande réelle sur l'index original de `java-simple` :

```text
glean --db-root <db> --schema dir:<schemas> \
  index --db java-simple/<instance> scip \
  --input <index.scip> <project-root>
```

Résultat :

```text
exit                    1
durée                   1 119 ms
schéma chargé           1 219 prédicats
base partielle          713 380 octets
erreur                  decodeScipRange: got Nothing
```

Le décodeur Haskell `Data.SCIP.Angle` de Glean 0.2.0.1 lit uniquement le champ
historique `Occurrence.range`. Les bindings Protobuf embarqués ne déclarent pas
`single_line_range`, `multi_line_range` ni leurs équivalents pour la plage
englobante. Les occurrences produites par `scip-java 0.13.1` utilisent ces
nouveaux champs ; l'ancien champ est donc vide après désérialisation et le
décodeur interrompt l'ingestion.

Le convertisseur Rust officiel courant `scip-to-glean 0.1.0` ne constitue pas
un repli : son `scip.proto` et sa fonction `decode_scip_range(&[i32])` utilisent
également le champ historique. Rust n'est pas installé dans l'environnement de
référence et ne sera pas ajouté pour reproduire la même incompatibilité.

### Repli expérimental retenu

Une seule reprise de compatibilité est autorisée pour mesurer le reste de C1 :

1. lire l'index original avec les bindings Java SCIP 0.9.0 déjà utilisés par
   MINOS ;
2. recopier chaque plage typée dans le champ historique à trois ou quatre
   entiers ;
3. conserver les plages englobantes selon la même règle ;
4. écrire un nouvel artefact sans modifier l'`index.scip` source ;
5. compter toutes les conversions et retenter l'ingestion Glean.

Cette copie est un adaptateur de compatibilité fournisseur, pas une
normalisation métier MINOS. Son coût doit compter contre l'opérabilité de
Glean dans la décision M0.

### Export de compatibilité réellement exécuté

Le test ciblé du convertisseur a réussi sur Java 24 :

```text
Tests run                    1
Failures / Errors / Skipped 0 / 0 / 0
BUILD SUCCESS
```

L'export de la fixture a ensuite produit :

```text
documents                            6
occurrences                        128
typedRangesConverted               128
typedEnclosingRangesConverted       27
legacyRangesRetained                 0
missingRanges                        0
index source                    13 196 octets
copie historique                12 730 octets
```

L'index original est conservé byte pour byte. La copie est un artefact local
ignoré sous :

```text
.minos-m0/experiments/glean-c1/java-simple/index-legacy-ranges.scip
```

### Ingestion réussie de la copie compatible

La même commande que l'essai natif, appliquée à la copie historique, réussit :

```text
exit                              0
durée ingestion               2 095 ms
base Glean                  436 312 octets
schéma                        1 219 prédicats
SymbolDisplayName                32 faits
DefinitionLocation               32 faits
ReferenceLocation                96 faits
IsImplemented                     2 faits
EnclosedSymbol                    0 fait
```

Une reconstruction indépendante dans une nouvelle racine de base a également
réussi :

```text
durée murale                  2,01 s
mémoire RSS maximale        204 340 KiB
base reconstruite           436 333 octets
comptes de faits             identiques
```

L'échec initial n'a donc pas contaminé la reconstruction. La récupération
consiste à conserver la base partielle comme diagnostic, créer une nouvelle
racine et relancer après correction de l'entrée.

## Comparaison avec `expected.json`

Les requêtes Angle ont exporté les faits `scip.Symbol`,
`scip.SymbolDisplayName`, `scip.Definition`, `scip.Reference` et
`scip.IsImplemented`.

| Mesure de vérité terrain | Résultat Glean |
|---|---:|
| Symboles obligatoires présents | 13 / 13 |
| Kinds obligatoires exacts | 9 / 13 |
| Cibles d'usage présentes | 5 / 5 |
| Relations `IMPLEMENTS` attendues | 1 / 1 |
| Relations `CALLS` explicites | 0 / 3 |
| Clés de symboles dupliquées | 0 |
| Définitions dupliquées | 0 |
| Localisations de référence dupliquées | 0 |

Les quatre kinds inexacts sont explicables et reproductibles : le record
`User` et l'interface `UserRepository` sont exposés comme `CLASS`, tandis que
les deux constructeurs obligatoires sont exposés comme `METHOD`. Les cinq
méthodes et les quatre classes restantes ont le kind attendu.

Les cinq cibles d'usage attendues possèdent respectivement 5, 4, 1, 3 et 2
références. Les 96 références visent 43 symboles distincts. Le schéma produit
ne fournit pas de statut « non résolu » comparable à celui du domaine MINOS :
la liste vide `expectedUnresolved` ne permet donc pas de conclure à un taux de
non-résolution Glean nul.

Les deux faits `IsImplemented` sont :

- `InMemoryUserRepository` implémente `UserRepository` ;
- `InMemoryUserRepository.findById` implémente
  `UserRepository.findById`.

Le second est une information fournisseur supplémentaire correcte. En
revanche, les trois appels de la fixture ne sont que des références à leur
cible. `EnclosedSymbol` reste vide : C1 ne produit aucune relation `CALLS` ni
attribution fiable de ces références au symbole appelant.

## Coûts et latences

### Installation

```text
dépendances APT annoncées       277 nouveaux paquets, 20 mises à jour
archives APT                    358 MB
espace APT annoncé              1 977 MB
durée dépendances               284,64 s
premier build Cabal             5 866,17 s, publication finale en échec
binaire Glean                   111 394 488 octets
répertoire versionné            178 554 512 octets
store Cabal                     4 516 378 499 octets
```

### Requêtes CLI

Vingt processus neufs ont été lancés pour chacun des deux prédicats. Les
latences `elapsed` sont fournies par Glean ; la durée murale inclut le lancement
WSL, l'ouverture de la base et la fin du processus.

| Requête | Résultats | `elapsed` p50 / p95 | exécution p50 / p95 | processus p50 / p95 |
|---|---:|---:|---:|---:|
| `SymbolDisplayName` | 32 | 62,409 / 69,364 ms | 0,125 / 0,276 ms | 740 / 807 ms |
| `Reference` | 96 | 60,284 / 72,890 ms | 0,139 / 0,227 ms | 711 / 802 ms |

Une requête instrumentée avec `/usr/bin/time -v` a mesuré 155 376 KiB de RSS
maximale et 0,94 s de durée murale. L'observation de processus a montré un seul
processus `glean`, avec cinq threads, pour une invocation CLI. C1 ne maintient
pas de sidecar en mémoire ; il n'existe donc pas de mesure d'idle RSS.

Les temps internes d'exécution sont sous les objectifs MINOS, mais ce ne sont
pas des mesures du service `find_symbol` / `find_usages` complet. En mode CLI,
le coût dominant est le démarrage et l'ouverture de base, deux ordres de
grandeur au-dessus de la baseline mémoire sur cette petite fixture.

## Verdict C1

```text
Capacité technique SCIP -> Glean       OUI, avec conversion de compatibilité
Consommation directe index scip-java   NON
Valeur fonctionnelle supplémentaire    implémentations et requêtes Angle
Parité MINOS obligatoire               NON démontrée
Backend par défaut Windows             NON
Backend avancé optionnel               À conserver comme possibilité future
Poursuite immédiate C2 / C3             NON justifiée
```

Glean 0.2.0.1 fonctionne réellement sous WSL2 sur `java-simple`, mais pas sur
l'index moderne émis par `scip-java 0.13.1` sans copie de compatibilité. Il
n'améliore ni la vérité terrain des symboles, ni les appels, ni la
représentation des non-résolutions sur ce cas. Son installation, son store
Cabal, sa dépendance Linux et son démarrage CLI ne sont pas compensés par une
capacité décisive pour le MVP.

La décision M0 est donc de conserver la frontière `CodeKnowledgeStore`, de
poursuivre un backend MINOS léger comme chemin par défaut et de différer C2
Thrift / C3 sidecar jusqu'à l'apparition d'un cas d'usage de graphe ou de
volume que la baseline légère ne satisfait pas. Cette conclusion est comparée
formellement dans `COMPARATIF_BACKENDS.md` et consolide ADR-0003.
