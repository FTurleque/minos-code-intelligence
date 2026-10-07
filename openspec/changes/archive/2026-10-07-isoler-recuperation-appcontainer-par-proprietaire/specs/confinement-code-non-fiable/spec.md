# Spec Delta

## Purpose

Garantit que le confinement du code de provider non fiable n'a pas d'effet de bord sur un sandbox concurrent vivant et ne laisse aucun résidu non borné après un arrêt brutal du processus MINOS, sous Windows et sous Linux.

## ADDED Requirements

### Requirement: Un sandbox vivant n'est jamais altéré par un autre lanceur
Sous Windows, tant que le propriétaire d'un sandbox AppContainer est vivant, aucun autre lanceur MINOS, du même processus ou d'un autre, SHALL retirer les droits accordés à ce sandbox, supprimer son profil ou supprimer son journal de reprise.

#### Scenario: Un second lanceur démarre pendant qu'un provider tourne
- **WHEN** un sandbox A exécute un provider qui dort 20 secondes puis écrit un fichier dans son répertoire de travail, et qu'un second lanceur B démarre depuis le même `MINOS_HOME` et s'exécute jusqu'à sa fin
- **THEN** le journal de reprise de A existe toujours, les droits du profil de A sont toujours présents sur ses racines, et le fichier attendu est écrit par le provider A à la fin des 20 secondes

#### Scenario: Une commande de qualification s'exécute pendant une indexation
- **WHEN** une commande qui qualifie le sandbox (sonde de capacité) lance un lanceur pendant qu'un provider d'un autre processus MINOS est en cours
- **THEN** le provider en cours n'observe ni accès refusé ni perte de son profil du fait de cette sonde

### Requirement: Un sandbox dont le propriétaire est mort est récupéré
Sous Windows, un lanceur MINOS SHALL récupérer un sandbox laissé par un propriétaire dont la mort est prouvée : retirer les droits accordés à son profil, supprimer son profil AppContainer et son journal de reprise.

#### Scenario: Le lanceur propriétaire est tué brutalement
- **WHEN** le processus lanceur d'un sandbox A est terminé de force sans passer par sa sortie normale, puis qu'un lanceur B démarre depuis le même `MINOS_HOME`
- **THEN** B retire les droits du profil de A sur chacune de ses racines, supprime le profil de A et supprime son journal de reprise

#### Scenario: Une sortie normale ne laisse rien
- **WHEN** un lanceur termine normalement, avec succès ou en échec du provider
- **THEN** ses droits, son profil et son journal de reprise ont disparu

### Requirement: Une propriété non prouvable n'est jamais récupérée
Sous Windows, un lanceur MINOS SHALL NOT récupérer un sandbox dont la mort du propriétaire n'est pas prouvée positivement : preuve de propriété illisible ou partielle, journal sans marque de propriétaire, journal d'un format antérieur. Il le laisse en place et le signale par un avertissement.

#### Scenario: Journal sans marque de propriétaire
- **WHEN** le répertoire de reprise contient un journal sans marque de propriétaire et qu'un lanceur démarre
- **THEN** ce journal, son profil et ses droits sont intacts et un avertissement l'identifie comme non récupérable

#### Scenario: La preuve de propriété ne peut pas être lue
- **WHEN** la marque de propriétaire d'un journal existe mais ne peut pas être examinée (accès refusé, erreur d'entrée-sortie autre que « propriétaire vivant »)
- **THEN** le journal est laissé en place et signalé, jamais récupéré

### Requirement: La preuve de propriété ne dépend ni d'une horloge ni d'un identifiant réutilisable
Sous Windows, la décision de récupérer ou non un sandbox SHALL NOT dépendre de l'horloge murale ni d'un identifiant de processus susceptible d'être réutilisé : la mort du propriétaire est établie par une marque que le système d'exploitation libère à la mort du processus.

#### Scenario: Saut d'horloge
- **WHEN** l'horloge murale avance ou recule de façon importante pendant qu'un sandbox vivant s'exécute et qu'un autre lanceur démarre
- **THEN** le sandbox vivant n'est pas récupéré

#### Scenario: Identifiant de processus réutilisé
- **WHEN** l'identifiant de processus d'un propriétaire mort est attribué à un processus sans rapport
- **THEN** le sandbox du propriétaire mort est tout de même récupéré, sans que le processus sans rapport ne le protège

