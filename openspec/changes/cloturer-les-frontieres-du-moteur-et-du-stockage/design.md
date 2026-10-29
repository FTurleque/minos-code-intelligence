# Design

## État vérifié au HEAD (`810dcfb2`, 2026-10-28)

### AUD-ARC-05

- A12 est vide (`PROCESS_LAUNCHERS: dict[str, int] = {}`, `scripts/architecture/check-module-boundaries.py:593`). Une classe est retenue comme lanceur si elle contient `ProcessBuilder` ou `Runtime.getRuntime()` immédiatement suivi de `.exec` (`:594`, utilisé à `:840`). **Prouvé sur copie** (rouge de la tâche 1.1) : `Runtime r = Runtime.getRuntime(); r.exec("x")`, `var rt = …; rt.exec(…)`, un champ `Runtime`, un paramètre `java.lang.Runtime` et `Runtime.getRuntime()::exec` passent le garde, 5 cas sur 5.
- `minos-domain`, `minos-engine` et `minos-application` n'appellent `Runtime.getRuntime()` nulle part en production (recherche au HEAD) : l'extension n'ajoute aucune entrée à A12.
- Part de code concret du moteur, mesurée sur `minos-engine/src/main/java`, chaque fichier compté pour le genre de sa première déclaration de premier niveau, toutes ses lignes comprises : 170 fichiers, 17 526 lignes ; classes 69 fichiers / 12 836 lignes (73,2 %), records 50 / 3 374, interfaces 37 / 1 122, enums 14 / 194. C'est le chiffre de la revérification du 28 octobre ; l'audit relevait 12 136 lignes de classes sur 16 932 (72 %).
- Le changement actif `decouper-les-classes-de-plus-de-800-lignes` (0/18) extrait de `PrivateLocalStorage` trois classes concrètes (`WriteDenyDiagnosis`, `ProcessOwner`, `PlatformAccessControlDiscovery`) et une interface, toutes dans `com.minos.io` du moteur (`proposal.md:21`, `tasks.md:13-16`).

### AUD-ARC-12

- La racine `storage` appartient à `minos-engine` et à `minos-storage` (`PACKAGE_ROOTS_BY_MODULE`, `check-module-boundaries.py:636-641`). Le commentaire de la table le permet explicitement : une racine peut appartenir à plusieurs modules, A3 garde chaque package dans un seul.
- Packages déclarés sous cette racine, tous modules et tests compris : `com.minos.storage` (10 fichiers, `minos-engine` seul : `StorageBackend`, `StorageBackendProvider`, `StorageBackendConfiguration`, `MinosRuntimeSettings`, `PersistentRetentionPolicy`, `StorageRetentionService`…), `com.minos.storage.codec` (25), `.home` (2), `.local` (106), `.postgresql` (62), tous dans `minos-storage`.
- `STORAGE_PACKAGES` (`:904`) ferme la liste des sous-packages de `minos-storage`, mais seulement dans ce module (`check_storage_package_isolation`). Aucun contrôle n'empêche `minos-engine` de créer `com.minos.storage.cache` : A14 l'accepte (racine possédée), A3 aussi (package déclaré par un seul module).
- ADR 0068 § 4 : « les codecs réellement partagés rejoignent `com.minos.storage.codec` dans le même artefact ». § 5 : « Aucun package existant n'est renommé », et un nouveau package va de préférence sous l'espace de noms propre au module. `minos-storage` en a deux, un par backend (`com.minos.storage.local`, `com.minos.storage.postgresql`).

## Écarts avec l'action proposée par l'audit

