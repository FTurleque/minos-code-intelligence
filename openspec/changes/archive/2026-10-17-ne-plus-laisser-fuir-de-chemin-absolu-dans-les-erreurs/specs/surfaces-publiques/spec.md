## ADDED Requirements

### Requirement: Un chemin absolu masqué par une séquence de contrôle ou accolé à un mot est détecté
Avant d'analyser un message destiné à une sortie publique, le détecteur de message sensible SHALL en retirer les séquences d'échappement ANSI complètes et les caractères de contrôle. Il SHALL reconnaître la forme lecteur Windows (une lettre, `:`, puis `\` ou `/`) où qu'elle apparaisse, même accolée à un identifiant. Un message reconnu sensible SHALL être remplacé par le repli fourni par l'appelant.

#### Scenario: Chemin précédé d'une séquence ANSI
- **GIVEN** le message `\u001b[31m/home/u/x`
- **WHEN** `PublicErrorMessages.looksSensitive` l'analyse
- **THEN** il est classé sensible et `sanitize` rend le repli

#### Scenario: Lecteur Windows accolé à un mot
- **GIVEN** le message `cannot open abcC:\Users\u\token`
- **WHEN** le détecteur l'analyse
- **THEN** il est classé sensible

#### Scenario: Aucune séquence de contrôle dans un message publié
- **GIVEN** un message sans chemin qui contient une séquence ANSI de couleur
- **WHEN** `sanitize` le traite
- **THEN** le texte rendu ne contient ni caractère d'échappement ni reste de la séquence

### Requirement: Un chemin relatif n'est pas pris pour un chemin absolu
Le détecteur de message sensible SHALL NOT classer sensible un chemin relatif à plusieurs segments, ni un nom de domaine suivi d'un chemin, au seul motif qu'il contient des barres obliques. Un message d'erreur actionnable qui nomme un répertoire du projet par son chemin relatif SHALL rester publié tel quel.

#### Scenario: Chemin relatif d'un module
- **GIVEN** le message `module packages/app/src not found`
- **WHEN** `PublicErrorMessages.sanitize` le traite
- **THEN** le message est rendu inchangé

#### Scenario: Nom de domaine suivi d'un chemin
- **GIVEN** le message `request to example.com/status failed`
- **WHEN** le détecteur l'analyse
- **THEN** il n'est pas classé sensible
