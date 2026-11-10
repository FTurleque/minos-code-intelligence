## MODIFIED Requirements

### Requirement: Les règles de mise en page de build valent aussi dans un profil
Le garde SHALL refuser `sourceDirectory`, `testSourceDirectory` et les `includes`/`excludes` du compilateur dans le `<build>` d'un `<profile>` comme dans le `<build>` racine, et SHALL NOT refuser les `plugins` d'un profil.

#### Scenario: Répertoire de sources dans un profil
- **WHEN** un POM déclare `<sourceDirectory>` dans `profiles/profile/build`
- **THEN** le garde échoue en nommant le module et le profil

#### Scenario: Profils existants
- **WHEN** le garde s'exécute sur le dépôt (profils `audit-*` du POM racine)
- **THEN** il se termine avec le code 0
