# audit-qualite-code Specification

## Purpose
Définit l'audit de qualité du code de MINOS à la demande : l'analyse statique du bytecode (SpotBugs) et la mesure de la capacité des tests à détecter une régression (tests de mutation PIT). Cet audit est borné, reproductible et sans effet sur le build habituel.

## Requirements

### Requirement: Le build habituel n'exécute ni SpotBugs ni PIT
`clean verify` et les workflows de PR SHALL NOT exécuter SpotBugs ni PIT. Les deux plugins SHALL n'être ajoutés au build que par les profils Maven `audit-spotbugs` et `audit-mutation`, qu'aucune condition d'activation ne déclenche.

#### Scenario: Aucun profil
- **WHEN** le POM effectif d'un module est calculé sans profil d'audit
- **THEN** ni `spotbugs-maven-plugin` ni `pitest-maven` n'est dans `build/plugins` (ils ne figurent que dans `pluginManagement`)

#### Scenario: Un seul profil
- **WHEN** le POM effectif est calculé avec `audit-spotbugs`, puis avec `audit-mutation`
- **THEN** seul le plugin du profil demandé est dans `build/plugins`

### Requirement: SpotBugs produit des rapports exploitables et un contrôle bloquant explicite
Avec `audit-spotbugs`, SpotBugs SHALL analyser le bytecode de production de chaque module Java du reactor et écrire un rapport XML, un rapport SARIF et un rapport HTML. Le contrôle `spotbugs:check` SHALL faire échouer le build lorsqu'il reste au moins une alerte de gravité égale ou supérieure à `failThreshold`, et SHALL NOT être lié à une phase du cycle de vie.

#### Scenario: Rapport
- **WHEN** `spotbugs:spotbugs` est lancé avec `-Paudit-spotbugs` sur un module
- **THEN** `target/spotbugsXml.xml`, `target/spotbugsSarif.json` et `target/reports/spotbugs.html` existent pour ce module (pour `minos-app`, dans le `target/` de la racine)

#### Scenario: Le contrôle échoue sur une alerte
- **WHEN** `spotbugs:check` est lancé sur un module qui contient une alerte de gravité `Medium` avec `failThreshold=Medium`
- **THEN** le build du module échoue et le message nomme le nombre d'alertes

#### Scenario: Le seuil se règle sans modifier de POM
- **WHEN** le même contrôle est relancé avec `-Dspotbugs.failThreshold=High` et qu'il ne reste aucune alerte `High`
- **THEN** le build réussit

### Requirement: Aucune alerte n'est masquée pour obtenir un résultat vert
Le filtre d'exclusion SpotBugs SHALL ne contenir que des exclusions ciblant une classe pour un motif, chacune justifiée par un commentaire daté. Le dépôt SHALL consigner les alertes découvertes au lieu de les exclure.

#### Scenario: Filtre initial
- **WHEN** l'intégration est livrée
- **THEN** `quality/spotbugs-exclude.xml` ne contient aucune règle `Match`

### Requirement: PIT ne mute qu'un périmètre déclaré et ajustable sans modifier de POM
Avec `audit-mutation`, PIT SHALL ne muter que les classes de `targetClasses` et SHALL n'exécuter que les tests de `targetTests`. Ces deux valeurs SHALL pouvoir être remplacées en ligne de commande. Le profil SHALL NOT lier PIT à une phase du cycle de vie.

#### Scenario: Périmètre initial
- **WHEN** PIT est lancé avec `-Paudit-mutation -pl minos-engine -am` sans autre option
- **THEN** il mute `HostedAuditChain`, `HostedAuthorizationService` et `HostedPermission` avec les tests de `com.minos.hosted.*`, et écrit `minos-engine/target/pit-reports/index.html` et `mutations.xml`

#### Scenario: Périmètre remplacé
- **WHEN** PIT est lancé avec `-DtargetClasses=<autre classe>`
- **THEN** seules les mutations de cette classe figurent dans `mutations.xml`

### Requirement: Une analyse PIT sans mutation couverte n'est pas un succès
Une exécution PIT SHALL NOT être tenue pour réussie sur la seule fin du processus. La vérification `audit-report-summary.py pit` SHALL échouer lorsque le rapport est absent, ne contient aucune mutation, ou ne contient aucune mutation couverte par un test.

