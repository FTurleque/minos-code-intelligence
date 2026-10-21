# persistance-snapshots Specification

## Purpose
Garantit qu'un snapshot de connaissance, une empreinte de projet et le schéma PostgreSQL restent dans un état
valide après un arrêt brutal ou une migration : le snapshot actif est toujours l'ancien ou le nouveau, entier ;
un pointeur actif illisible fait échouer l'ouverture de façon explicite au lieu de basculer en silence ; une
migration qui échoue n'est jamais appliquée à moitié ; les fichiers temporaires laissés par un arrêt sont
récupérés sans toucher une écriture en cours. Elle borne aussi la lecture d'un snapshot persisté : le cache des vues
de requête pèse chaque vue sans sous-évaluer le tas qu'elle retient, y admet un snapshot de la taille de ce dépôt, ne
publie ni ne met en cache un snapshot altéré, et le décodage partage une instance par chaîne distincte.

## Requirements

### Requirement: Un arrêt brutal pendant une publication laisse le snapshot actif précédent intact
Si le processus qui publie un snapshot local s'arrête brutalement entre l'écriture du fichier temporaire et son renommage, la réouverture du magasin SHALL exposer le snapshot actif précédent, intact et vérifiable, et SHALL NOT exposer le snapshot partiellement publié.

#### Scenario: Processus tué après l'écriture temporaire
- **WHEN** un processus publie un snapshot, s'arrête après l'écriture du fichier temporaire, et est tué
- **THEN** un nouveau processus qui ouvre le magasin lit le snapshot actif précédent, dont la somme de contrôle est valide

#### Scenario: Processus tué après le renommage du snapshot mais avant celui du pointeur
- **WHEN** le processus est tué après la publication du fichier de snapshot et avant la mise à jour du pointeur actif
- **THEN** la réouverture expose toujours le snapshot actif précédent

### Requirement: Un pointeur actif illisible fait échouer l'ouverture de façon explicite
Un fichier de pointeur actif vide, tronqué ou de version inconnue SHALL faire échouer l'ouverture avec un message qui nomme le fichier, et SHALL NOT basculer silencieusement vers un autre snapshot.

#### Scenario: Pointeur vide
- **WHEN** `active.pointer` existe et est vide
- **THEN** l'ouverture échoue avec un message qui nomme le pointeur

#### Scenario: Pointeur tronqué
- **WHEN** `active.pointer` est coupé au milieu de son contenu
- **THEN** l'ouverture échoue fermée, sans retour à un snapshot plus ancien

### Requirement: Une base PostgreSQL peuplée migre jusqu'à la version courante sans perte
L'ouverture du stockage PostgreSQL sur une base peuplée à une version de schéma antérieure SHALL la migrer jusqu'à la version courante en une transaction, et les données existantes SHALL rester lisibles par l'API du magasin.

#### Scenario: Base v1 peuplée
- **WHEN** le stockage s'ouvre sur une base au schéma v1 contenant un projet et un snapshot
- **THEN** `schema_version` vaut la version courante et le projet et son snapshot se relisent à l'identique

#### Scenario: Échec pendant la migration
- **WHEN** une étape de migration échoue
- **THEN** la transaction est annulée, `schema_version` est inchangé et l'ouverture échoue avec un message qui nomme l'étape

### Requirement: Les pointeurs temporaires orphelins sont récupérés sans toucher une écriture en cours
Si la récupération est décidée, un fichier `.active-*.tmp` plus ancien que la borne de rétention des résidus SHALL être supprimé sous le verrou de mutation du projet, et un fichier plus récent ou en cours d'écriture SHALL NOT l'être.

#### Scenario: Orphelin ancien
- **WHEN** la rétention s'exécute et qu'un `.active-*.tmp` a plus de 24 heures
- **THEN** il est supprimé et la suppression est comptée dans le résultat de la rétention

#### Scenario: Écriture en cours
- **WHEN** un `.active-*.tmp` a moins de 24 heures
- **THEN** il est conservé

### Requirement: La validation réseau des connexions du pool PostgreSQL est bornée
Le pool SHALL NOT valider une connexion par un aller-retour réseau à sa restitution. À l'emprunt, il SHALL ne la valider que si elle est restée au repos au-delà du seuil d'inactivité ; une connexion invalide SHALL être fermée et remplacée.

#### Scenario: Emprunt puis restitution immédiats
- **GIVEN** un pool dont la connexion compte ses appels de validation
- **WHEN** une connexion est empruntée, utilisée et restituée deux fois de suite sans attente
- **THEN** le compteur de validations réseau vaut 0