### Requirement: Un lanceur qui ne prouve pas sa propre propriété refuse de démarrer
Sous Windows, un lanceur MINOS SHALL NOT démarrer le code d'un provider tant qu'il n'a pas établi la marque de propriété de son propre sandbox. S'il ne le peut pas, il échoue avant toute création de profil, sans lancer de provider et sans mode dégradé.

#### Scenario: La marque de propriété ne peut pas être établie
- **WHEN** la création de la marque de propriété échoue (collision de nom, accès refusé, volume en lecture seule)
- **THEN** le lanceur se termine en échec avant toute exécution du provider et ne laisse ni profil ni droits

### Requirement: Un répertoire de reprise d'un format antérieur n'est pas balayé par le nouveau lanceur
Sous Windows, le nouveau lanceur SHALL écrire ses journaux dans un emplacement de reprise distinct de celui des lanceurs antérieurs et SHALL NOT récupérer les journaux de l'ancien emplacement, de sorte qu'aucun balayage ne s'exerce entre versions.

#### Scenario: Journal hérité d'un lanceur antérieur
- **WHEN** un journal du format antérieur existe dans l'ancien emplacement de reprise et qu'un nouveau lanceur démarre
- **THEN** ce journal reste en place, intact, et le nouveau lanceur ne l'examine pas pour récupération

#### Scenario: Le nouveau lanceur écrit dans son propre emplacement
- **WHEN** un nouveau lanceur démarre un sandbox
- **THEN** son journal de reprise est créé dans l'emplacement du nouveau format et jamais dans l'ancien

### Requirement: Les résidus de travail d'un run mort ont une durée de vie bornée
Lorsque MINOS crée un nouveau répertoire de travail éphémère de provider, il SHALL supprimer les répertoires de travail de runs précédents dont la dernière modification est antérieure à la durée de vie unique des résidus, quelle que soit la plateforme.

#### Scenario: Un répertoire orphelin ancien est supprimé
- **WHEN** `local-provider-workspaces/` contient le répertoire d'un run mort dont la dernière modification remonte à 48 heures, et qu'un autre run crée son répertoire de travail
- **THEN** le répertoire ancien et tout son contenu sont supprimés

#### Scenario: Les autres racines de résidus suivent la même borne
- **WHEN** le cache de dépôts distants contient un temporaire `.entry-*.tmp`, ou le cache d'artefacts distribués un temporaire `.accept-*`, d'un processus mort et plus ancien que la durée de vie, et qu'une nouvelle matérialisation ou acceptation démarre
- **THEN** ce temporaire est supprimé, et aucune entrée valide du cache n'est supprimée

### Requirement: Un résidu récent, protégé ou daté dans le futur n'est jamais supprimé
Le balayage des résidus SHALL conserver tout répertoire de travail plus récent que la durée de vie, le répertoire du run courant, et tout résidu dont la date est dans le futur ; la durée de vie SHALL excéder strictement la durée maximale d'un run ou d'un clonage qui possède un tel résidu.

#### Scenario: Un run récent est conservé
- **WHEN** `local-provider-workspaces/` contient le répertoire d'un run dont la dernière modification est récente et qu'un autre run crée son répertoire de travail
- **THEN** le répertoire récent est conservé intact

#### Scenario: Date dans le futur
- **WHEN** un résidu porte une date de dernière modification postérieure à l'instant courant (saut d'horloge)
- **THEN** il n'est pas supprimé

### Requirement: Le balayage des résidus ne sort jamais de sa racine et reste non fatal
Le balayage des résidus SHALL ne supprimer que des enfants directs de la racine qui lui est donnée, sans jamais suivre un lien symbolique ni une jonction, et SHALL NOT faire échouer l'opération qui le déclenche : un résidu qui ne peut pas être supprimé est signalé par un avertissement et laissé en place. Son coût par déclenchement est borné.

#### Scenario: Un lien symbolique ancien dans la racine
- **WHEN** la racine des répertoires de travail contient un lien symbolique ou une jonction ancien pointant hors de la racine
- **THEN** seul le lien est supprimé ; la cible n'est ni parcourue ni modifiée

#### Scenario: Un résidu ne peut pas être supprimé
- **WHEN** un résidu ancien refuse la suppression (droits, verrou de fichier)
- **THEN** le nouveau répertoire de travail est créé normalement, l'opération réussit et un avertissement signale le résidu sans chemin absolu

#### Scenario: Racine volumineuse
- **WHEN** la racine contient plus de résidus anciens que la borne de travail d'un déclenchement
- **THEN** seul un nombre borné de résidus est supprimé à ce déclenchement et les autres le seront aux suivants
