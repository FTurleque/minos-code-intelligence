# Prompt — S5, S6, S7, S8, S9, S12 et S15 : l'axe Sécurité, sévérité moyenne (à coller dans une session Claude Code à la racine du dépôt)

---

Tu es l'orchestrateur du chantier **Sécurité, sévérité moyenne** de l'audit `docs/audit/AUDIT-2026-09.md` sur `minos-code-intelligence`. Sept constats, tous ouverts :

| ID | Statut | En une ligne |
|---|---|---|
| S9 | CONFIRMÉ | le nom de la variable d'environnement de credential est choisi par l'utilisateur : **n'importe quelle variable peut partir en basic-auth** vers github ou gitlab ; plus cinq autres défauts de l'intégration Git |
| S12 | CONFIRMÉ | la réserve d'audit compte **tous** les événements : un tenant bavard cesse de chaîner ses refus, y compris le premier refus d'une attaque réelle |
| S5 | CONFIRMÉ | une dizaine de sites créent des fichiers avec les permissions par défaut ou les ouvrent en suivant les liens, dont un TOCTOU |
| S6 | CONFIRMÉ | deux `channel.lock()` sans délai, dont un sans verrou JVM |
| S8 | CONFIRMÉ / PLAUSIBLE | un `.gitignore` hostile casse le chargement (`[z-a]`) ou le ralentit (ReDoS) ; seul le `.gitignore` racine est lu |
| S7 | PLAUSIBLE | Windows : environnement parent hérité par les launchers, DACL non protégée, `forceDirectory` inerte |
| S15 | CONFIRMÉ | Windows : MINOS **supprime l'ACE de refus d'écriture** posée sur `MINOS_HOME`, et `doctor` y dépose un script PowerShell exécutable |

**L'ordre des lots est celui du risque, pas celui des identifiants.** S9 et S12 sont les deux seuls qui donnent quelque chose à un attaquant aujourd'hui : l'un exfiltre un secret vers un hôte distant, l'autre éteint la détection. Ils passent en premier, même si S5 est la fondation.

**Attention, les chemins cités par l'audit sont datés.** A2, A3, A4 et les chantiers Code, Fiabilité et CLI ont déplacé et renommé des paquets entiers : `minos-storage-local` vit sous `com.minos.storage.local.*`, `minos-runtime-local` sous `com.minos.runtime.local`, le câblage sous `minos-bootstrap`. **Relocalise chaque cible avant d'y toucher** et corrige la référence dans ton suivi.

**Deux recoupements à connaître avant de commencer**, pour ne pas refaire ou défaire le travail des sprints précédents :

