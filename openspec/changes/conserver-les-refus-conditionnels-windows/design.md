# Design

## Context

Sprint 8 de l'audit du 10 octobre 2026 : **AUD-SEC-06**. HEAD analysé : `949e1569` (`develop`). À faire après `etendre-le-garde-d-e-s-privees-aux-ecritures-brutes` (les appels ajoutés ici passent sous le gate étendu) et avant le chantier AUD-ARC-05 (sprint 10). Le prérequis AUD-TST-03 est fermé.

## État vérifié au HEAD

### AUD-SEC-06 — confirmé, action contraire à un test existant

- `minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java` : Javadoc `:62-72` (réécriture au premier contact par JVM, puis `icacls /inheritance:d` ; `:71-72` « Known limit: a conditional DENY (XD) is invisible to Java as well, so the rewrite drops it ») ; `:381` `acl.getAcl()`, `:382` `ownerOnlyAcl`, `:390` `acl.setAcl(desired)` ; `:411-414` les entrées `DENY` visibles sont conservées ; classe imbriquée `WindowsDacl` (`:618`), `protect` lance `icacls … /inheritance:d /L /q` (`:634-635`) avec contrôle de `SystemRoot` (`:630-633`) et délai de 10 s (`:619`, `:639-642`).
- `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/WindowsAppContainerWorkerSandboxBackend.java` : `:620` octroi `(OI)(CI)F` à l'identité du jeton sur une racine d'écriture, sauté si le propriétaire détient déjà une entrée héritable (`:616`) ; `:703` octroi `(RX)` d'un SID de sonde, retiré en `:712` ; `runIcacls` `:720-727` : `Path.of(System.getenv("SystemRoot"), …)` sans contrôle de nullité, `process.waitFor()` sans délai.
- **Sites non cités par l'audit** : les octrois faits au conteneur sont en PowerShell, `minos-runtime-local/src/main/resources/com/minos/runtime/local/windows-appcontainer-sandbox-v4.ps1.template` — `Grant-AppContainerDirectory` (`:474`), `Grant-AppContainerFile` (`:482`), `Remove-AppContainerPath` (`:488`) : `/grant` et `/remove:g` additifs. `minos-runtime-local/src/main/resources/com/minos/runtime/local/windows-fragments/appcontainer-private-storage.ps1frag:35` ajoute une règle d'accès de **registre**. `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosStrongProcessLauncher.java:161` fait `setAcl(List.of(ownerOnly))`. `scripts/install/configure-runtime-settings.ps1:74` fait `/inheritance:r /grant:r`.
- Tests existants, `minos-engine/src/test/java/com/minos/io/PrivateLocalStorageWindowsAclTest.java` (`@EnabledOnOs(OS.WINDOWS)`, `:29`) : `:36` un refus explicite d'administrateur survit ; `:53` un refus hérité survit comme entrée explicite ; `:229` `aConditionalEntryInheritedFromTheParentIsNotKeptByHardening` — une **autorisation** conditionnelle (XA) héritée ne doit **pas** survivre. Le test lit le SDDL par `icacls /save` (`:305-314`). Aucun test ne porte sur un refus conditionnel (XD).
- Un lecteur du texte SDDL de `icacls /save` existe, `minos-runtime-local/src/main/java/com/minos/runtime/local/sandbox/SddlReplaceRights.java` : il refuse tout type d'entrée autre que `A` et `D`. Il vit dans `minos-runtime-local`, dont `minos-engine` ne peut pas dépendre.
- Aucun usage de `java.lang.foreign` dans le code de production, aucun `--enable-native-access` dans les lanceurs (recherche sans résultat).
- Gates qui lisent le backend Windows : `scripts/remediation/check-mne.py:146`, `scripts/remediation/check-mnd.py:77`, `scripts/remediation/check-post-mne.py:144`, `scripts/remediation/check-minos-01.py:151`, `scripts/remediation/check-post228-hardening.py:42-53`. `scripts/remediation/check-p0-p2.py:139` lit `PrivateLocalStorage.java` (`attributes.isOther()`). Scope JaCoCo `provider-sandbox-windows` (`scripts/quality/check-jacoco.py:131-133`).
- Règle A12 (liste fermée des classes de `minos-engine` qui lancent un processus : `{PrivateLocalStorage}`) : spécifiée par le changement actif `figer-la-dette-de-frontieres-par-des-cliquets`, pas encore implémentée.