#### Scenario: Rapport absent
- **WHEN** la vérification est lancée pour un module où PIT n'a pas tourné
- **THEN** elle échoue avec le code 1 et nomme le fichier manquant

#### Scenario: Analyse utile
- **WHEN** la vérification est lancée après l'exécution du périmètre initial
- **THEN** elle réussit et affiche le nombre de mutations par statut, le score de mutation et la force des tests

### Requirement: Les commandes documentées correspondent à la configuration
Chaque commande de `docs/developer/quality/code-audit.md` SHALL être exécutable telle quelle depuis la racine du dépôt, et la documentation SHALL distinguer celles qui n'ont pas été exécutées dans l'environnement de l'intégration.

#### Scenario: Commande non exécutée
- **WHEN** une commande de la documentation n'a pas été lancée (bash sous Linux ou macOS, workflow GitHub Actions)
- **THEN** la documentation l'indique explicitement dans ses limites

### Requirement: L'état de la mutation par module est écrit une seule fois
Le document de couverture de l'audit SHALL porter un tableau unique donnant, pour chaque module du reactor et pour le plugin IntelliJ, un statut de mutation (exécuté, partiel, non exécuté, bloqué), le système, l'exécution et la raison. Les autres sections SHALL y renvoyer et SHALL NOT redonner un décompte de modules mutés.

#### Scenario: Décompte unique
- **GIVEN** `docs/developer/quality/code-audit-couverture.md`
- **WHEN** on y cherche les décomptes de la forme « modules sur 14 » relatifs à PIT
- **THEN** il n'en existe qu'à un seul endroit, celui du tableau d'état

#### Scenario: Décompte contradictoire réintroduit
- **GIVEN** une copie du document où une section hors du tableau affirme un autre nombre de modules mutés
- **WHEN** on compte dans cette copie les lignes contenant « modules sur 14 » (par exemple `grep -c "modules sur 14"`)
- **THEN** le compte dépasse un et la copie n'est pas acceptée à la clôture

#### Scenario: Tous les modules nommés
- **GIVEN** la liste des modules du `pom.xml` racine et le plugin IntelliJ
- **WHEN** le tableau d'état est lu
- **THEN** chacun y figure avec un statut

### Requirement: Un module sans rapport complet n'est pas compté comme analysé
Un module dont le rapport PIT est absent, vide, ou porte des mutants non analysés (minion non démarré) SHALL être écrit « non exécuté » ou « partiel », jamais « exécuté ». Les mutants en délai dépassé ou en erreur SHALL être comptés à part des mutants tués. Un module muté sur un seul système SHALL porter ce système, et le code propre à l'autre SHALL être déclaré non analysé.

#### Scenario: Rapport manquant
- **GIVEN** des rapports PIT pour tous les modules sauf un qui a des classes compilées
- **WHEN** `python scripts/quality/audit-report-summary.py pit --all` est exécuté
- **THEN** la commande échoue en nommant le module sans rapport

#### Scenario: Erreurs d'exécution massives
- **GIVEN** un rapport où des mutants sont en `RUN_ERROR`
- **WHEN** la synthèse est produite
- **THEN** ces mutants sont comptés à part des mutants tués et le module est écrit « partiel » tant qu'ils ne sont pas rejoués

#### Scenario: Rejeu sous Linux d'un module à code Windows
- **GIVEN** `minos-runtime-local` muté sur un runner Linux
- **WHEN** son statut est écrit dans le tableau
- **THEN** il porte « Linux » et ses classes propres à Windows sont déclarées non analysées

### Requirement: Le nom d'une opération bornée dit son comportement au dépassement
Dans le code de production, un helper d'addition ou de multiplication bornée SHALL porter un nom qui dit s'il sature ou s'il lève une exception au dépassement. Les noms `safeAdd` et `safeMultiply` SHALL NOT être déclarés, et `checkedAdd` SHALL NOT désigner un helper qui ne lève pas. Un gate exécuté par le job des invariants SHALL échouer sur une telle déclaration en nommant le fichier et la ligne.

