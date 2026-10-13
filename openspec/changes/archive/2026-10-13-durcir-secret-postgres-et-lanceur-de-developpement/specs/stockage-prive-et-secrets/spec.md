## ADDED Requirements

### Requirement: Un secret écrit par un script d'installation n'existe jamais avec une ACL héritée
Les scripts d'installation qui écrivent le mot de passe PostgreSQL (`configure-runtime-settings.ps1`, `configure-m30-docker-services.ps1`) SHALL créer le fichier vide, le restreindre à l'utilisateur courant (héritage coupé, une seule entrée d'autorisation) et vérifier cette restriction avant d'y écrire le secret. Si la restriction ou sa vérification échoue, le fichier SHALL être supprimé, le secret SHALL NOT avoir été écrit et le script SHALL échouer.

#### Scenario: Écriture nominale dans un dossier d'ACL large
- **GIVEN** un dossier de données dont l'ACL accorde la lecture au groupe Utilisateurs
- **WHEN** la fonction d'écriture du secret est exécutée
- **THEN** le fichier final n'a pas d'héritage et une seule entrée d'autorisation, celle du SID courant, et son contenu est le secret

#### Scenario: Le secret arrive dans un fichier déjà restreint
- **GIVEN** une sonde qui lit l'ACL du fichier au moment où son contenu devient non vide
- **WHEN** la fonction d'écriture est exécutée
- **THEN** l'ACL observée par la sonde est déjà restreinte à l'utilisateur courant

#### Scenario: Échec de la restriction
- **GIVEN** une restriction d'ACL qui échoue (échec injecté)
- **WHEN** la fonction d'écriture est exécutée
- **THEN** le script échoue, le fichier n'existe plus et le secret n'a été écrit à aucun moment

### Requirement: Un fichier de secret existant est revérifié
Quand le fichier du mot de passe existe déjà, le script SHALL le restreindre de nouveau et vérifier son ACL avant de le lire ou de le conserver. Si la restriction n'est pas possible, le script SHALL échouer sans modifier le contenu et SHALL indiquer que le mot de passe peut avoir été exposé.

#### Scenario: Fichier existant d'ACL héritée
- **GIVEN** un fichier de mot de passe existant dont l'ACL est héritée d'un dossier large
- **WHEN** `New-ManagedPassword` est exécutée
- **THEN** l'ACL du fichier est restreinte à l'utilisateur courant avant que son contenu ne soit lu

#### Scenario: Fichier existant non restreignable
- **GIVEN** un fichier existant dont la restriction échoue (échec injecté)
- **WHEN** `New-ManagedPassword` est exécutée
- **THEN** le script échoue avec un message qui mentionne l'exposition possible, et le contenu du fichier est inchangé