- **S6 recoupe R9.** `LocalStorageRetentionService.compact` est exactement le verrou `retention-locks/<projet>.lock` que l'audit décrit sous R9 : `FileChannel.lock()` sans délai, sans couche JVM, sans `NOFOLLOW` ni droits privés. Le lot 3 ferme donc R9 en même temps que S6 — dis-le dans la PR.
- **S15 recoupe R12.** Le constat R12 dit qu'une commande de lecture ne peut pas tourner sur un `MINOS_HOME` en lecture seule ; S15 dit que MINOS supprime l'ACE qui le rendrait tel. La décision que tu écris pour S15 (« MINOS ne retire jamais une restriction posée par l'administrateur ») rend R12 **observable** sous Windows pour la première fois. Ne cherche pas à fermer R12 ici, mais note ce que ta décision change pour lui.

Tu travailles avec **un agent d'implémentation** et **un agent de supervision qui tourne en parallèle du début à la fin**.

| Agent | Rôle |
|---|---|
| `impl-sec` | implémente les lots dans l'ordre, par commits réversibles |
| `verif-sec` | supervision continue : bug nouveau, régression, convention non respectée, duplication, durcissement affaibli. N'écrit pas de code de production |

`verif-sec` démarre **en même temps** qu'`impl-sec`. Un constat bloquant renvoie l'implémenteur au travail avant qu'il n'avance.

## Règles non négociables

1. **Un correctif de sécurité se prouve par l'attaque, pas par l'intention.** Pour chaque constat, le test rouge **exerce le défaut** : il envoie la variable qui ne devrait pas partir, il fait suivre le lien symbolique, il fabrique le `.gitignore` hostile, il noie la réserve d'audit. Un test qui vérifie seulement que le nouveau code est appelé ne prouve rien.
2. **Aucun durcissement existant ne peut être affaibli pour faire passer un test**, et c'est le piège de ce chantier : ces sept constats vivent dans le code qui protège le reste. Si un correctif casse un test de durcissement, c'est le correctif qui est faux.
3. **S7 est PLAUSIBLE : prouve avant de corriger.** Écris le test qui montre qu'une ACE héritée réapparaît, qu'une variable sensible atteint le launcher, que `forceDirectory` ne fait rien. **Si le scénario n'est pas reproductible, ne corrige pas** : écris dans `docs/audit/SEC-SUIVI.md` pourquoi le constat ne tient pas, avec la preuve, et passe. Même règle pour la part PLAUSIBLE de S8 (le ReDoS).
4. **Aucun chemin absolu, aucun secret, aucun fragment de fichier lu dans un message, un journal ou une exception.** C'est une règle du dépôt, et ce chantier est précisément celui qui ne peut pas se permettre de l'enfreindre. `verif-sec` la vérifie à chaque commit.
5. **Le comportement par défaut est fermé.** Un verrou qui expire, une ACL qu'on ne sait pas lire, un `.gitignore` qu'on ne sait pas compiler, une variable dont on ne sait pas si elle est un credential : on refuse, on dit pourquoi, on ne devine pas.
6. **Builds locaux ciblés** pendant le travail ; un `clean verify` complet par lot avant d'ouvrir sa PR. Les lots 4 et 5 sont sensibles à la plateforme : dis explicitement ce qui a été vérifié sous Windows, sous Linux, et ce qui ne l'a pas été.
7. **Branche et worktree par lot**, lots **séquentiels**, chacun rebasé sur le précédent. **Test rouge avant correctif**, preuve jointe au commit.
8. **Pas d'élargissement** : ce qui sort du périmètre part dans `docs/audit/SEC-SUIVI.md`, section « à traiter plus tard ». S10, S11, S13 et S14 ne sont **pas** dans ce chantier.
9. **Consigne le tout dans `docs/audit/SEC-SUIVI.md`** : inventaire daté, décisions, journal par commit, preuves, résultats de `clean verify` et des gates à chaque fin de lot, tableau des constats de `verif-sec`, et **numéros de PR**. Le chantier précédent a laissé ces preuves dans les PR seulement : ne refais pas ça.
10. **Réponds en français.**

---

## Lot 1 — `sec/s9-git` : un secret ne part pas vers un hôte distant (S9)

Six défauts, d'inégale gravité. Le premier est le seul qui soit une fuite :

1. **le nom de la variable d'environnement de credential est choisi par l'utilisateur** : MINOS lit la variable nommée dans la configuration et l'envoie en basic-auth à l'hôte distant. Une configuration (ou un dépôt partagé qui la porte) peut donc faire partir `AWS_SECRET_ACCESS_KEY` ou un jeton d'API vers github, gitlab ou n'importe quel hôte ;
2. `FileRepositoryBuilder.readEnvironment()` respecte `GIT_DIR` : le dépôt réellement ouvert peut ne pas être celui demandé ;
3. `deleteCacheTree` réimplémente la suppression sans protection contre les jonctions ;
4. une URL distante avec slash final produit `…/r/.git` ;
5. l'historique s'arrête au premier commit plus ancien que `since`, alors que les dates de commit ne sont pas monotones ;
6. le clone est *shallow* (profondeur 1) et exige `expectedCommit == HEAD` : un commit qui n'est pas la pointe de branche est inindexable.

**Attendu** : (1) le nom de la variable n'est plus libre — liste blanche explicite, ou nom fixe, ou les deux ; et la valeur ne part **jamais** vers un hôte que la configuration n'a pas nommé. (2) l'environnement Git n'est plus lu : le dépôt ouvert est celui qu'on a demandé, et rien d'autre. (3) la suppression passe par la primitive du dépôt qui ne suit pas les liens ni les jonctions — pas une seconde implémentation. (4) l'URL est normalisée à un seul endroit. (5) la troncature d'historique ne repose plus sur la monotonie des dates.

**Le point (6) n'est pas un défaut de sécurité, c'est une limite fonctionnelle.** Ne l'élargis pas en refonte du clone : soit tu le corriges proprement si c'est contenu, soit tu le nommes dans « à traiter plus tard » avec ce qu'il coûterait. Dis lequel, et pourquoi.

**Points de vigilance** : restreindre le nom de la variable **casse les configurations existantes** qui en utilisent un autre. Décide et écris : refus au démarrage avec un message qui dit quoi faire, ou période de tolérance avec avertissement ? Je penche pour le refus — c'est un credential — mais c'est à toi de l'argumenter. Et le test de (1) doit montrer qu'**avant**, une variable arbitraire partait : monte un serveur HTTP local qui capture l'en-tête `Authorization`, et vérifie qu'après le correctif rien n'y arrive. Ne mets jamais une vraie valeur de secret dans un test ni dans un journal.

---

## Lot 2 — `sec/s12-audit` : le premier refus d'une attaque est toujours chaîné (S12)

La condition de chaînage d'un refus est `auditEvents().size() < deniedAuditCapacity()` : elle porte sur la taille **totale** de la chaîne. Un tenant qui accumule 90 % d'événements autorisés légitimes — la rétention n'évince rien implicitement — cesse de chaîner **tout** refus. Le premier refus d'une attaque réelle se réduit alors à un WARNING, et le correctif de S2 ne protège plus rien.

**Attendu** : la réserve destinée aux refus est comptée sur les événements `DENIED`, jamais sur le total ; un tenant bavard ne peut pas épuiser la capacité de chaînage des refus ; un test montre qu'**avant**, un refus arrivait non chaîné après N événements autorisés, et qu'après il est chaîné.

**Points de vigilance** : c'est un correctif court mais il touche la chaîne d'audit, donc l'invariant que S2 a établi. Vérifie que la capacité reste bornée dans l'autre sens : un attaquant qui produit uniquement des refus ne doit pas pouvoir faire grossir la chaîne sans fin — c'était l'objet de S2, et le fermer ne doit pas le rouvrir. Dis explicitement ce qui arrive quand la réserve de refus est, elle, épuisée. Et regarde S13 pendant que tu es là (un événement non chaîné est indiscernable d'un événement chaîné) : il n'est **pas** dans ce chantier, mais si ton correctif le rend trivial, dis-le dans la PR plutôt que de l'embarquer en silence.

---

## Lot 3 — `sec/s5-s6-primitives` : tout passe par les primitives, et un gate le garde (S5, S6)

**S5** — une dizaine de sites créent des répertoires et des fichiers avec les permissions par défaut, ou les ouvrent sans `NOFOLLOW_LINKS` : le magasin de paquets d'artefacts distribués, le worker isolé, la préparation du répertoire de run, la sortie bornée de processus, la rétention des répertoires de run, le registre de baux de cache partagé, le magasin du plan de contrôle hébergé, la compaction de rétention, le verrou de matérialisation Git, le registre de projets, la lecture d'un secret absolu. Plus un **TOCTOU** : `RuntimeObservationEnvelopeCodec.read` contrôle le fichier avec `NOFOLLOW` puis le rouvre avec un flux qui suit les liens.

**S6** — `FileHostedControlPlaneStore.lock` et `LocalStorageRetentionService.compact` appellent `channel.lock()` sans délai, alors que le reste du code borne à 10 s ; `compact` n'a pas de verrou JVM, donc deux threads qui compactent lèvent `OverlappingFileLockException`.

**Attendu** : **toute** création ou ouverture de fichier sous `MINOS_HOME` passe par `PrivateLocalStorage` / `ConfinedFileOpener` ; le TOCTOU est fermé en ouvrant **une fois** et en travaillant sur le descripteur ouvert, pas en recontrôlant ; les deux verrous sont bornés comme les autres et `compact` a sa couche JVM.

**Le livrable central du lot est le gate, pas les dix correctifs.** Une règle — ArchUnit, ou un contrôle de source dans `scripts/architecture/` à côté de `check-module-boundaries.py` — qui **interdit** `Files.createDirectories`, `Files.write`, `Files.newInputStream`, `FileChannel.open` et `channel.lock()` hors des primitives, avec son auto-test et sa mutation témoin. Sans ce gate, le onzième site apparaîtra au prochain sprint. Les exceptions légitimes (le code qui crée `MINOS_HOME` lui-même, les tests) sont une **liste blanche nominative et justifiée**, pas un filtre large sur un répertoire.

**Points de vigilance** : borner un verrou **change le comportement** — dis ce qui arrive à l'expiration (échec fermé, message sans chemin, code de sortie), et vérifie qu'aucun chemin chaud ne se met à échouer sous charge normale. Attention à ne pas ré-créer une primitive : il en existe déjà (`PrivateLocalStorage`, `ConfinedFileOpener`, `DurableAtomicFile`) ; en ajouter une quatrième qui fait la même chose est le bug que ce lot est censé supprimer. Et le TOCTOU de `RuntimeObservationEnvelopeCodec` ne se corrige pas en répétant le contrôle : s'il reste deux ouvertures, le défaut reste.

---

## Lot 4 — `sec/s8-gitignore` : un dépôt hostile ne casse pas le chargement (S8)

- `ProjectIgnoreRules` recopie les classes de caractères telles quelles dans une regex : `[z-a]` lève une `PatternSyntaxException` non vérifiée qui fait échouer **tout** le chargement (CONFIRMÉ).
- Des `**/` imbriqués produisent des `(?:.*/)?` répétés, donc un ReDoS possible depuis un dépôt distant (PLAUSIBLE).
- Seul le `.gitignore` racine est lu : ni les `.gitignore` imbriqués, ni `.git/info/exclude`.

**Attendu** : un motif qui ne compile pas est **écarté avec un avertissement compté**, et n'empêche jamais le chargement des autres ; le coût de l'évaluation est borné, quel que soit le motif ; les `.gitignore` imbriqués et `.git/info/exclude` sont pris en compte, ou leur absence est documentée comme un écart assumé à la sémantique de Git.

**Points de vigilance** :

- **le test de ReDoS ne doit pas être un test d'horloge.** Une assertion « moins de 2 secondes » est instable par construction et sera la première à rougir en CI chargée. Borne le **mécanisme** : refuse à la compilation au-delà d'une complexité mesurable (nombre de `**`, longueur du motif), ou compte les pas d'évaluation. Un test déterministe sur la borne vaut mieux qu'un chronomètre ;
- écarter un motif change la liste des fichiers indexés : **mesure avant/après** sur ce dépôt et joins le nombre de fichiers gagnés et perdus. Un gain silencieux qui change le périmètre d'indexation n'en est pas un ;
- lire les `.gitignore` imbriqués est un **élargissement fonctionnel**, pas un correctif de sécurité. Si tu le fais, c'est avec la sémantique de Git (précédence, négation `!`, portée du répertoire) et des tests qui la prouvent ; sinon, documente l'écart. Ne livre pas une demi-sémantique.

---

## Lot 5 — `sec/s7-s15-windows` : MINOS ne desserre pas ses propres protections (S7, S15)

**S15 (CONFIRMÉ)** — sous Windows, la première commande qui ouvre l'application **supprime l'ACE de refus d'écriture** posée sur `MINOS_HOME` : une restriction explicite de l'administrateur est annulée en silence. Et `doctor` matérialise `sandbox/windows-appcontainer-sandbox-v4.ps1`, un script PowerShell **exécutable**, dans ce même répertoire.

**S7 (PLAUSIBLE, à prouver d'abord)** — les launchers PowerShell héritent de **tout** l'environnement parent ; `PrivateLocalStorage` pose une seule ACE « propriétaire » sans protéger la DACL, donc des ACE héritées peuvent réapparaître (et SYSTEM et Administrateurs sont retirés) ; `requireInheritableOwnerAccess` s'appuie sur `user.name` ; `DurableAtomicFile.forceDirectory` ne fait rien sous Windows.

**Écris la décision avant de coder**, dans `docs/audit/SEC-SUIVI.md`. Ma position, que tu peux contredire avec un argument :

- **MINOS ne retire jamais une restriction qu'un administrateur a posée.** S'il ne peut pas écrire là où il doit écrire, il échoue en le disant. Supprimer une ACE de refus est une élévation de privilège silencieuse, quel que soit le bénéfice ergonomique ;
- **un script exécutable n'a pas sa place dans un répertoire de données.** Le script du bac à sable est un artefact du produit : il appartient à la distribution (c'est précisément ce que l'ADR 0040 a établi avec le zip auto-portant), pas à `MINOS_HOME`. S'il doit absolument être matérialisé à l'exécution, c'est hors de `MINOS_HOME`, en lecture seule, et avec une empreinte vérifiée ;
- **la DACL est protégée** (l'héritage ne peut pas réintroduire d'ACE), et le retrait de SYSTEM et Administrateurs est soit justifié par écrit, soit annulé ;
- **l'environnement passé aux launchers est une liste blanche**, pas l'héritage complet.

**Points de vigilance — c'est le lot le plus difficile à vérifier** :

- ce code **protège le bac à sable des workers**. Resserrer une ACL ou filtrer l'environnement peut empêcher le bac à sable de démarrer, et un bac à sable qui ne démarre pas est un repli vers moins d'isolation, c'est-à-dire une régression de sécurité déguisée en correctif. Vérifie que la qualification du bac à sable passe toujours, sous Windows, après chaque changement ;
- **tout est spécifique à Windows** : dis précisément ce qui a été exécuté sur une vraie machine Windows, ce qui l'a été sous WSL, et ce qui ne l'a été nulle part. Un test qui s'ignore (`assumeTrue`) sur la CI est un test qui ne garde rien : compte-les et dis-le ;
- **ne corrige pas le `.ps1` en le réécrivant** : les scripts PowerShell du bac à sable n'ont jamais été relus (c'est écrit dans l'audit sous S7). Les déplacer est dans le périmètre ; les réécrire ne l'est pas.

---

## Consigne pour `verif-sec` (supervision continue, en parallèle)

> Tu inspectes après chaque commit annoncé, et au moins toutes les dix minutes, avec `git diff` et `git log -p`. Tu n'écris pas de code de production. **Vérifie chaque constat contre le fichier réel du dépôt avant de le déclarer** — un diff lu sur une copie périmée produit de faux bloquants, c'est arrivé deux fois sur les chantiers précédents.
>
> **Bug nouveau et régression** — dans l'ordre de gravité :
> - **un durcissement a-t-il été affaibli ?** C'est la question numéro un de ce chantier. Pour chaque test de sécurité existant que le diff touche, demande-toi si l'assertion a été **adaptée** ou **réduite**. Un test de durcissement dont une assertion disparaît est bloquant, même si la suite est verte.
> - **un secret, un chemin absolu ou un fragment de fichier lu peut-il atteindre un journal, un message ou une exception ?** Relis chaque nouveau message. Pour le lot 1, vérifie toi-même qu'aucune valeur de credential n'apparaît où que ce soit, y compris dans un message d'échec de configuration.
> - **le bac à sable démarre-t-il toujours ?** Après chaque commit du lot 5, rejoue la qualification du bac à sable sous Windows. Un repli silencieux vers un backend moins isolant est bloquant.
> - **un chemin chaud peut-il désormais échouer ?** Les verrous bornés du lot 3 et les ACL resserrées du lot 5 introduisent des échecs là où il y avait une attente ou un succès. Exerce-les sous concurrence.
> - **un `.gitignore` légitime est-il désormais écarté ?** Pour le lot 4, prends les `.gitignore` de ce dépôt et de deux dépôts publics, et compare la liste des fichiers ignorés avant et après.
> - un code de sortie modifié pour une commande hors périmètre ; un golden régénéré sans justification.
>
> **Le gate du lot 3 est à auditer comme du code de production** : rejoue-le toi-même, écris une mutation témoin par interdiction (un `Files.createDirectories` hors primitive, un `channel.lock()` hors primitive) et vérifie qu'elle sort en erreur, puis `git checkout -- .`. Vérifie surtout la **liste blanche** : une entrée trop large (un répertoire entier, un joker) vide le gate de son sens et est bloquante.
>
> **Tests** : tout nouveau test de concurrence est rejoué **50 fois** par tes soins, indépendamment de l'implémenteur, et sans `Thread.sleep` de synchronisation. **Refuse toute assertion de durée** comme preuve de ReDoS. Vérifie qu'aucun correctif PLAUSIBLE (S7, part ReDoS de S8) n'a été écrit **avant** son test rouge : `git log` doit montrer le test d'abord.
>
> **Conventions** : la convention qui fait foi est celle du module touché. Vérifie le nommage, l'emplacement port/adaptateur (A2), la racine de composition toujours dans `minos-bootstrap` (ADR 0042), le placement des classes dans le bon module (A3), un seul constructeur public par classe neuve (A4), la langue de la Javadoc sans mélange dans un même fichier, les messages passés par `PublicErrorMessages` et sans chemin absolu, `Locale.ROOT` sur toute comparaison de casse, aucun nouveau numéro de jalon dans un nom de fichier (ADR 0043), aucune I/O dans un constructeur de record (Q15).
>
> **Duplication** — le risque est précis sur ce chantier :
> - **une quatrième primitive de fichier** à côté de `PrivateLocalStorage`, `ConfinedFileOpener` et `DurableAtomicFile` : bloquant. Le but du lot 3 est qu'il y en ait moins, pas plus.
> - **un second normalisateur d'URL**, un second effaceur d'arborescence, un second compilateur de motifs d'exclusion : compte les occurrences avant et après, le nombre doit baisser.
> - **une seconde règle d'ACL** : un seul endroit décide des droits d'un fichier sous `MINOS_HOME`.
> - **un second décompte d'audit** : la règle de capacité des refus s'écrit une fois.
>
> **Après chaque commit** : rejoue les gates (`check-module-boundaries.py`, `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`, `check-workflow-pins.py`, `check-jacoco.py`, et le gate neuf du lot 3) et compare aux chiffres relevés avant le premier commit. Vérifie que les 12 golden de `characterization/` sont inchangés **à chaque commit**, pas seulement en fin de lot.
>
> Chaque constat : identifiant, fichier et ligne, ce qui casse, un scénario concret d'attaquant quand c'en est un, une sévérité (bloquant / à corriger / remarque). Tenue à jour de `docs/audit/SEC-SUIVI.md`.

---

## Fin de chantier : ouvrir les PR, puis les mener au vert

**Tu ouvres les pull requests — c'est demandé explicitement et ce n'est pas optionnel.** Une PR par lot, vers `develop`, en brouillon, dans l'ordre des lots, chacune ouverte quand son lot est complet, inspecté sans constat ouvert, et son `clean verify` vert en local. Ouvre-les l'une après l'autre, chaque branche rebasée sur la précédente une fois celle-ci verte.

Corps de chaque PR, **dans cet ordre** :

1. **ce qu'un attaquant pouvait faire avant et ne peut plus faire après**, une phrase par constat, en tête et en clair ;
2. les changements observables pour un utilisateur légitime : configurations désormais refusées (lot 1), verrous qui peuvent expirer (lot 3), motifs d'exclusion écartés et leur effet mesuré sur le périmètre d'indexation (lot 4), `MINOS_HOME` qui n'est plus desserré et script déplacé (lot 5) ;
3. la décision écrite du lot concerné, et pour le lot 5 la position retenue sur l'ACL et le script ;
4. ce qui a changé par commit ; la preuve (tests rouges→verts, gates, golden, mesures) ;
5. la répartition Windows / Linux / non exécuté, et le nombre de tests ignorés ;
6. les constats de `verif-sec` et leur résolution ;
7. ce qui est laissé de côté, et **tout constat PLAUSIBLE non reproduit avec sa preuve de non-reproduction**.

**Si la CI échoue, tu corriges.** Diagnostique, corrige sur la même branche, repousse, et recommence jusqu'au vert — trois tentatives par PR au maximum. Au-delà, tu t'arrêtes et tu me rends l'analyse plutôt que de continuer à pousser.

**Interdit pour faire passer la CI** : désactiver ou marquer `@Disabled` un test, ajouter un `assumeTrue` qui neutralise un test de durcissement, assouplir un gate ou élargir sa liste blanche, régénérer un golden sans justification écrite, allonger un délai pour masquer une course, ou retirer une assertion de sécurité. Si le vert n'est atteignable qu'à ce prix, c'est que le correctif est faux : reviens au code.

Puis rends-moi la main avec les liens des PR, l'état de leur CI, et ce qui reste ouvert. Tu ne fusionnes rien.

## Définition de terminé

- S5, S6, S8, S9, S12 et S15 corrigés, chacun avec un test qui **exerçait l'attaque** et échouait avant ; S7 corrigé ou démontré non reproductible, preuve jointe ;
- aucune variable d'environnement autre que celles d'une liste blanche explicite ne peut partir vers un hôte distant, et aucun secret n'apparaît dans un journal ou un message ;
- la réserve d'audit est comptée sur les refus, et un tenant bavard ne peut plus empêcher le chaînage du premier refus ;
- **le gate interdit `Files.createDirectories`, `Files.write`, `Files.newInputStream`, `FileChannel.open` et `channel.lock()` hors des primitives**, avec auto-test, mutation témoin et liste blanche nominative ; le TOCTOU est fermé par une ouverture unique ;
- un `.gitignore` hostile n'empêche plus le chargement, le coût d'évaluation est borné par un mécanisme et non par un chronomètre, et l'effet sur le périmètre d'indexation est mesuré ;
- MINOS ne retire plus aucune restriction posée sur `MINOS_HOME`, n'y dépose plus de script exécutable, et la qualification du bac à sable passe toujours sous Windows ;
- aucune quatrième primitive de fichier, aucun second normalisateur d'URL, aucune seconde règle d'ACL, aucun second décompte d'audit ;
- tous les gates verts, aucun test désactivé ni neutralisé, les 12 golden inchangés ou leur modification justifiée ;
- `docs/audit/SEC-SUIVI.md` à jour, **avec les résultats de `clean verify`, les chiffres des gates, le tableau des constats de `verif-sec` et les numéros de PR** ;
- **les PR sont ouvertes et vertes**, leurs liens me sont donnés.
