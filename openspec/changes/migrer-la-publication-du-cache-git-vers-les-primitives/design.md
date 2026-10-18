# Design

## Context

Suite d'AUD-SEC-03 (sprint 8) : lot « application et registre », partie `minos-integration-git`. HEAD analysé : `4922f4ac` (`develop`). Dépend de `completer-les-primitives-d-e-s-privees` (publication de répertoire). Aucun code, test ni gate n'est modifié par cette spécification.

## État vérifié au HEAD

- `check-private-io.py` : `JGitRemoteRepositoryMaterializer.java` / `Files.move`, maximum 1, position `:401`.
- `JGitRemoteRepositoryMaterializer.java:399-405` : `Files.move(source, target, ATOMIC_MOVE)` ; `AtomicMoveNotSupportedException` → `IOException("remote repository cache requires atomic directory publication", cause)`. **Pas de repli non atomique** : déjà fail-closed. Un seul appelant : `:198` (`moveDirectory(temporary, entry)`).
- Contexte de l'appel (`:170-208`) : l'entrée existante est supprimée avant (`:170`, `deleteCacheTree(entry)`), le répertoire temporaire `cacheRoot/<préfixe><uuid><suffixe>` est rendu privé (`:177-178`), un clone complet y est fait, les métadonnées y sont écrites (`writeProperties`), puis le répertoire est déplacé sur `entry` ; en cas d'échec de l'éviction l'entrée est supprimée. Le verrou du cache (`BoundedFileLease`, `SharedCacheLeaseRegistry`) entoure l'ensemble.
- `moveFile` (`:407-409`) appelle déjà `DurableAtomicFile.replace` ; `PrivateLocalStorage.writePrivateFile` écrit les métadonnées (`:390`).
- **Gate littéral** : `scripts/quality/check-remote-distributed-consistency.py:81` exige `ATOMIC_MOVE` dans `JGitRemoteRepositoryMaterializer.java` ; la chaîne quitte le fichier, l'attente est modifiée dans la même tâche (par `moveDirectory`). `check-post-mne.py` lit aussi ce fichier sans attente d'appel de `Files`.
- Tests : `JGitRemoteRepositoryMaterializerTest`, `JGitRemoteRepositoryMaterializerLockTest`. Scope JaCoCo `m25-remote-distributed-indexing`.
- Recouvrements : aucun changement actif ne cite ce fichier.

**Non vérifié** : aucun test exécuté ; le comportement de `moveDirectory` sous Windows avec un antivirus actif sur un clone fraîchement écrit n'est pas mesuré.

## Écarts avec la justification de la liste

1. La liste dit « vers `PrivateLocalStorage` / `DurableAtomicFile` » : `DurableAtomicFile.publish` et `replace` ne publient pas un répertoire (lien dur de fichier, `fsync` de fichier). La primitive est `moveDirectory`, fournie par le socle.
2. Le site est déjà fail-closed ; le gain n'est pas d'écarter un repli mais de gagner la reprise bornée sous Windows (aujourd'hui un seul essai : un antivirus qui tient un fichier du clone fait échouer la publication) et la synchronisation du parent sous Linux.

## Goals / Non-Goals

**Goals** : une entrée retirée ; le message public d'échec inchangé ; la reprise Windows bornée.

**Non-Goals** : changer le clonage, l'éviction, les budgets ou le verrou.

## Decisions

### D1. Message d'échec — recommandation : conserver

`DurableAtomicFile.moveDirectory` échoue par une `IOException` dont la cause est `AtomicMoveNotSupportedException` quand le rename atomique manque ; le matérialiseur la ré-enveloppe dans le message actuel. Les autres échecs sont propagés tels quels. Option B : adopter le message de la primitive. Le message n'existe que dans la source (`JGitRemoteRepositoryMaterializer.java:403`) : aucun test ni document ne le cite ; le conserver est un choix de continuité, pas une obligation, et le test ajouté le fixe.

### D2. Entrée existante — recommandation : l'attente du socle (cible inexistante) suffit

L'entrée est supprimée avant le clone (`:170`) sous le verrou du cache : la cible n'existe pas au moment du déplacement. Une cible apparue entre-temps (hors verrou) fait échouer le déplacement (`FileAlreadyExistsException`) au lieu d'être écrasée. Option B : supprimer à nouveau avant le déplacement. Rejetée : élargit la fenêtre où l'entrée n'existe pas.

### D3. Gate littéral — recommandation : l'attente suit le code

`check-remote-distributed-consistency.py:81` : `ATOMIC_MOVE` remplacé par `DurableAtomicFile.moveDirectory`, dans la tâche qui modifie le fichier ; son auto-test, s'il couvre cette liste, est ajusté.

## Constat de mise en œuvre (2026-10-18)

La couture de test est un constructeur de paquet qui reçoit un `DirectoryPublisher` (interface fonctionnelle, `ATOMIC` par défaut) : instance, non statique. Les assistants de `JGitRemoteRepositoryMaterializerTest` sont devenus visibles du paquet pour le nouveau test. Vingt matérialisations successives sous Windows avec la protection en temps réel de Defender : 0 échec.

## Direction des dépendances

`adapters → engine` (ADR 0022) ; `com.minos.io` est déjà importé (`DurableAtomicFile`, `PrivateLocalStorage`, `BoundedFileLease`, `StaleScratchReclamation`). Aucun package nouveau.

## Qualification des capacités

- Publication atomique de l'entrée de cache : **qualifiée** sous Linux ; **partielle** sous Windows (synchronisation du parent inerte, reprise bornée à environ une seconde).

## Windows et Linux

Aucune branche de plateforme ajoutée. Test d'échec atomique : plateforme simulée côté moteur ; ici, un déplaceur injecté (point d'injection package-private ajouté avec le test).

## Risks / Trade-offs

- **Reprise de ~1 s** sous Windows puis échec franc : meilleur que l'échec immédiat actuel ; mesure manuelle (tâche 1.3).
- **Gate littéral modifié** : une attente de gate qui suit le code est le motif prévu par `minos-literal-gates` ; l'ancienne attente est remplacée, pas supprimée.
