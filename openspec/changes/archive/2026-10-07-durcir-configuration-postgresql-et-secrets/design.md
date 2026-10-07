# Design

## Context

Motivation et périmètre : voir `proposal.md`. État du code au HEAD, relu pendant la rédaction, et
mesures faites avec le jar `postgresql-42.7.13` du dépôt Maven local (JDK 24, Windows) :

- **B07.** `PostgresJdbcUrlPolicy.queryParameters` fait
  `URLDecoder.decode(rawKey, UTF_8).toLowerCase(Locale.ROOT)` sur le nom du paramètre, puis
  `validateJdbcHostPolicy` exige `verify-full` pour un hôte externe. Mesure sur
  `Driver.parseURL` puis `SslMode.of` : `sslmode=verify-full` donne `VERIFY_FULL`,
  `sslmode=VERIFY-FULL` donne `VERIFY_FULL` (la **valeur** est insensible à la casse côté pilote, la
  politique reste donc exacte sur ce point), `sslmode=verify%2Dfull` donne `VERIFY_FULL` (le pilote
  décode la valeur), mais `SSLMODE=verify-full` et `ssl%6Dode=verify-full` donnent `sslmode=null` et
  `PREFER`. Le différentiel porte donc uniquement sur le **nom** du paramètre.
  `PostgresConnectionFactory.openRawConnection` passe l'URL tel quel à `DriverManager`.
  Les gates `check-post-mne.py` imposent dans ce fichier les littéraux
  `ALLOWED_URL_PARAMETERS = Set.of("sslmode")`, `"verify-full"` et `duplicate parameter`.
