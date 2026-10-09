# Spec Delta

## ADDED Requirements

### Requirement: Le lanceur par défaut documenté existe dans une installation standard
Le chemin du lanceur Windows que la documentation du plugin propose par défaut SHALL désigner le fichier que l'installateur et la distribution créent effectivement. Un contrôle du dépôt SHALL échouer si la documentation cite un autre emplacement.

#### Scenario: Chemin documenté conforme
- **WHEN** la documentation du plugin cite `%LOCALAPPDATA%\Programs\MINOS\minos.cmd`
- **THEN** le contrôle documentaire réussit, ce chemin correspondant à `DefaultDirName` de l'installateur et à l'emplacement de `minos.cmd` dans la distribution

#### Scenario: Chemin documenté inexistant
- **WHEN** la documentation cite `…\Programs\MINOS\bin\minos.cmd`
- **THEN** le contrôle documentaire échoue en nommant le fichier, la ligne et l'emplacement attendu