#### Scenario: Emprunt après inactivité
- **GIVEN** une connexion au repos depuis plus longtemps que le seuil, selon l'horloge injectée
- **WHEN** elle est empruntée
- **THEN** elle est validée une fois avant d'être remise à l'appelant

#### Scenario: Connexion invalide après inactivité
- **GIVEN** une connexion au repos au-delà du seuil dont la validation échoue
- **WHEN** elle est empruntée
- **THEN** elle est fermée et l'appelant reçoit une connexion neuve

### Requirement: Une connexion rendue dans un état incertain n'est pas réutilisée
Une connexion restituée fermée, hors de son mode d'auto-commit attendu ou après une exception de l'appelant SHALL être fermée et retirée du pool. Une rupture de connexion constatée à la première instruction d'un appel SHALL produire le comportement défini par la décision du design, sans jamais rejouer une écriture.

#### Scenario: Restitution après une exception
- **GIVEN** un appel qui se termine par une exception SQL
- **WHEN** sa connexion est restituée
- **THEN** elle est fermée et l'emprunt suivant obtient une autre connexion

#### Scenario: Transaction laissée ouverte
- **GIVEN** une connexion restituée avec l'auto-commit désactivé
- **WHEN** le pool la reçoit
- **THEN** il la ferme et ne la remet pas au repos

#### Scenario: Connexion rompue sous le seuil
- **GIVEN** une connexion au repos depuis moins que le seuil, rompue côté serveur
- **WHEN** la première instruction d'un appel en lecture échoue par une erreur de connexion
- **THEN** la connexion est écartée et l'appel suit la décision retenue (nouvel essai unique ou erreur), avec une erreur publique sans URL ni chemin

#### Scenario: Aucune écriture rejouée
- **GIVEN** un appel qui a déjà exécuté une écriture puis subit une erreur de connexion
- **WHEN** l'erreur survient
- **THEN** l'appel échoue et aucune instruction n'est rejouée

### Requirement: Une recherche de symbole par identifiant utilise l'index de la vue
Un service de requête qui cherche un symbole par son identifiant SHALL utiliser l'index de la vue de requête active et SHALL NOT parcourir la liste des symboles du snapshot. Un identifiant inconnu SHALL produire la même réponse qu'auparavant.

#### Scenario: Rapport runtime d'un symbole
- **GIVEN** un store dont le chargement du snapshot brut échoue et dont la vue de requête est disponible
- **WHEN** le rapport runtime d'un symbole existant est demandé
- **THEN** le rapport est rendu

#### Scenario: Identifiant inconnu
- **GIVEN** une vue de requête active
- **WHEN** le rapport runtime d'un identifiant absent est demandé
- **THEN** la réponse est celle d'avant ce changement

### Requirement: Le poids d'une vue en cache compte ses index dérivés
Le poids d'une vue de requête et celui d'une entrée du cache de graphe de programme SHALL inclure les index dérivés qu'elles retiennent. Le poids estimé SHALL rester supérieur ou égal au tas retenu une fois ces index construits.

#### Scenario: Vue avec index d'arêtes construit
- **GIVEN** une vue de requête dont l'index des arêtes entrantes a été construit
- **WHEN** le test de poids compare l'estimation au tas retenu
- **THEN** l'estimation est supérieure ou égale à la mesure

### Requirement: Un snapshot de la taille de ce dépôt est admis au cache de vues
Les deux stores de snapshots SHALL estimer le poids d'une vue de requête d'après une amplification mesurée de la taille persistée. Un snapshot au format courant dont la vue tient sous le plafond du cache SHALL être mis en cache : deux lectures consécutives de la vue active SHALL ne provoquer qu'un seul chargement complet.

#### Scenario: Snapshot de plus de 64 Mio persistés
- **GIVEN** un snapshot actif au format courant de plus de 64 Mio persistés, dont la vue pèse moins que le plafond du cache
- **WHEN** la vue active est lue deux fois de suite
- **THEN** le compteur de chargements complets vaut 1

#### Scenario: Vue plus lourde que le plafond
- **GIVEN** un snapshot dont la vue estimée dépasse le plafond du cache
- **WHEN** la vue active est lue deux fois de suite
- **THEN** elle n'est pas mise en cache et le compteur de chargements complets vaut 2

### Requirement: Le poids estimé d'une vue ne sous-évalue pas le tas qu'elle retient
Pour chaque format de snapshot lisible, le poids estimé d'une vue SHALL être supérieur ou égal au tas qu'elle retient, mesuré par un test. Un format dont l'amplification n'a pas été mesurée SHALL conserver l'amplification prudente d'origine.

