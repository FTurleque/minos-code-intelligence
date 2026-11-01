---
id: SEC-0075
title: "Le runtime Docker n'expose que les projets inscrits"
domain: security
rules: false
---

# Le runtime Docker n'expose que les projets inscrits

- **Statut** : Proposed
- **Date** : 2026-11-01
- **Origine** : changement OpenSpec `exposer-seulement-les-projets-inscrits-a-docker` (décision D12)

## Context

Le backend MCP Docker lit les projets de l'hôte dans deux conteneurs : `minos-mcp` (plan de requête, `network_mode: none`) et `minos-admin` (plan d'administration, qui a une sortie réseau pour les dépendances). Monter une racine des projets entière sur `/workspace/projects` rend visible de ces conteneurs tout ce que contient cette racine : autres dépôts, copies de travail d'agents, fichiers divers, inscrits ou non dans un registre MINOS. Refuser les racines dangereuses (profil, racine de volume, dossiers de MINOS) réduit les pires cas sans changer ce modèle.

Docker Desktop ne suit ni lien symbolique ni jonction d'un dossier monté : on ne peut pas construire une racine qui ne contiendrait que les dépôts voulus. Sous Linux, un lien de cible absolue est résolu dans l'espace de noms du conteneur. La seule exposition minimale est un montage par projet.

Options pesées : montage par projet (retenu) ; racine conservée avec un filtrage dans MINOS (un défaut du filtre suffit à lire un autre dépôt : la frontière doit être le montage) ; copie des projets dans un volume (index et sources divergent).

## Decision

1. **Aucune racine des projets n'est montée.** Les fichiers Compose livrés ne montent aucun dossier de projets. `/workspace/projects` ne contient que des montages en lecture seule, un par projet exposé, identiques dans `minos-mcp` et `minos-admin`. Une table vide est valide.
2. **La table des montages est la seule source de ces montages.** Elle est tenue dans `<données Docker>/runtime/project-paths.properties` (format 2), écrite seulement par le bootstrap Docker (E/S privées), qui refuse sans écrire un nom hors de `[a-z0-9][a-z0-9._-]{0,62}`, un doublon sans tenir compte de la casse, un chemin de montage qui en préfixe un autre, un dossier hôte égal à un autre ou qui le contient, un dossier hôte relatif et plus de 64 montages.
3. **Le fichier Compose des montages est généré**, depuis la table, avant chaque démarrage de `minos-mcp`, chaque `minos-admin` et chaque validation ; il n'est jamais édité à la main. Chaque scalaire y est une chaîne JSON, `$` doublé. Un dossier hôte disparu est omis avec un avertissement qui nomme le montage.
4. **Un dossier exposé est contrôlé côté hôte à l'ajout et avant chaque montage** : absolu, existant, sans `$`, ni point de reparse ni sous un parent qui en est un, refusé s'il est une racine de volume, le profil ou l'un de ses parents, ou s'il est, contient ou se trouve dans `%LOCALAPPDATA%`, `%APPDATA%` ou un dossier de MINOS.
5. **Une racine unique migre sans élargir l'exposition** : chaque projet inscrit garde son chemin conteneur ; un projet inscrit à la racine elle-même, isolé ou dont le dossier est refusé n'est pas monté. Le registre n'est pas réécrit.
6. **Les paramètres hérités de racine ne montent rien** : `/DOCKERPROJECTSROOT`, `-ProjectsRoot` et la valeur `ProjectsRoot` enregistrée sont ignorés avec un avertissement.

Exclu : le retrait de l'inscription d'un projet (le retrait d'un montage retire son exposition, pas son inscription ni son index) ; l'ajout d'un montage à chaud (`minos-mcp` est recréé).

## Do's and Don'ts

### Do

- Exposer un projet par `mcp-lifecycle.ps1 -Action AddProject`, qui contrôle le dossier puis l'ajoute à la table.
- Passer le fichier Compose généré des montages à chaque appel Compose du cycle de vie.
- Nommer un montage par son nom dans les diagnostics ; n'afficher un chemin hôte que sur demande explicite de l'utilisateur de son poste.

### Don't

- Monter un dossier de projets par une variable d'environnement ou dans un fichier Compose livré.
- Éditer à la main le fichier Compose des montages, ou écrire la table hors du bootstrap Docker.
- Monter la racine d'une installation migrée, un projet isolé ou un dossier refusé.

## Consequences

### Positives

- Un dossier que l'utilisateur n'a pas exposé n'est visible d'aucun conteneur, y compris `minos-admin` et sa sortie réseau.
- Le registre, local ou PostgreSQL, n'est pas réécrit par la migration.

### Négatives

- Exposer ou retirer un projet recrée `minos-mcp` et interrompt les sessions MCP attachées.
- Un dossier placé sous l'ancienne racine et jamais inscrit n'est plus visible après la migration.

### Risques

- La création par Docker Desktop d'un point de montage imbriqué (`/workspace/projects/a/b`) sur un système de fichiers racine en lecture seule n'est pas vérifiée sous Windows.
- 64 montages restent sous les limites connues du partage de dossiers de Docker Desktop, sans mesure.

## Compliance and Enforcement

- Contrôle automatique : `M29DockerAdministrationContractTest` (aucun `MINOS_PROJECTS_DIR` ni cible `/workspace/projects` dans les fichiers Compose livrés) ; tests de `ProjectPathMapping`, `ProjectPathMappingStore`, `ProjectPathMappingService` et `DockerRuntimeBootstrap` (refus de la table) ; `scripts/install/test_docker_script_contexts.py` (override passé à Compose, contrôle du dossier avant le bootstrap).
- Revue : tout nouvel appel Compose du cycle de vie passe le fichier des montages ; aucun montage de projets n'est ajouté ailleurs.

## Mise en œuvre

(Complété à l'implémentation.)

## References

- ARCH-0069 (backends MCP natif et Docker) ; SEC-0070 (code non fiable) ; SEC-0074 (le plan d'administration ne lance aucun provider).
- Changement OpenSpec `exposer-seulement-les-projets-inscrits-a-docker`.
- arc42 : `docs/architecture/arc42/09-decisions.md`.