#### Scenario: Retour d'un nom ambigu
- **GIVEN** une copie temporaire du dépôt où un fichier de `src/main` déclare `private static long safeAdd(long left, long right)`
- **WHEN** `python scripts/quality/check-bounded-helpers.py --self-test` construit cette copie et y exécute le gate
- **THEN** le gate échoue en nommant le fichier et la ligne

#### Scenario: checkedAdd qui sature
- **GIVEN** une copie temporaire où une méthode `checkedAdd` rend `Long.MAX_VALUE` au dépassement sans lever d'exception
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le fichier et la ligne

#### Scenario: checkedAdd qui lève
- **GIVEN** une copie temporaire où une méthode `checkedAdd` lève une exception au dépassement
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il réussit

#### Scenario: Dépôt réel après renommage
- **GIVEN** le dépôt après le lot de renommage
- **WHEN** `python scripts/quality/check-bounded-helpers.py` est exécuté
- **THEN** il réussit et affiche le nombre de helpers contrôlés

### Requirement: Un cache LRU pondéré n'est écrit qu'une fois
Tout cache à ordre d'accès borné en poids du code de production SHALL utiliser le cache partagé. Un gate SHALL échouer sur une carte à ordre d'accès construite hors du cache partagé, sauf pour les fichiers d'une liste fermée dont la raison est écrite, et SHALL échouer sur une entrée de liste devenue sans objet. La migration SHALL laisser inchangés les noms et les valeurs des statistiques publiques de chaque cache.

#### Scenario: Cache réécrit à la main
- **GIVEN** une copie temporaire du dépôt où un fichier de `src/main` hors liste construit `new LinkedHashMap<>(16, 0.75f, true)`
- **WHEN** `python scripts/quality/check-bounded-helpers.py --self-test` y exécute le gate
- **THEN** le gate échoue en nommant le fichier et la ligne

#### Scenario: Exception déclarée
- **GIVEN** un fichier de la liste fermée, avec sa raison
- **WHEN** le gate est exécuté
- **THEN** ce fichier est accepté et la liste est affichée dans la sortie

#### Scenario: Entrée de liste périmée
- **GIVEN** une liste fermée qui nomme un fichier absent ou sans carte à ordre d'accès
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée périmée

#### Scenario: Statistiques inchangées après migration
- **GIVEN** les tests existants des caches migrés, sans modification de leurs assertions
- **WHEN** `./mvnw -B -ntp -pl minos-application,minos-storage,minos-bootstrap -am test` est exécuté
- **THEN** ils réussissent

### Requirement: Le cache partagé tient ses bornes et son poids
Le cache partagé SHALL ne jamais dépasser sa borne en entrées ni son plafond de poids, SHALL évincer d'abord les entrées les plus anciennement lues, SHALL retrancher le poids d'une entrée à chaque retrait, remplacement, invalidation ou éviction, et SHALL rester cohérent sous accès concurrent. Une entrée plus lourde que le plafond SHALL NOT être conservée ni provoquer d'éviction.

#### Scenario: Éviction par le poids
- **GIVEN** un cache dont le plafond de poids est atteint
- **WHEN** une entrée de plus est insérée et que les tests du cache partagé sont exécutés (`./mvnw -B -ntp -pl minos-engine -am test`)
- **THEN** les entrées les plus anciennement lues sont évincées et le poids total est inférieur ou égal au plafond

#### Scenario: Poids après retraits
- **GIVEN** un cache rempli puis vidé entrée par entrée, remplacements et invalidations par prédicat compris
- **WHEN** son poids total est lu
- **THEN** il vaut zéro

#### Scenario: Entrée plus lourde que le plafond
- **GIVEN** une entrée dont le poids dépasse le plafond du cache
- **WHEN** elle est insérée
- **THEN** elle n'est pas conservée, les entrées des autres clés restent et le poids total ne croît pas

#### Scenario: Accès concurrents
- **GIVEN** plusieurs fils synchronisés par une barrière qui lisent et insèrent en même temps
- **WHEN** ils ont terminé
- **THEN** le poids total égale la somme des poids des entrées présentes et aucune borne n'est dépassée

### Requirement: Le cache partagé refuse un poids ou une borne invalide
Le cache partagé SHALL refuser, par une exception d'argument et sans modifier son contenu, l'insertion d'une entrée de poids négatif. Il SHALL refuser à la construction une borne en entrées inférieure à un et un plafond de poids nul, négatif ou supérieur à la moitié de la plus grande valeur d'un entier long, afin que la somme des poids ne puisse pas déborder.