- **AUD-ARC-05** propose d'extraire l'ACL (fait par SH-08), de déplacer `hosted` (l'ADR 0068 le laisse au moteur après la cartographie de SH-08) et d'ajouter une règle « le moteur n'appelle ni `ProcessBuilder` ni `Runtime.exec` » (A12, étendue ici). Il ne demande pas de seuil sur la part de code concret. La consigne de clôture prévoyait un cliquet chiffré. L'inventaire montre qu'il serait trompeur (D1).
- **AUD-ARC-12** propose une convention `com.minos.<couche>.<capacité>` vérifiée par le garde. L'ADR 0068 § 5 l'a limitée aux nouveaux packages. Ce changement ne renomme rien. Il ajoute la seule règle qui manque pour que le nom d'un sous-package de `com.minos.storage` dise son module (D2).

## Décisions

### D1 — Mesurer la part de code concret du moteur sans la plafonner (décision du propriétaire, 2026-10-28)

Options :

- A. **Acceptation écrite et mesure affichée** (retenue). Le garde affiche à chaque exécution fichiers et lignes par genre de déclaration (`engine_concrete_code`) et n'échoue jamais sur ce chiffre. L'ADR 0068 écrit pourquoi.
- B. Plafond de lignes de classes (12 836). Toute ligne ajoutée à une classe du moteur le dépasse. Le découpage de `PrivateLocalStorage` le dépasse aussi (en-têtes, délégation), alors qu'il est voulu.
- C. Plafond de fichiers de classes (69). Il est insensible aux modifications internes, mais le découpage ajoute 3 classes et le ferait échouer.

B et C pénalisent le découpage et récompensent le passage d'une classe en record ou l'ajout d'interfaces, sans rien retirer du moteur. Ce qu'AUD-ARC-05 visait de concret (le moteur lance un processus, rôle du runtime local) est tenu par A12. Les frontières du moteur sont tenues par A10, A11 et A14.

### D2 — Réserver les sous-packages de `com.minos.storage` à `minos-storage`, sans déplacement

Options :

- A. **Règle de garde** (retenue). Hors de `minos-storage`, aucune source, de production ou de test, ne déclare un package sous `com.minos.storage.`. Le moteur garde le package racine. Combinée à `STORAGE_PACKAGES`, elle fait de `com.minos.storage.<x>` un nom qui désigne toujours `minos-storage`.
- B. Déplacer `codec` et `home` sous un espace de noms du module. Il n'en existe pas de commun aux deux backends. Placés sous `com.minos.storage.local`, les packages partagés seraient nommés par le backend PostgreSQL, ce que l'ADR 0068 § 4 interdit (`local` et `postgresql` ne dépendent pas l'un de l'autre). Un espace neuf (`com.minos.storage.shared.codec`) renomme deux packages existants, contrairement au § 5. Il contredit aussi le § 4, qui nomme `com.minos.storage.codec`, et touche les portées JaCoCo, les services `META-INF` et `STORAGE_PACKAGE_RULES`.
- C. Consigner seulement un écart dans l'ADR. Le défaut resterait ouvert pour tout nouveau package du moteur.

La règle s'ajoute à `check_storage_package_isolation`, qui lit déjà l'arborescence `com/minos/storage` de `minos-storage`. Elle compte par chemin, comme cette fonction ; `check_java_layout` garantit que chemin et package concordent.

### D3 — Reconnaître un `Runtime` tenu par un nom

Est un site de lancement tout `exec` (appel `.exec` ou référence `::exec`) sur un nom déclaré de type `Runtime` ou `java.lang.Runtime` (variable locale, champ, paramètre) ou affecté de `Runtime.getRuntime()` (`var` compris), ainsi que `Runtime.getRuntime()::exec`. Le contrôle est lexical, sur le code sans commentaires ni littéraux, comme le reste d'A12. Un `Runtime` passé par une méthode intermédiaire (`Supplier<Runtime>`, retour de méthode) n'est pas suivi : A12 reste un garde de revue, pas une analyse de flot. La limite est écrite dans l'ADR.

## Direction des dépendances

Aucune arête Maven ni dépendance de package modifiée (ADR 0068 § 2).

## Capacités

| Capacité | État |
|---|---|
| A12 voit un `exec` sur un `Runtime` déclaré ou affecté | qualifiée (auto-tests sur arbre temporaire) |
| A12 suit un `Runtime` à travers un appel de méthode | non supportée (limite écrite) |
| Mesure du code concret du moteur | qualifiée, informative, sans échec |
| Sous-packages de `com.minos.storage` réservés à `minos-storage` | qualifiée, production et test |

## Windows et Linux

Le garde est en Python, bibliothèque standard seule, et il est exécuté par le job `invariants` sur Ubuntu. Les chemins affichés sont normalisés en `/`.

## Décisions en attente

Aucune : D1 a été tranchée par le propriétaire le 2026-10-28.
