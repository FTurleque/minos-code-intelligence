# M1.2 — Politique d'ignore et registre local

Statut : **incrément en cours — validation locale requise**

Date : **22 juillet 2026**

Suivi : issue #6.

## Objectif

M1.2 complète la découverte M1.1 avec deux responsabilités distinctes :

1. empêcher les fichiers et modules volontairement ignorés de contaminer la découverte ;
2. attribuer et conserver des identités projet/workspace stables entre plusieurs exécutions de MINOS.

## Politique d'ignore

`ProjectIgnorePolicy` combine trois niveaux :

1. exclusions techniques MINOS non ré-includables : `.git`, `.idea`, `.minos-m0`, `node_modules`, `target`, `dist`, `out` ;
2. règles du `.gitignore` situé à la racine du projet ;
3. règles du `.minosignore` situé à la racine du projet.

Un chemin ignoré par `.gitignore` reste ignoré même si `.minosignore` tente de le réinclure. `.minosignore` sert donc à **resserrer** la portée d'analyse, pas à contourner les exclusions Git.

Sous-ensemble M1.2 supporté :

```text
# commentaire
!negation
/pattern-ancre
repertoire/
*.ext
**/segment
file?.ts
[ab].ts
\!literal
\#literal
```

Les règles sont évaluées dans l'ordre ; la dernière règle correspondante d'un même fichier l'emporte.

### Limite explicite

Les `.gitignore` imbriqués ne sont pas encore interprétés. M1.2 lit uniquement les fichiers d'ignore de la racine du projet. Cette limite est documentée plutôt que masquée et pourra être levée si les dépôts de validation M1 démontrent que cela est nécessaire.

## Intégration à la découverte

`ProjectDiscoveryService` applique la politique avant de déclarer :

- un module détecté par `pom.xml` / `package.json` ;
- un build Maven via `pom.xml` ;
- npm via `package-lock.json` ;
- une racine Java ou TypeScript ;
- un fichier servant de preuve de langage.

Les exclusions techniques lourdes peuvent stopper la traversée d'un sous-arbre. Les règles utilisateur restent évaluées sur les chemins observés afin de préserver les négations du même fichier.

## Registre local

`LocalProjectRegistry` est un registre file-backed dont le répertoire de stockage est fourni explicitement par l'appelant.

Contrats :

- `RegisteredProject` ;
- `RegisteredWorkspace`.

### Identité projet

Lors du premier enregistrement :

```text
racine locale existante
        ↓
canonicalisation du chemin
        ↓
UUID aléatoire attribué par le registre
        ↓
fichier projects/<uuid>.properties
```

Le chemin canonique sert à rendre `registerProject(...)` idempotent pour une même racine locale. Il **n'est pas transformé en UUID**.

Deux racines différentes obtiennent donc deux identités différentes même si leur nom d'affichage est identique.

### Workspace

Un workspace possède également son propre UUID persistant.

L'affectation est stockée uniquement dans l'enregistrement projet via `workspaceId`. La liste `RegisteredWorkspace.projectIds` est reconstruite à la lecture à partir des projets enregistrés. Cela évite une relation dupliquée dans deux fichiers indépendants.

### Persistance

Chaque fichier `.properties` est écrit dans un fichier temporaire puis promu vers sa destination avec `ATOMIC_MOVE` lorsque le système de fichiers le permet. Un remplacement simple est utilisé comme repli si la promotion atomique n'est pas disponible.

Le registre M1 synchronise ses opérations dans une instance JVM. Le verrouillage concurrent entre plusieurs processus MINOS reste hors périmètre de cet incrément.

## Tests M1.2

La suite ajoute :

- `ProjectIgnorePolicyTest` : règles de répertoire, glob, négation, priorité `.gitignore`, exclusions techniques ;
- extension de `ProjectDiscoveryServiceTest` : module ignoré et racine de tests exclue ;
- `LocalProjectRegistryTest` : UUID stables après recréation du registre, deux racines distinctes, persistance d'un workspace, retrait du workspace, refus d'un workspace inconnu ;
- `ProviderBoundaryTest` étendu au package `registry`.

## Hors périmètre

- `.gitignore` imbriqués ;
- réconciliation automatique d'un projet déplacé ;
- suppression/renommage complet des entrées du registre ;
- verrouillage multi-processus ;
- base de données ;
- `IndexerRegistry` et négociation des capacités ;
- cycle de vie d'indexation.

## Validation locale attendue

Depuis la branche M1.2 :

```powershell
.\mvnw.cmd clean verify
```

La PR doit rester en Draft tant que ce build n'est pas vert sur son head courant.