#### Scenario: Poids négatif
- **GIVEN** un cache contenant des entrées
- **WHEN** une entrée de poids négatif est insérée
- **THEN** une exception d'argument est levée, et le contenu et le poids total du cache sont inchangés

#### Scenario: Bornes invalides
- **GIVEN** une borne en entrées nulle, ou un plafond de poids nul, négatif ou supérieur à la moitié de `Long.MAX_VALUE`
- **WHEN** le cache est construit dans les tests de son module (`./mvnw -B -ntp -pl minos-engine -am test`)
- **THEN** la construction échoue par une exception d'argument

### Requirement: La langue des commentaires de chaque module est déclarée
Le guide de contribution SHALL énoncer la langue des commentaires et de la Javadoc du code de production. La langue de chaque module SHALL être déclarée dans un fichier unique lu par le gate de langue, avec par module un maximum de fichiers ayant un commentaire dans l'autre langue. Le gate SHALL échouer si un module n'est pas déclaré, si ce nombre dépasse son maximum ou s'il lui est inférieur.

#### Scenario: Module sans langue déclarée
- **GIVEN** une copie du dépôt où un module du reactor est absent de la déclaration
- **WHEN** `python scripts/quality/check-comment-language.py --root <copie>` est exécuté
- **THEN** le gate échoue avec un code non nul en nommant le module

#### Scenario: Nouveau fichier hors langue
- **GIVEN** une copie du dépôt où un fichier de `src/main` ajouté à un module ne porte que des commentaires dans la langue que ce module ne déclare pas
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le module, son décompte et son maximum

#### Scenario: Maximum devenu trop haut
- **GIVEN** une copie du dépôt où un fichier hors langue a été repris dans la langue déclarée sans abaisser le maximum de son module
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en donnant la valeur à inscrire

#### Scenario: Dépôt réel
- **GIVEN** le dépôt, où chaque module du reactor a une langue déclarée et un maximum égal à son décompte
- **WHEN** `python scripts/quality/check-comment-language.py` est exécuté, sous Windows comme sous Linux
- **THEN** il réussit et affiche la langue et le décompte de chaque module

### Requirement: Un fichier de production ne mêle pas deux langues hors d'une liste décroissante
Un fichier Java de production dont les commentaires mêlent français et anglais SHALL faire échouer le gate de langue, sauf s'il figure, par son nom de fichier, dans la liste des fichiers mixtes tolérés. Le gate SHALL échouer si une entrée ne désigne aucun fichier, en désigne plusieurs, ou désigne un fichier qui n'est plus mixte, de sorte que la liste ne puisse que diminuer. Les sources SHALL être lues en UTF-8.

#### Scenario: Nouveau fichier mixte
- **GIVEN** une copie du dépôt où un fichier de `src/main` hors liste contient un commentaire français et un commentaire anglais
- **WHEN** `python scripts/quality/check-comment-language.py --root <copie>` est exécuté
- **THEN** il échoue en nommant le fichier et une ligne de chaque langue

#### Scenario: Fichier listé devenu conforme
- **GIVEN** un fichier de la liste dont les commentaires sont désormais dans une seule langue
- **WHEN** le gate est exécuté
- **THEN** il échoue en demandant de retirer l'entrée

#### Scenario: Entrée morte
- **GIVEN** une liste qui nomme un fichier supprimé
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée

#### Scenario: Fichier listé déplacé
- **GIVEN** un fichier listé déplacé dans un autre package sans changer de nom
- **WHEN** le gate est exécuté
- **THEN** il réussit sans modification de la liste

#### Scenario: Lettre accentuée hors commentaire
- **GIVEN** un fichier dont les commentaires sont en anglais et dont un littéral de chaîne ou un `{@code …}` contient une lettre accentuée
- **WHEN** `python scripts/quality/check-comment-language.py --self-test` est exécuté
- **THEN** le cas est accepté : le fichier n'est pas tenu pour mixte