#### Scenario: Estimation comparée au tas retenu
- **GIVEN** une vue construite depuis un snapshot réel au format courant
- **WHEN** le test de poids mesure le tas retenu et le compare à l'estimation
- **THEN** l'estimation est supérieure ou égale à la mesure

#### Scenario: Format non mesuré
- **GIVEN** un snapshot d'un format antérieur sans mesure d'amplification
- **WHEN** son poids est estimé
- **THEN** l'estimation utilise l'amplification prudente d'origine

### Requirement: Un snapshot altéré n'est ni publié ni mis en cache
Quel que soit le nombre de parcours du fichier, une vue SHALL NOT être rendue à l'appelant, mise en cache ni comptée comme chargée avant que l'empreinte du contenu lu ait été comparée à l'empreinte attendue. Un contenu altéré, tronqué ou prolongé SHALL produire l'erreur d'intégrité.

#### Scenario: Contenu altéré
- **GIVEN** un snapshot actif dont un octet a été modifié après publication
- **WHEN** la vue active est lue
- **THEN** la lecture échoue avec l'erreur d'intégrité, le cache est vide et le compteur de chargements complets n'a pas augmenté

#### Scenario: Contenu tronqué
- **GIVEN** un snapshot actif tronqué
- **WHEN** la vue active est lue
- **THEN** la lecture échoue avec l'erreur d'intégrité et non avec une erreur de décodage

#### Scenario: Seconde lecture après un échec
- **GIVEN** une lecture qui vient d'échouer sur un contenu altéré
- **WHEN** la vue active est lue de nouveau
- **THEN** elle échoue de la même façon

### Requirement: Un snapshot décodé partage une instance par chaîne distincte
Le décodage d'un snapshot SHALL rendre une seule instance par chaîne distincte lue dans une même passe, pour chaque format lisible. Le modèle décodé SHALL rester égal au modèle écrit. La table de partage SHALL NOT survivre à la passe de décodage.

#### Scenario: Chaînes répétées sur plusieurs entités
- **GIVEN** un snapshot écrit dont deux symboles, une occurrence et une relation portent le même identifiant de projet
- **WHEN** il est décodé au format courant puis au format précédent
- **THEN** ces champs désignent la même instance et le modèle décodé est égal au modèle écrit

#### Scenario: Deux décodages successifs
- **GIVEN** un même fichier décodé deux fois
- **WHEN** les deux modèles sont comparés
- **THEN** ils sont égaux et ne partagent pas leurs instances de chaînes

### Requirement: Une seconde publication identique ne change pas l'état persisté
Publier deux fois le même snapshot, ou la même empreinte, sous le même identifiant SHALL laisser le même nombre de lignes persistées et le même contenu rechargé qu'après la première publication. Le test d'idempotence de chaque store PostgreSQL SHALL relire l'état après le second appel et SHALL échouer si une ligne est dupliquée ou si le contenu rechargé diffère.

#### Scenario: Snapshot publié deux fois
- **GIVEN** un snapshot publié une fois dans le store PostgreSQL
- **WHEN** il est publié une seconde fois avec le même contenu
- **THEN** le nombre de lignes du projet est inchangé et le snapshot rechargé est égal au snapshot publié

#### Scenario: Empreinte publiée deux fois
- **GIVEN** une empreinte de projet publiée une fois
- **WHEN** elle est publiée une seconde fois
- **THEN** une seule empreinte active est relue et elle est égale à l'empreinte publiée

#### Scenario: Duplication détectée
- **GIVEN** une publication modifiée localement pour insérer sans contrôle d'existence
- **WHEN** `./mvnw -B -ntp -pl minos-storage-postgresql -am verify` est exécuté avec PostgreSQL disponible
- **THEN** le test d'idempotence échoue

### Requirement: Une migration rejouée laisse le schéma à la version courante
Exécuter deux fois la migration du schéma PostgreSQL SHALL laisser dans `schema_version` chaque version de 1 à la version courante exactement une fois. Une base dont la version est supérieure à la version courante SHALL être refusée sans modification.

#### Scenario: Migration rejouée
- **GIVEN** une base migrée à la version courante
- **WHEN** la migration est exécutée de nouveau
- **THEN** `schema_version` contient les mêmes versions, une fois chacune

