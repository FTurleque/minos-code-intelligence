# ADR-0037 — Traiter native et Docker comme backends MCP explicites et fail-closed

Date : 2 août 2026

Statut : **Accepted pour le contrat S1 ; parité Docker non acquise**

Origine : M29

## Contexte

ADR-0021 a correctement établi le runtime natif comme moteur autonome d'indexation et a conservé Docker comme serveur MCP read-only optionnel. Depuis, MINOS dispose d'un installateur Windows, d'un point d'entrée stable `minos.exe mcp`, d'un registre persistant, de snapshots structurés et d'un vector store sémantique v2.

M29 doit faire évoluer Docker vers un backend autonome de premier rang sans casser les intégrations Copilot, Claude ou Codex. Le risque principal serait de masquer une indisponibilité Docker en ouvrant l'application native, ou d'obliger chaque client IA à connaître la topologie du backend.

L'ouverture actuelle de `MinosApplication` initialise immédiatement le registre, les snapshots, l'index state, les fingerprints, le vector store et les runtimes providers. Le choix du backend doit donc intervenir **avant** cette ouverture.

## Décision

### Point d'entrée stable

Les clients IA continuent d'invoquer :

```text
minos.exe mcp
```

Le launcher résout le home client puis charge une configuration backend versionnée avant toute initialisation métier spécifique au runtime.

### Contrat backend

Le format courant est `formatVersion=1` et accepte exclusivement :

```text
backend=native
```

ou :

```text
backend=docker
```

La configuration est stockée sous :

```text
<MINOS_HOME>/runtime/backend.properties
```

Elle contient également le nom du conteneur Docker géré et le timeout de probe.

Une installation pré-M29 sans fichier est migrée une seule fois vers une configuration **native explicite**. Cette migration de compatibilité n'est pas un fallback runtime. Dès qu'un fichier existe, version inconnue, backend inconnu, propriété inconnue ou valeur invalide échoue fermée.

### Routage natif

Pour `native`, le routeur ouvre `MinosApplication` sur le home résolu et démarre `MinosMcpServer` dans le processus courant.

### Routage Docker

Pour `docker`, le routeur n'ouvre pas `MinosApplication` côté hôte. Il vérifie dans l'ordre :

1. `docker version` et disponibilité du daemon ;
2. existence et état `Running` du conteneur MINOS explicitement configuré ;
3. seulement ensuite une session STDIO avec `docker exec -i` vers `com.minos.mcp.MinosMcpServer`.

L'entrée, la sortie et stderr de la session Docker sont reliées directement au processus `minos.exe` afin de préserver le transport MCP STDIO.

Si Docker, le daemon ou le conteneur est indisponible, MINOS échoue explicitement. **Aucun fallback Docker → natif n'est autorisé.**

Les probes sont bornés dans le temps. Une interruption détruit le processus enfant et restaure le statut d'interruption Java. Les erreurs de bootstrap restent mappées sur le code d'erreur d'exécution CLI existant ; une session MCP normale retourne succès.

## Switching

M29-S7 appliquera une bascule transactionnelle :

```text
prepare backend cible
→ validate
→ handshake
→ écrire atomiquement backend.properties
→ seulement ensuite retirer ce qui peut l'être de l'ancien backend
```

Une validation échouée laisse la configuration active inchangée. Le store S1 fournit déjà l'écriture atomique nécessaire ; S7 ajoute l'orchestration d'installation/rollback.

## Conséquences

### Positives

- les configurations Copilot/Claude/Codex restent stables ;
- le backend est un choix MINOS explicite, versionné et testable ;
- sélectionner Docker ne crée pas de registre/snapshot/vector store natif par effet de bord ;
- l'indisponibilité Docker est visible et déterministe ;
- la future bascule de backend n'exige pas de réécrire les clients IA.

### Contraintes

- le home côté hôte conserve la petite configuration de routage même lorsque les données métier vivent dans le volume Docker ;
- le conteneur doit être préparé et validé avant de sélectionner `docker` ;
- S1 ne prouve pas encore l'autonomie d'indexation, la portabilité des données ni la parité fonctionnelle : ces preuves appartiennent à S2–S8 ;
- aucun document ne peut déduire la parité Docker de l'acceptation de cet ADR.

## Relation avec ADR-0021

ADR-0037 **supersède partiellement** ADR-0021 uniquement sur la conclusion selon laquelle Docker reste nécessairement un simple MCP read-only non autonome.

Les principes encore valides d'ADR-0021 restent conservés : absence d'installation silencieuse de dépendances projet, traçabilité des providers, runtime utilisateur reproductible et profil MCP query-only durci. M29 introduit un plan d'administration/indexation Docker distinct afin de ne pas affaiblir le serveur MCP read-only.

## Alternatives rejetées

### Configurer directement chaque client vers `docker exec`

Rejeté : rendrait le backend visible dans chaque configuration tierce et transformerait chaque switch en mutation multi-client risquée.

### Essayer Docker puis tomber silencieusement sur native

Rejeté : le runtime réellement utilisé deviendrait ambigu, les données pourraient diverger et un utilisateur demandant l'isolation Docker obtiendrait un comportement natif sans le savoir.

### Partager automatiquement le même chemin physique de home

Rejeté : Windows et Linux ont des localisations physiques différentes. S2 introduit une identité logique et un mapping typé plutôt qu'une substitution de chaînes.

## Liens

- issue : #107
- roadmap : [`../roadmap/M29_EXECUTION.md`](../roadmap/M29_EXECUTION.md)
- ADR historique : [`0021-native-runtime-autonomous-indexing.md`](0021-native-runtime-autonomous-indexing.md)
- MCP : [`../user/mcp.md`](../user/mcp.md)
- installation : [`../user/production-installation.md`](../user/production-installation.md)