**Non mesuré** : le comportement réel d'un refus conditionnel sous Windows pendant le durcissement (ni survie, ni erreur observée) ; le coût d'un processus de lecture de DACL par objet. **Non vérifié** : aucun build ni test exécuté ; les chaînes exactes exigées par les cinq gates sur le backend Windows (seules les lignes d'appel ont été relues) ; ce que fait `:620` après un octroi échoué.

### Écarts avec l'action proposée par l'audit

1. **« Refuser d'écrire quand une ACE conditionnelle est présente » contredit un test.** `PrivateLocalStorageWindowsAclTest.java:229` exige qu'une autorisation conditionnelle héritée soit retirée : la conserver ouvrirait l'emplacement à un autre principal. L'exigence est restreinte aux **refus** conditionnels (XD).
2. **Java ne peut pas réécrire une entrée conditionnelle.** « Conserver » un XD exclut donc `setAcl` sur cet objet : soit l'opération échoue, soit la DACL est modifiée par un autre moyen (D3).
3. **Lire la DACL complète coûte un processus de plus par objet** au premier contact (il y en a déjà un pour `/inheritance:d`), ou un appel natif. Le lecteur SDDL existant est hors d'atteinte de `minos-engine` (ADR 0022) ; détecter un XD ne demande pas de l'analyser entièrement (D2).
4. **La « primitive unique » ne peut pas couvrir PowerShell.** Les trois fonctions du lanceur sont déjà additives. Le périmètre est le code **Java** : les deux sites de `runIcacls`.
5. **`runIcacls` n'a ni délai ni contrôle de `SystemRoot`** (`:720-727`), à la différence de `PrivateLocalStorage` (`:630-642`).
6. **L'emplacement de la primitive doit anticiper AUD-ARC-05** : rien de nouveau ne lance un processus depuis `minos-engine` ; la primitive d'octroi vit dans `minos-runtime-local`.
7. **Linux n'est pas concerné** : la branche POSIX (`:373-377`) ne change pas.

## Goals / Non-Goals

**Goals** : aucune restriction d'administrateur, conditionnelle ou non, n'est levée par le durcissement ; une DACL illisible fait échouer ; les octrois Java du bac à sable sont additifs, bornés et faits en un seul endroit.

**Non-Goals** : évaluer la condition d'une entrée conditionnelle ; conserver une autorisation conditionnelle ; retirer `icacls` de `minos-engine` ; toucher aux scripts PowerShell.

## Decisions

### D1. Seuls les refus conditionnels sont protégés

Un refus conditionnel (XD) n'est jamais retiré. XA hérité : toujours retiré, le test `:229` reste vert sans modification.

### D2. Lecture de la DACL complète — **décision en attente du propriétaire**

Option A (recommandée) : `icacls <objet> /save <fichier>` lancé depuis la classe imbriquée `WindowsDacl`, qui lance déjà `icacls` ; recherche d'une entrée de type `XD` dans le texte SDDL, sans l'analyser davantage. Coût : un processus de plus par objet et par JVM, au premier contact seulement (même mémoire que la protection d'héritage) ; un fichier temporaire à créer par une primitive (sous le gate étendu). Aucune classe lanceuse nouvelle : la liste A12 ne change pas. Texte illisible, code de sortie non nul ou délai dépassé : échec.

Option B : appel natif (`GetNamedSecurityInfoW` puis conversion SDDL) par l'API FFM. Aucun processus, aucun fichier, et la protection d'héritage pourrait suivre (ce qui servirait AUD-ARC-05). Mais : première utilisation de FFM dans le dépôt, `--enable-native-access` à ajouter à tous les lanceurs (CLI, MCP, image Docker, installateur Windows), et un ADR. À évaluer avec les décisions du sprint 10.

### D3. En présence d'un refus conditionnel — **décision en attente du propriétaire**

Option A (recommandée) : **refuser**. Le durcissement échoue avant `setAcl`, par une erreur d'usage sans chemin absolu, qui dit qu'une restriction conditionnelle posée sur l'emplacement de données empêche MINOS de le rendre privé et qu'un administrateur doit la retirer ou déplacer `MINOS_HOME`. Fail-closed, simple, sans écriture de DACL hors de Java.

Option B : **conserver** en ne passant pas par `setAcl` pour cet objet : retrait des entrées d'autres principaux par `icacls /remove:g`, entrée du propriétaire par `/grant`, puis `/inheritance:d`. MINOS reste utilisable, mais deux chemins de durcissement coexistent et le second n'est prouvé que par des tests Windows.

Dans les deux cas, le refus conditionnel est encore présent dans la DACL après l'appel.

### D4. Primitive d'octroi Java unique, dans `minos-runtime-local`

Une classe package-private du package `sandbox` porte le seul lancement d'`icacls` du backend : octroi additif (`/grant`, jamais `/grant:r`, jamais `setAcl`) pour **une** identité donnée par SID, retrait (`/remove:g`), contrôle de `SystemRoot`, délai borné, sortie ignorée. Un échec, un délai dépassé ou un répertoire Windows inconnu rend « non accordé » de façon explicite ; l'appelant échoue. `runIcacls` disparaît du backend ; les chaînes que les gates exigent dans `WindowsAppContainerWorkerSandboxBackend.java` y restent (tâche 1.2).

### D5. La Javadoc dit le comportement garanti

Le paragraphe « Known limit » (`:71-72`) est remplacé par la règle de D1 et D3. `docs/user/troubleshooting.md` reçoit l'erreur nouvelle si D3-A est retenue.

## Direction des dépendances

`minos-engine` : changement interne à `com.minos.io`, aucun import nouveau, aucune classe lanceuse ajoutée. `minos-runtime-local` : classe nouvelle dans un package gardé par A10, non exportée. `minos-engine ↛ minos-runtime-local` respecté (ADR 0022) : le lecteur `SddlReplaceRights` n'est ni déplacé ni réutilisé depuis `minos-engine`.

## Qualification des capacités

- Conservation des refus ordinaires : qualifiée, inchangée.
- Conservation des refus conditionnels : **non supportée** au HEAD (limite documentée) ; qualifiée sous Windows par ce changement, sur la preuve du test XD exécuté en CI Windows.
- Octrois du lanceur PowerShell : hors périmètre, **non requalifiés** ici.

## Windows et Linux

Tout le comportement est propre à Windows. Les tests ajoutés sont marqués pour Windows : sous Linux ils comptent comme **sautés**, ce que la capacité `confinement-code-non-fiable` admet pour un test propre à une plateforme ; la preuve vient du check `Verify (windows-2022)`. La branche POSIX de `PrivateLocalStorage` et le backend bubblewrap ne changent pas.

## Risks / Trade-offs

- **Un utilisateur dont `MINOS_HOME` hérite d'un refus conditionnel est bloqué** (D3-A), là où MINOS fonctionnait en effaçant la restriction. Voulu ; le message doit suffire à agir.
- **Coût** : un processus de plus par objet au premier contact ; non mesuré, à relever sur une indexation (tâche 1.1) avant de retenir D2-A.
- **Fenêtre entre lecture et écriture** : un refus conditionnel posé entre la lecture du SDDL et `setAcl` est encore effacé. Limite résiduelle, à écrire dans la Javadoc.
- **Gates littéraux** sur le backend : extraire `runIcacls` peut retirer une chaîne exigée ; relevé en 1.2, gates rejoués dans la tâche de la correction.
- **SonarCloud** : la classe d'octroi est du code neuf de `minos-runtime-local` ; ses tests de refus (délai, `SystemRoot`) doivent s'exécuter aussi sous Linux, par un lanceur injecté, sans quoi sa couverture ne vient que de Windows (l'analyse Sonar tourne sous Linux).
- **Conflit avec AUD-ARC-05** : si l'option FFM est retenue au sprint 10, la lecture par `icacls` de D2-A sera remplacée ; elle reste confinée à `WindowsDacl`.