#### Scenario: Schéma plus récent que le runtime
- **GIVEN** une base dont `schema_version` porte une version supérieure à la version courante
- **WHEN** la migration est exécutée
- **THEN** elle échoue en nommant la version trouvée et `schema_version` est inchangée

### Requirement: L'attente d'un verrou de mutation est prouvée par un signal observé
Un écrivain concurrent d'un même projet SHALL attendre la libération du verrou de mutation ou du bail du projet. Le test qui le prouve SHALL observer l'entrée du concurrent dans l'attente (verrou PostgreSQL non accordé dans `pg_locks`, file d'attente d'un verrou de la JVM, ou signal positif borné pour un verrou de fichier) avant d'affirmer l'absence d'effet, et SHALL NOT s'appuyer sur un délai fixe.

#### Scenario: Concurrent en attente d'un verrou consultatif
- **GIVEN** un détenteur du verrou de mutation d'un projet et un concurrent dont le verrou figure comme non accordé dans `pg_locks`
- **WHEN** l'état du projet est relu avant la libération
- **THEN** il est inchangé, puis le concurrent aboutit après la libération

#### Scenario: Verrou retiré
- **GIVEN** une copie locale où l'acquisition du verrou est neutralisée
- **WHEN** le test de verrou correspondant est exécuté
- **THEN** il échoue

#### Scenario: Signal d'attente jamais observé
- **GIVEN** un concurrent qui n'entre pas en attente avant l'échéance du test
- **WHEN** l'échéance est atteinte
- **THEN** le test échoue avec un message et ne passe pas par défaut

### Requirement: Une panne du stockage de l'état d'index a un type unique
Toute implémentation de production du port d'état d'index SHALL signaler une panne de stockage (E/S, SQL, contenu illisible, bail non obtenu ou non rendu) par le type d'échec unique déclaré par le port, cause conservée. Elle SHALL NOT la signaler par `UncheckedIOException` ni par `IllegalStateException`, réservée aux violations d'invariant.

#### Scenario: Fichier d'état illisible
- **GIVEN** un store fichier dont le fichier d'état d'un projet est corrompu
- **WHEN** l'état du projet est lu
- **THEN** l'échec est du type du port et sa cause est l'erreur d'origine

#### Scenario: Erreur SQL
- **GIVEN** un store PostgreSQL dont la connexion échoue
- **WHEN** un run est enregistré
- **THEN** l'échec est du type du port, pour le store simple comme pour le store à mutation de projet

#### Scenario: Violation d'invariant
- **GIVEN** un bail de cycle de vie acquis par un thread
- **WHEN** un autre thread le libère
- **THEN** l'échec est une `IllegalStateException` et non le type du port

#### Scenario: Interruption pendant une écriture
- **GIVEN** une écriture d'état interrompue par l'interruption du thread
- **WHEN** l'exécuteur de run traite l'échec
- **THEN** le run est marqué interrompu et proposé à la reprise, comme avant

### Requirement: Un défaut de programmation n'est pas requalifié en panne d'E/S
Les appelants du port d'état d'index SHALL n'attraper que le type d'échec du port. Une exception d'un autre type levée par un store SHALL remonter telle quelle et SHALL NOT être convertie en erreur de lecture ou d'écriture de l'état d'index.

#### Scenario: Store qui lève NullPointerException
- **GIVEN** un store dont la lecture de l'état du projet lève `NullPointerException`
- **WHEN** la réconciliation de l'état du projet est exécutée
- **THEN** l'exception remonte et aucune erreur « failed to read project index state » n'est produite

#### Scenario: Panne de stockage
- **GIVEN** un store dont la lecture lève le type d'échec du port
- **WHEN** la réconciliation est exécutée
- **THEN** elle échoue avec l'erreur d'E/S existante, qui nomme le projet

### Requirement: Les messages du port d'état d'index ne contiennent aucun chemin absolu
Le message d'un échec levé par une implémentation du port SHALL NOT contenir le chemin absolu d'un fichier d'état ni la racine de stockage. Il SHALL nommer la nature de l'état et son identifiant quand il est connu ; la cause technique SHALL rester chaînée.

#### Scenario: Lecture d'un fichier d'état en échec
- **GIVEN** un store fichier placé sous une racine temporaire, dont un fichier d'état est illisible
- **WHEN** l'état est lu et l'échec capturé
- **THEN** le message ne contient pas le chemin de la racine temporaire

#### Scenario: Propriété manquante
- **GIVEN** un fichier d'état auquel il manque une propriété obligatoire
- **WHEN** il est lu
- **THEN** le message nomme la propriété et ne contient aucun chemin absolu
