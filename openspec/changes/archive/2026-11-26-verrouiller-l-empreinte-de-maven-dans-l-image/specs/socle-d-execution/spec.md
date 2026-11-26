## ADDED Requirements

### Requirement: Chaque archive épinglée de l'image est vérifiée avant usage
Pour chaque `ARG` d'empreinte que `embedded-tools.json` attribue à `docker/Dockerfile.mcp.release`, une seule instruction `RUN` SHALL le consommer. Elle SHALL commencer par `set -e`, contenir une fois `sha256sum -c -` sur cet `ARG`, sans `||`, `set +e` ni `trap`, et n'utiliser le fichier avant ce contrôle que pour le télécharger ou l'effacer. `check-tools-manifest.py` SHALL échouer sinon, en nommant l'`ARG` et la ligne.

#### Scenario: Contrôle neutralisé par un OU logique
- **GIVEN** une copie du dépôt où la ligne de contrôle de `MAVEN_ZIP_SHA256` finit par `sha256sum -c - || true; \`
- **WHEN** `python scripts/quality/check-tools-manifest.py --root <copie>` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `MAVEN_ZIP_SHA256` et la ligne

#### Scenario: Instruction sans arrêt sur erreur
- **GIVEN** une copie du dépôt où l'instruction qui vérifie `NODE_LINUX_SHA256` commence par `set -ux`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il se termine avec le code 1 et nomme `NODE_LINUX_SHA256`

#### Scenario: Archive utilisée avant son contrôle
- **GIVEN** l'auto-test du gate, avec un Dockerfile où `unzip` de l'archive Maven précède la ligne de contrôle
- **WHEN** `python scripts/quality/test_check_tools_manifest.py` est exécuté
- **THEN** le cas constate le refus et le message qui nomme l'`ARG`

#### Scenario: Contrôle supprimé pour un outil du catalogue
- **GIVEN** une copie du dépôt où la ligne de contrôle de `SCIP_DOTNET_NUPKG_SHA256` est supprimée
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il se termine avec le code 1 et nomme `SCIP_DOTNET_NUPKG_SHA256`

#### Scenario: Dockerfile au HEAD
- **WHEN** `python scripts/quality/check-tools-manifest.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0 et les six `ARG` d'empreinte du catalogue sont reconnus vérifiés