### Requirement: Le nombre de fichiers dont un commentaire cite un jalon ne peut que baisser
Le gate de langue SHALL compter, par module, les fichiers Java de production dont un commentaire cite un jalon (`M` suivi de chiffres en mot entier), et SHALL échouer si ce nombre dépasse le maximum déclaré pour le module ou s'il lui est inférieur. Un fichier dont les commentaires sont repris SHALL perdre ses mentions de jalon, et le maximum de son module SHALL baisser dans le même commit.

#### Scenario: Nouveau commentaire citant un jalon
- **GIVEN** une copie du dépôt où un commentaire d'un fichier de `src/main` sans jalon cite `M17`
- **WHEN** `python scripts/quality/check-comment-language.py --root <copie>` est exécuté
- **THEN** il échoue en nommant le module, son décompte et son maximum

#### Scenario: Fichier repris sans abaisser le maximum
- **GIVEN** une copie du dépôt où le seul commentaire à jalon d'un fichier est réécrit sans le jalon, le maximum de son module restant inchangé
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en donnant la valeur à inscrire

#### Scenario: Nom qui contient un chiffre sans être un jalon
- **GIVEN** un fichier dont les commentaires citent `arm64`, `System32` ou `qualifiedM24Providers`, sans autre jalon
- **WHEN** `python scripts/quality/check-comment-language.py --self-test` est exécuté
- **THEN** le cas est accepté : le fichier n'est pas compté

### Requirement: Les verrous npm livrés sont analysés par le gate de vulnérabilités
Le job de vulnérabilités des pull requests SHALL analyser chaque verrou npm livré avec le produit, quel que soit le nom du fichier. Une vulnérabilité connue dans un paquet de ces verrous SHALL faire échouer le job.

#### Scenario: Paquet vulnérable dans un verrou livré
- **GIVEN** une copie du dépôt où `scip-python-package-lock.json` référence une version d'un paquet touchée par un avis connu
- **WHEN** OSV-Scanner est exécuté avec les arguments du job de pull request
- **THEN** l'analyse échoue et nomme le paquet et l'avis

#### Scenario: Verrous sains
- **GIVEN** le dépôt réel
- **WHEN** OSV-Scanner est exécuté avec les arguments du job de pull request
- **THEN** les deux verrous figurent parmi les sources analysées et l'analyse réussit

### Requirement: Aucun verrou npm livré n'échappe au scan
Un gate statique SHALL échouer quand un fichier de verrou npm versionné sous un répertoire de ressources de production n'est pas passé au job de vulnérabilités.

#### Scenario: Troisième verrou non déclaré
- **GIVEN** une copie du dépôt où un nouveau `scip-autre-package-lock.json` est ajouté aux ressources sans modifier le workflow
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le fichier

#### Scenario: Verrous déclarés
- **GIVEN** le dépôt réel
- **WHEN** le gate est exécuté
- **THEN** il réussit

### Requirement: Le catalogue des outils embarqués a une veille planifiée à couverture déclarée
Un contrôle planifié SHALL interroger une base d'avis pour chaque composant du catalogue qui a un identifiant interrogeable et SHALL échouer quand un composant est touché ou hors support. Sa sortie SHALL lister les composants non couverts avec leur raison. Un échec réseau, un composant inconnu de la table ou une dérogation échue SHALL être un échec.

#### Scenario: Composant touché par un avis
- **GIVEN** des réponses simulées où un composant du catalogue est touché par un avis
- **WHEN** `python scripts/quality/check-embedded-tools-advisories.py --self-test` exécute ce cas
- **THEN** le contrôle échoue et nomme le composant et l'avis

#### Scenario: Composant absent de la table
- **GIVEN** un catalogue simulé contenant un composant que la table du script ne connaît pas
- **WHEN** le contrôle est exécuté
- **THEN** il échoue en nommant le composant

#### Scenario: Service d'avis injoignable
- **GIVEN** une interrogation qui échoue
- **WHEN** le contrôle est exécuté
- **THEN** il échoue avec un message distinct d'une alerte et ne rapporte pas « aucun avis »

#### Scenario: Composants non couverts déclarés
- **GIVEN** le catalogue réel et des réponses sans avis
- **WHEN** le contrôle est exécuté
- **THEN** il réussit et sa sortie énumère les composants non couverts avec leur raison
