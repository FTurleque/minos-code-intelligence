## ADDED Requirements

### Requirement: Les versions de Maven écrites dans la documentation courante sont celles du wrapper ou de l'image
Dans la liste fermée des documents courants qui décrivent la toolchain (`STATUS.md`, `ROADMAP.md`, `TOOLCHAIN_POLICY.md`, `code-audit.md`, `docker-runtime.md`, `supply-chain.md`), toute version de Maven écrite SHALL être celle de `.mvn/wrapper/maven-wrapper.properties` ou le `MAVEN_VERSION` de `docker/Dockerfile.mcp.release`. `check-toolchain-baseline.py` SHALL échouer sinon, en nommant le fichier, la ligne et les deux références.

#### Scenario: Version de wrapper périmée dans l'état du produit
- **GIVEN** une copie du dépôt où `docs/project/governance/STATUS.md` écrit « Maven Wrapper : Maven 3.10.0 » alors que le wrapper télécharge 3.10.2
- **WHEN** `python scripts/quality/check-toolchain-baseline.py --root <copie>` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `STATUS.md`, la ligne, 3.10.2 et la version de l'image

#### Scenario: Version de l'image citée par la page utilisateur
- **GIVEN** le dépôt où `docs/user/docker-runtime.md` écrit « Apache Maven 3.9.16 », égal au `MAVEN_VERSION` du Dockerfile
- **WHEN** le gate est exécuté
- **THEN** cette mention est acceptée

#### Scenario: Document de la liste absent
- **GIVEN** l'auto-test du gate, avec un arbre où un document de la liste fermée manque
- **WHEN** `python scripts/quality/test_check_toolchain_baseline.py` est exécuté
- **THEN** le cas constate le refus, qui nomme le document manquant

#### Scenario: Dépôt après réécriture des documents
- **WHEN** `python scripts/quality/check-toolchain-baseline.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0