- **B07, jumeau.** `scripts/install/configure-runtime-settings.ps1`, fonction
  `Assert-ExternalPostgresUrl`, refait la même validation et fait
  `[Uri]::UnescapeDataString($Parts[0].Replace('+', ' ')).ToLowerInvariant()` sur la clé : même
  différentiel (la fiche d'audit ne le mentionne pas). `check-post-mne.py` y impose
  `Assert-ExternalPostgresUrl`, `sslmode=verify-full`, `PostgresUrl contains unsupported parameter`
  et `PostgresUrl contains duplicate parameter`.
- **B13.** `java.net.URI.getHost()` renvoie `[::1]` avec crochets (mesuré) ; `loopbackHost`
  compare à `::1` et `0:0:0:0:0:0:0:1`. `StorageBackendConfiguration.safePostgresUrl` ajoute
  `[` `]` autour d'un hôte contenant `:`, donc `[[::1]]`. L'installateur, qui lit
  `Uri.DnsSafeHost` (sans crochets), n'est pas concerné.
- **B08.** `BoundedProperties.strictUtf8Reader` décode en UTF-8 strict sans retirer U+FEFF
  (mesuré : `Properties.load` produit la clé `﻿minos.storage.backend`, `getProperty` renvoie
  `null`). `MinosRuntimeSettings.secret` applique `trim()` qui ne retire pas U+FEFF. Lecteurs de
  `load` et `readUtf8` : `MinosRuntimeSettings`, `McpBackendConfigurationStore`,
  `ProjectInspectionService`, `FileProgramGraphProvider`, `JavaSecurityRules`,
  `JGitRemoteRepositoryMaterializer`, `FileIndexStateStore`, `LocalProjectRegistry`,
  `ProjectPathMappingStore` ; `loadUtf8` : `DistributedArtifactBundleStore` (manifeste ZIP produit
  par MINOS). Le script d'installation écrit sans BOM (`UTF8Encoding($false)`) : le BOM vient des
  fichiers édités à la main. Seul `RuntimeObservationEnvelopeCodec` refuse déjà un BOM.
- **B09.** `DurableAtomicFile.publish` appelle `move(..., replaceExisting=false, ...)` ;
  `atomicMove` n'utilise alors que `StandardCopyOption.ATOMIC_MOVE`, dont le JDK ne spécifie pas
  le comportement sur cible existante (mesuré sous Windows : la cible est remplacée ; `rename(2)`
  remplace aussi sous Linux). `Files.createLink` sur une cible existante lève
  `FileAlreadyExistsException` (mesuré sous Windows). Appelants de `publish` :
  `FileHostedControlPlaneStore` (garde `Files.exists` sous verrou, `finally` qui supprime le
  temporaire) et `FileProjectFingerprintSnapshotStore.publishLocked`. `DurableAtomicFile` est une
  primitive d'E/S autorisée par `check-private-io.py` ; `check-hosted-control-plane-consistency.py`
  impose les littéraux `DurableAtomicFile.replace` et `DurableAtomicFile.publish` dans les
  appelants. La couture d'essai `Platform(mover, windows, pause)` et le test
  `aReplacementIsNeverRetriedOutsideWindowsNorForAFinalFailureNorForAPublication` supposent que la
  publication passe par `mover`.

## Goals / Non-Goals

**Goals :**

- Supprimer tout différentiel d'analyse entre la politique d'URL et le pilote, et le prouver par un
  test de parité contre le vrai pilote.
- Ne jamais perdre ni altérer en silence une propriété ou un secret à cause d'un BOM.
- Reconnaître le bouclage IPv6 littéral, avec un diagnostic correct.
- Rendre `publish` exclusif, de façon identique sous Windows et Linux.

**Non-Goals :**

- Changer le contrat de configuration (l'URL continue de porter `sslmode`), le message
  « external PostgreSQL requires sslmode=verify-full » ou les codes de sortie.
- Toucher aux enveloppes produites par MINOS (`loadUtf8`), au mode des fichiers de secret, au
  `search_path`, ni à l'atomicité de `writePrivateFile`.

## Decisions

### D1. B07 : comparer le nom brut, prouver la parité

Dans `queryParameters`, la clé n'est plus décodée ni mise en minuscules : elle est comparée telle
quelle à `ALLOWED_URL_PARAMETERS` (`Set.of("sslmode")`, littéral conservé). Toute autre clé, y
compris `SSLMODE` ou `ssl%6Dode`, échoue avec « contains unsupported parameter ». La valeur reste
décodée (le pilote la décode aussi) et comparée sans tenir compte de la casse. Le doublon exact
reste « duplicate parameter ». Les tests existants de `PostgresConnectionFactoryTest` (dont
`rejectsDuplicateCaseVariantsAndInvalidQueryEncoding`, qui attend une `IOException` pour
`sslmode=…&SSLMODE=…`) restent verts : le refus change de motif, pas de nature.

Un test de parité (classe à créer, nom indicatif `PostgresJdbcUrlPolicyDriverParityTest`, dans
`minos-storage-postgresql`, qui dépend déjà de pgjdbc) soumet un ensemble d'URL à la politique puis,
pour ceux qui sont acceptés, à `org.postgresql.Driver.parseURL` et `org.postgresql.jdbc.SslMode.of`,
et exige l'égalité du mode. C'est la garde contre la récidive : le test casse si une future
version du pilote change son analyse.

- *Alternative écartée* : faire passer `sslmode` par les `Properties` plutôt que par l'URL. Le
  contrat de configuration, l'installateur, les scripts Docker et les gates qui imposent
  `sslmode=verify-full` dans l'URL changeraient. Une variante de défense en profondeur
  (fournir `sslmode=verify-full` en valeur par défaut des `Properties` pour un hôte externe, que
  l'URL pourrait seulement confirmer) est en Questions ouvertes.
- *Installateur* : `Assert-ExternalPostgresUrl` compare la clé brute (`-ceq 'sslmode'`) sans
  `UnescapeDataString` ni `ToLowerInvariant`, en conservant les messages littéraux assertés par la
  gate. Vérification manuelle sous Windows (le script refuse de s'exécuter ailleurs : garde
  `$env:OS -ne 'Windows_NT'`) ; aucun test automatique n'existe pour ce script.

### D2. B13 : retirer une seule paire de crochets, des deux côtés

`PostgresJdbcUrlPolicy` normalise l'hôte (retrait d'une paire de crochets quand le premier et le
dernier caractère sont `[` et `]`) avant `loopbackHost`, qui reconnaît `localhost`, `::1`,
`0:0:0:0:0:0:0:1` et le littéral IPv4 `127.x.y.z` (inchangé). `safePostgresUrl` n'ajoute des crochets
que si l'hôte n'en porte pas déjà. Toute autre forme (`[::ffff:127.0.0.1]`, `[0::1]`, identifiant de
zone) reste **externe**, donc `verify-full` exigé : fail-closed. Les deux fonctions sont des
one-liners dans deux modules ; elles ne sont pas mutualisées (un helper public dans `minos-engine`
pour deux lignes serait plus coûteux), chacune a son test.

### D3. B08 : retirer un BOM, refuser un BOM répété, dans la primitive

Dans `BoundedProperties` (primitive d'E/S, hors `check-private-io.py`), après le décodage strict,
le lecteur ignore un premier caractère U+FEFF ; si le caractère suivant est encore U+FEFF, il
lève une `IOException` explicite. Cela couvre `load(Path, …)` et les deux `readUtf8`, donc aussi
`MinosRuntimeSettings.secret` (lecture absolue et relative confinée) sans toucher à `trim()`.
Le plafond d'octets s'applique en amont du décodage : les octets du BOM comptent, comportement
inchangé. Pas de nouvelle E/S brute. `loadUtf8(byte[], …)` (manifeste de bundle produit par MINOS)
n'est pas modifié (question ouverte 3).

- *Alternative* : refuser tout BOM, comme `RuntimeObservationEnvelopeCodec`. Plus strict, mais
  casserait les utilisateurs de Windows PowerShell 5.1 que ce changement veut servir ; question
  ouverte 3.
- *UTF-16* : un fichier UTF-16 n'est pas du UTF-8 valide et reste refusé par le décodeur strict
  (inchangé).

### D4. B09 : publication exclusive par lien physique, avec couture d'essai

`DurableAtomicFile.move` pour `replaceExisting=false` ne passe plus par `ATOMIC_MOVE` : après
`forceFile(source)`, il crée un lien physique `Files.createLink(cible, source)` (échec
`FileAlreadyExistsException` si la cible existe, sur les deux plateformes, et atomique côté
système de fichiers), supprime la source (nettoyage au mieux : la publication est déjà acquise et les
appelants suppriment déjà leur fichier temporaire en `finally`), puis force le répertoire parent
(`CommitUncertainException` inchangée). Si le système de fichiers ne supporte pas les liens
physiques (`UnsupportedOperationException` ou `FileSystemException` autre que « existe déjà »), la
publication échoue avec un message explicite (« filesystem does not support required exclusive
publication »), **sans repli qui écraserait**. `replace` (avec `REPLACE_EXISTING`, retry Windows)
est inchangé. `Platform` gagne une couture `linker` (constructeur secondaire à trois arguments
conservé, valeur par défaut `Files::createLink`) ; le test
`aReplacementIsNeverRetriedOutsideWindowsNorForAFinalFailureNorForAPublication` est adapté pour que
sa troisième assertion (refus final d'une publication) passe par cette couture.

- *Alternatives écartées* : `Files.move` sans `ATOMIC_MOVE` (atomique sous NTFS, mais sous Linux le
  JDK teste l'existence puis fait `rename` : fenêtre de course, donc non exclusif) ; garde
  `Files.exists` avec verrou documenté en précondition (ne protège pas un futur appelant sans
  verrou, qui est justement le risque décrit) ; création `CREATE_NEW` puis copie (le fichier est
  visible avant d'être complet).
- *Repli* si les liens physiques sont indisponibles : question ouverte 1.

### D5. Qualification des capacités

| Capacité | Qualification | Justification |
|---|---|---|
| Politique TLS d'URL externe (nom brut, parité avec pgjdbc 42.7.13) | **Qualifiée** | test de parité contre le vrai pilote, Windows et Linux |
| Même validation dans l'installateur Windows | **Partielle** | alignée mais sans test automatique ; vérification manuelle sous Windows seulement |
| Bouclage `[::1]` et `[0:0:0:0:0:0:0:1]` | **Qualifiée** | tests unitaires |
| Autres formes IPv6 de bouclage (mappée IPv4, zone, abrégée) | **Non supportée** | traitées comme externes, `verify-full` exigé |
| Retrait d'un BOM UTF-8 initial (configuration et secrets) | **Qualifiée** | tests sur `BoundedProperties` et secrets absolus et relatifs |
| BOM dans les enveloppes produites par MINOS | **Non supportée** | inchangé, hors lot |
| Publication exclusive sur NTFS, ext4, xfs, tmpfs | **Qualifiée** | tests réels sous Windows et Linux (CI) |
| Publication exclusive sur un système de fichiers sans lien physique | **Non supportée** | refus explicite, sans repli |
| Publication exclusive sur partage réseau (SMB, NFS) | **Non qualifiée** | comportement dépendant du serveur ; refus explicite si le lien échoue |

### D6. Windows et Linux

- **B07, B13** : logique pure de chaîne, identique ; la parité s'exécute sur le jar du pilote, donc
  sur les deux CI. L'installateur est Windows uniquement.
- **B08** : l'origine est Windows (PowerShell 5.1, Bloc-notes) ; les tests écrivent les octets
  `EF BB BF` explicitement, jamais via le jeu de caractères de l'hôte, et incluent des fins de ligne
  CRLF et LF.
- **B09** : lien physique sur le même volume (le temporaire est créé dans le répertoire de la cible).
  NTFS et ReFS le supportent, FAT et exFAT non (refus explicite) ; ext4, xfs, tmpfs, overlayfs oui.
  La source supprimée après lien ne change pas l'ACL ni les permissions privées (le lien partage
  l'inode ou le descripteur de sécurité). Les tests utilisent un `@TempDir` réel, sans
  `@EnabledOnOs`.
- **Gates** : `check-private-io.py` (les deux fichiers modifiés sont des primitives
  autorisées ; aucune E/S brute ailleurs) ; `check-post-mne.py` et
  `check-audit-remediation-v2.py` (littéraux listés au Contexte à conserver) ;
  `check-hosted-control-plane-consistency.py`. Aucun code n'est renommé ni déplacé.

Direction des dépendances (ADR-0022) : `minos-storage-postgresql` (adaptateur) reste sur
`minos-engine` ; `minos-engine` (primitives et configuration) ne dépend de rien de nouveau ;
aucune exception.

## Risks / Trade-offs

- [Un opérateur dont l'URL porte `SSLMODE=` ou un nom encodé est refusé après la mise à jour] →
  c'est le but (le pilote ignorait cette clé) ; le message nomme la clé, la documentation
  utilisateur le dit.
- [Une publication refusée sur un système de fichiers sans lien physique, où elle réussissait en
  écrasant] → refus explicite, jamais d'écrasement ; seuls `FileHostedControlPlaneStore.create` et
  `FileProjectFingerprintSnapshotStore` publient, sous `MINOS_HOME` ; question ouverte 1 si ce
  support doit être conservé.
- [Un BOM tronqué par un éditeur exotique] → hors périmètre : un octet UTF-8 invalide est déjà
  refusé.
- [Le test de parité devient fragile à une montée de version du pilote] → c'est sa fonction :
  il doit échouer pour imposer une relecture de la politique.
- [Couture d'essai de `Platform` modifiée] → constructeur à trois arguments conservé ; seul le test
  de publication change.
- [Scopes JaCoCo] → `m30-postgresql-pgvector` (préfixe du paquet entier), 
  `m30-storage-backend-selection` (`MinosRuntimeSettings`, `StorageBackendConfiguration`),
  `persistence-cache-indexes` : le code ajouté est couvert par les tests de la même tâche.

## Migration Plan

Aucune migration de données. Un `minos.properties` ou un fichier de secret avec BOM devient
lisible ; un URL à clé `SSLMODE` devient refusé (corriger la casse). Retour arrière : restaurer
`move` avec `ATOMIC_MOVE`, `queryParameters` avec décodage, `strictUtf8Reader` sans retrait ;
aucun format persistant n'est touché.

## Open Questions

1. **Repli de B09 sans lien physique.** Défaut retenu : refus explicite. Si l'utilisateur veut
   conserver le support de systèmes de fichiers sans lien physique, un repli sur « test
   d'existence sous verrou documenté » est possible mais ré-ouvre le défaut pour ces seuls
   systèmes. Si le support est retiré, une note d'ADR est à proposer (statut Proposed). Décision de
   support : à confirmer avant la tâche 4.1 ; elle ne change pas le découpage des tâches.
2. **Défense en profondeur de B07** (optionnelle dans l'audit) : fournir `sslmode=verify-full` comme
   valeur par défaut des `Properties` pour un hôte externe. Hors des tâches bloquantes.
3. **BOM.** Retirer un BOM (retenu) ou le refuser comme `RuntimeObservationEnvelopeCodec` ; et
   étendre ou non le comportement aux enveloppes machine (`loadUtf8`). Retenu : retirer, une fois,
   pour les fichiers lus par chemin et par flux ; `loadUtf8` inchangé.
4. **Installateur : lecture du secret source** (`Read-BoundedUtf8` puis `.Trim()`) : un BOM en tête
   n'est probablement pas retiré. Non vérifié sous Windows PowerShell 5.1 ni PowerShell 7 ; à
   instruire séparément (la gate `check-post-mne.py` y impose des littéraux).

## Écarts constatés à l'implémentation (2026-10-07)

Aucun ne change les exigences ; ils précisent D1 à D5. Le détail et les preuves sont dans la section « Évidence d'implémentation » de `tasks.md`.

- **D1, installateur : un défaut de plus que la fiche.** Aligner la casse ne suffisait pas : `Assert-ExternalPostgresUrl` lisait la requête dans `$Uri.Query`, or `System.Uri` normalise la requête et décode un caractère non réservé inutilement encodé (`%6D` devient `m`). `ssl%6Dode=verify-full` arrivait donc au script sous la forme `sslmode=verify-full` et était **accepté**, même sans toucher à la casse. La requête est désormais lue dans l'URL brute (`$JdbcUrl`, après le premier `?`), le nom est gardé tel quel et la comparaison est faite par `-cne` : la table de hachage et `-notin` de PowerShell sont insensibles à la casse par défaut. Les messages littéraux assertés par `check-post-mne.py` sont conservés.
- **D1, test de parité.** `PostgresJdbcUrlPolicyDriverParityTest` compte ses échantillons (au moins trois URL acceptés, trois dont la clé est ignorée par le pilote, deux refusés au bouclage) pour qu'un échantillon vidé ne le fasse pas passer à vide ; `SslMode.of` déclare une `PSQLException` contrôlée, d'où les `throws Exception`.
- **D2.** `PostgresJdbcUrlPolicy.unbracketed` retire une seule paire de crochets avant `loopbackHost` ; les formes `[::ffff:127.0.0.1]`, `[0::1]` et `[::2]` restent externes (testées, politique gérée comprise). `safePostgresUrl` n'ajoute des crochets que si l'hôte ne commence pas par `[`.
- **D3.** Le retrait est un `PushbackReader` posé sur le décodeur strict, avec un helper `skipLeadingByteOrderMark` appelé dans le `try`, après l'ouverture des ressources : une erreur de BOM répété ne laisse aucun flux ouvert. `loadUtf8` garde sa lecture historique (un BOM y reste une donnée, testé).
- **D4, erreurs.** `FileAlreadyExistsException` est relancée telle quelle (l'erreur « existe déjà » de la spec). `UnsupportedOperationException` et une `FileSystemException` générique (FAT, exFAT, certains partages) deviennent `filesystem does not support required exclusive <label>: <cible>` ; `NoSuchFileException` et `AccessDeniedException` restent spécifiques, car elles disent la vraie cause. Sur refus, la source est conservée pour que l'appelant la nettoie ; sur succès, son nom est supprimé au mieux. `atomicMove` ne porte plus de paramètre `replaceExisting` : seule la publication évite le déplacement.
- **D4, tests.** Sous Windows, le test « cible absente » est **vert au HEAD** (un déplacement vers une cible absente réussissait déjà) : ce n'est pas un test rouge, contrairement à ce qu'annonçait la tâche ; seuls « cible existante » et « publications concurrentes » le sont. Le test existant `aReplacementIsNeverRetriedOutsideWindowsNorForAFinalFailureNorForAPublication` passe pour sa troisième assertion par la couture `linker`.
- **Limite de preuve.** Tout a été exécuté sous Windows. Le lien physique sous Linux n'a pas tourné ; la CI Ubuntu le prouvera.
