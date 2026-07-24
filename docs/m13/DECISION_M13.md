# Décision M13 — Intégration NEXUS

Date : **24 juillet 2026**

Statut : **PRÉPARÉE — décision finale après validation inter-dépôt**

## Question

NEXUS peut-il consommer la Code Intelligence normalisée de MINOS pour enrichir son contexte technique sans couplage binaire entre les deux moteurs et sans brouiller leurs responsabilités ?

## Contraintes observées

- MINOS est compilé et validé avec Java 24 ;
- NEXUS conserve Java 21 comme niveau minimal ;
- NEXUS possède déjà `CodeIndexImporter` pour les index de code externes ;
- NEXUS possède déjà son propre ranking, sa sélection sous budget et son `ContextBuilder` ;
- MINOS possède déjà la connaissance normalisée des symboles, relations, preuves et provenance ;
- les deux moteurs doivent rester utilisables indépendamment.

## Options étudiées

### A — Dépendance Maven NEXUS → MINOS

Rejetée.

Elle imposerait à NEXUS le bytecode Java 24 et créerait un couplage de modèles contraire aux frontières des deux projets.

### B — Réimplémenter la connaissance MINOS dans NEXUS

Rejetée.

Cette option dupliquerait symboles, relations, preuves et logique de normalisation et ferait diverger les sources de vérité.

### C — Faire sélectionner le contexte final par MINOS

Rejetée.

Le ranking, les sources contextuelles, les contraintes et le budget de tokens sont la responsabilité de NEXUS.

### D — Contrat JSON local versionné, consommé par un importer NEXUS optionnel

Retenue.

MINOS exporte sa connaissance active en lecture seule. NEXUS lance le JAR MINOS avec un runtime Java 24 explicitement configuré, valide le contrat puis transforme seulement le sous-ensemble représentable dans son modèle d’index.

## Décision retenue

M13 adopte :

```text
MINOS NexusExportContract v1
        +
CLI nexus-export JSON
        +
NEXUS MinosCodeIndexImporter opt-in
```

Le transport est local et inter-processus. Aucun protocole réseau ni framework serveur n’est requis.

## Invariants

1. MINOS ne référence aucun type NEXUS.
2. NEXUS ne référence aucun type MINOS.
3. MINOS ne calcule ni ranking NEXUS ni budget de contexte.
4. NEXUS ne reconstruit pas les faits MINOS qu’il peut importer.
5. Une donnée MINOS non représentable côté NEXUS est ignorée explicitement, jamais convertie vers une sémantique approximative.
6. La provenance `minos` reste identifiable dans NEXUS.
7. L’intégration est désactivée par défaut.
8. Un runtime Java 24 est explicitement fourni à NEXUS lorsque MINOS est activé.
9. Aucun accès réseau n’est nécessaire.
10. Les deux moteurs continuent à fonctionner indépendamment.

## Conséquences

### Positives

- compatibilité Java 21 / Java 24 sans abaisser MINOS ni relever NEXUS ;
- contrat testable et versionnable ;
- absence de dépendance Maven privée croisée ;
- réutilisation du pipeline NEXUS existant ;
- conservation du ranking et du budget NEXUS ;
- possibilité d’enrichir NEXUS avec une connaissance plus riche que son analyse locale.

### Acceptées

- lancement d’un processus Java supplémentaire lors de l’import MINOS ;
- besoin de configurer le chemin du JAR MINOS et le runtime Java 24 ;
- sous-ensemble des kinds/relations MINOS actuellement représentable dans NEXUS ;
- nécessité d’un replay inter-dépôt pour chaque évolution incompatible du contrat.

## Porte

Verdict préparé :

> **OUI, via un contrat JSON local versionné et un importer NEXUS optionnel : MINOS reste la source de faits de Code Intelligence, tandis que NEXUS reste seul responsable du classement, de la sélection et du budget du contexte.**

Ce verdict devient définitif après validation exacte des deux heads et succès du replay réel Java 24 → Java 21 décrit dans `NEXUS_INTEGRATION.md`.
