# ADR 0055 — Réunir les adaptateurs dans minos-storage

Date : 2026-10-05. Statut : **Accepted — décision validée par le propriétaire ; implémentation à réaliser**.

## Contexte

Le propriétaire souhaite regrouper `minos-storage-local` et `minos-storage-postgresql` dans un module Maven `minos-storage`, avec des packages Java séparés. Sur `develop` (`c9a339088f81b6b31c65c7ad12bc718be2a98a7b`), les ports sont déjà dans `minos-engine` et `minos-bootstrap` existe (ADR 0042). PostgreSQL dépend encore du module local pour les codecs. Le lanceur distribue déjà les deux backends.

L'analyse préalable de `main` (`730b760`) ne décrivait pas les corrections A2/A3 présentes sur `develop` : il ne faut pas les refaire. La fusion concerne les implémentations et leur packaging, pas les contrats du cœur.

## Décision

- Un seul artefact Maven `com.minos:minos-storage` remplace les deux modules.
- Les implémentations conservent `com.minos.storage.local.*` et `com.minos.storage.postgresql.*`. Les codecs réellement partagés rejoignent `com.minos.storage.codec` dans ce même artefact.
- Ne pas déclarer de classes de production dans `com.minos.storage` : ce package appartient déjà à `minos-engine` (ADR 0044). Un parent lexical et ses sous-packages peuvent appartenir à des modules différents ; une déclaration de package identique ne le peut pas.
- Les ports `StorageBackend`, `StorageBackendProvider`, `CodeKnowledgeSnapshotStore` et les modèles neutres restent dans le cœur. Aucune dépendance application → stockage concret n'est réintroduite.
- L'assemblage et la sélection restent dans `minos-bootstrap`, notamment `StorageBackendSelection`. Ne pas créer un second bootstrap dans `minos-storage`.
- `local` et `postgresql` ne dépendent pas l'un de l'autre ; ils peuvent dépendre de `codec` et du cœur. `codec` ne dépend d'aucun backend concret.
- Conserver les identifiants de configuration, fichiers de services SPI, sélection explicite du backend, contrat Java public et formats persistés. Le mode local ne doit ni ouvrir de connexion PostgreSQL ni exiger un serveur ou Docker pour fonctionner.
- Conserver le raccourci de sélection locale pendant la fusion ; une uniformisation SPI éventuelle doit être séparée et testée. Elle n'est pas nécessaire à cette décision.

## Alternatives et conséquences

Deux modules maintiendraient une isolation Maven et des distributions séparables, au prix du couplage codec ou d'un troisième artefact. La fusion est retenue pour simplifier le produit livré ensemble. Les dépendances JDBC/Jackson rejoindront le graphe transitif du module commun : inventorier l'impact sur API embarquée, taille, SBOM et avis tiers. Ne pas prétendre que PostgreSQL reste absent du classpath local.

Les frontières internes sont désormais contrôlées par les packages et des tests d'architecture, pas par Maven seul. Aucun nouveau framework serveur, ORM, changement de licence, migration de données ou release n'est induit.

Les coordonnées Maven changent. Vérifier les consommateurs avant suppression ; documenter la migration. Si des coordonnées anciennes sont contractuellement consommées, proposer des POM de relocation sans dupliquer les classes, puis valider ce choix séparément. Les signatures Java et les bytes persistés restent inchangés pendant la fusion.

## Relations avec les décisions antérieures

Amende **uniquement la partition des artefacts de stockage** de l'ADR 0022 et les emplacements concernés de l'ADR 0044. Préserve les frontières de l'ADR 0042 et le bootstrap de l'ADR 0045. Ne remplace pas les ADR 0023/0024/0025 ni les règles V1/V2/V3 et repli V2 de l'ADR 0046. L'ADR 0047 demeure conditionnel et indépendant.

## Exécution et validation

[Backlog et tâches SH-01 à SH-03, SH-11 et SH-12](../roadmap/storage-hexagonal-2026-10/README.md).

Critères : un seul module de stockage dans le reactor ; aucun package éclaté ; aucune dépendance directe entre les deux backends ; lecture des formats historiques, publication, reprise, verrous, rétention et synchronisation sémantique inchangées ; backend local utilisable sans service PostgreSQL ; qualification PostgreSQL obligatoire dans l'environnement qui lui est réservé ; JAR distribué et SPI vérifiés.

## Références

- [Maven — reactor multi-module](https://maven.apache.org/guides/mini/guide-multiple-modules.html)
- [Oracle — ServiceLoader](https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/util/ServiceLoader.html)
- [ADR 0042](0042-racine-de-composition.md), [ADR 0044](0044-un-package-un-module.md), [ADR 0046](0046-format-de-snapshot-v3-chaines-utf8.md).
