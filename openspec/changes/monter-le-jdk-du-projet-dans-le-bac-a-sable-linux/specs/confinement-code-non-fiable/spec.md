## ADDED Requirements

### Requirement: Le bac à sable Linux monte en lecture seule le JDK nommé par JAVA_HOME
Sous Linux, quand l'environnement du provider nomme par `JAVA_HOME` ou `JDK_HOME` un répertoire existant, le bac à sable SHALL monter en lecture seule la racine réelle de ce répertoire, visible au chemin réel et au chemin nommé. Il MUST NOT monter son répertoire parent ni le monter en écriture.

#### Scenario: JDK autonome hors de /usr
- **GIVEN** sous Linux, un JDK autonome dans un répertoire temporaire et `JAVA_HOME` qui le nomme
- **WHEN** `$JAVA_HOME/bin/java -version` est exécuté dans le bac à sable avec un egress `DENY`
- **THEN** la commande réussit

#### Scenario: Parent non monté
- **GIVEN** sous Linux, `JAVA_HOME` qui nomme `<tmp>/profil/jdk` et un fichier `<tmp>/profil/secret.txt`
- **WHEN** le plan du bac à sable est construit
- **THEN** il monte `<tmp>/profil/jdk` en `--ro-bind` et ne monte ni `<tmp>/profil` ni `secret.txt`

### Requirement: Les liens du JDK ne montent qu'une cible exacte de la configuration Java de /etc
Sous Linux, pour un lien du JDK nommé dont la cible sort de ce qui est monté, le bac à sable SHALL monter en lecture seule la cible exacte si elle est sous `/etc/java`, `/etc/java-…`, `/etc/javaN…`, `/etc/ssl/certs`, `/etc/pki/java` ou `/etc/pki/ca-trust/extracted`. Il MUST NOT monter `/etc`, le répertoire de la cible ni une cible hors de cette liste.

#### Scenario: Disposition Debian
- **GIVEN** sous Linux, `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` installé par paquet
- **WHEN** le plan du bac à sable est construit
- **THEN** il monte `/etc/java-21-openjdk/security/java.security` et ne monte ni `/etc` ni `/etc/java-21-openjdk`

#### Scenario: Lien planté vers le profil
- **GIVEN** sous Linux, un JDK nommé qui contient un lien vers un fichier du profil et un lien vers `/etc/shadow`
- **WHEN** le plan du bac à sable est construit
- **THEN** aucune des deux cibles n'est montée

### Requirement: Une racine d'outillage trop large est refusée avant le lancement
Sous Linux, quand `JAVA_HOME` ou `JDK_HOME` désigne la racine du système de fichiers, un répertoire de premier niveau, le répertoire personnel ou un de ses ancêtres, `MINOS_HOME`, ou un répertoire sous `/etc`, `/proc`, `/sys`, `/dev` ou `/run`, le bac à sable SHALL refuser le plan avant tout lancement, par un message qui nomme la variable et ne contient aucun chemin absolu.

#### Scenario: JAVA_HOME à la racine
- **GIVEN** sous Linux, `JAVA_HOME=/`
- **WHEN** le plan du bac à sable est construit
- **THEN** il échoue par `IllegalStateException` dont le message nomme `JAVA_HOME` sans chemin absolu

#### Scenario: JAVA_HOME au répertoire personnel
- **GIVEN** sous Linux, `JAVA_HOME` égal au répertoire personnel de l'utilisateur
- **WHEN** le plan du bac à sable est construit
- **THEN** il échoue avant lancement et ne monte pas ce répertoire

### Requirement: Une racine d'outillage qui n'est pas un JDK est refusée
Sous Linux, le bac à sable SHALL refuser avant tout lancement une racine réelle de `JAVA_HOME` ou `JDK_HOME` qui n'a pas sa propre `bin/java`, fichier régulier, avec un fichier `release` ou `lib/libjli.so`. Il SHALL aussi refuser une racine située sous `MINOS_HOME` hors de `MINOS_HOME/tools`. Le message MUST NOT contenir de chemin absolu.

#### Scenario: Lien vers un répertoire du profil
- **GIVEN** sous Linux, `JAVA_HOME` qui nomme un lien vers un répertoire `.ssh` du profil
- **WHEN** le plan du bac à sable est construit
- **THEN** il échoue par `IllegalStateException` qui nomme `JAVA_HOME` sans le chemin

#### Scenario: JDK dans MINOS_HOME
- **GIVEN** sous Linux, un JDK sous `MINOS_HOME/registry` et un autre sous `MINOS_HOME/tools`
- **WHEN** le plan du bac à sable est construit pour chacun
- **THEN** le premier est refusé et le second est monté en lecture seule
