---
id: ARCH-0082
title: "Exposer l'exploration sémantique par une surface HTTP locale, optionnelle et en lecture seule"
domain: architecture
rules: false
---

# Exposer l'exploration sémantique par une surface HTTP locale, optionnelle et en lecture seule

- **Statut** : Accepted
- **Date** : 2026-11-13
- **Origine** : décision du propriétaire du 2026-11-13 ; changement OpenSpec `explorer-l-index-semantique-dans-un-navigateur` ; étude `docs/project/research/explorateur-vectoriel-2026-11/`
- **Remplace** : —
- **Remplacé par** : —

## Context

MINOS n'a aucune surface réseau en écoute : ses surfaces sont la CLI, l'API Java et le MCP en STDIO (recherche de `HttpServer`, `jetty`, `javalin`, `undertow`, `netty` dans les sources et les POM au commit `63e0c4ac` : aucune occurrence). Un explorateur dans un navigateur — liste des documents sémantiques, recherche, carte des embeddings — exige un transport que le navigateur sait joindre.

Contraintes en vigueur :

- [ARCH-0017](ARCH-0017-mcp-stdio-read-only.md) : le MCP reste en STDIO et en lecture seule ; un serveur HTTP ne doit pas devenir obligatoire.
- [GEN-0077](GEN-0077-socle-d-execution-java-21-et-maven.md) § 7 : aucun framework serveur dans le domaine ni les services ; une couche d'exposition réseau est un adaptateur périphérique.
- [ARCH-0068](ARCH-0068-modules-dependances-et-propriete-des-packages.md) : les surfaces ne dépendent d'aucun adaptateur ; pas de module créé pour reproduire un package.
- [ARCH-0069](ARCH-0069-backends-mcp-natif-et-docker.md) : en backend Docker, `MinosApplication` n'est pas ouverte côté hôte.
- [SEC-0071](SEC-0071-plan-de-controle-d-equipe-embarque.md) : pas de serveur HTTP propre au plan de contrôle ; aucun secret en argument.
- [BE-0029](BE-0029-optional-rebuildable-semantic-layer-and-hybrid-ranking.md) : aucune construction d'index déclenchée en silence.

Faits qui bornent la décision : la recherche sémantique n'est soumise à aucun contrôle de permission (`minos-mcp/src/main/java/com/minos/mcp/MinosApplicationMcpBackend.java:250-251`) et les permissions d'équipe ne couvrent pas la lecture du code (`minos-engine/src/main/java/com/minos/hosted/HostedPermission.java:4-14`). Il n'existe donc aucun mécanisme d'autorisation par projet sur lequel adosser un accès distant.

Options considérées :

- **A. Surface HTTP locale dans MINOS**, sur le serveur HTTP du JDK (`jdk.httpserver`), ressources statiques embarquées.
- **B. Application web séparée** lisant PostgreSQL : credentials de base hors de MINOS, logique de recherche dupliquée, nouvelle toolchain, stockage local non couvert.
- **C. Export statique** d'un fichier HTML : aucune écoute, mais aucune recherche en langage naturel et un fichier contenant du code indexé.
- **D. Framework serveur** : contraire à GEN-0077 pour un besoin que le JDK couvre.

## Decision

1. **Une surface HTTP d'exploration, optionnelle.** Elle n'existe que lorsqu'une commande explicite la démarre (`minos explorer serve`). Aucune autre surface n'en dépend ; le MCP reste en STDIO.
2. **Boucle locale uniquement.** Le serveur écoute sur `127.0.0.1` et `::1`. Aucune option n'élargit l'écoute. En runtime Docker, le port n'est publié que sur la boucle locale de l'hôte.
3. **Boucle locale n'est pas authentification.** Toute requête d'API exige un jeton de session aléatoire généré au démarrage, transmis par en-tête, jamais par cookie ni par argument de commande. L'en-tête `Host` est contrôlé contre une liste fermée ; une requête portant un `Origin` étranger est refusée ; aucun en-tête CORS n'est émis.
4. **Lecture seule.** Aucune route ne modifie un index, une projection, un projet ou une configuration. Toute préparation (index, projection) est une commande distincte.
5. **Adaptateur périphérique sans framework.** La surface s'appuie sur le serveur HTTP du JDK et sur les services de `minos-application` ; elle ne dépend d'aucun adaptateur de stockage. Ses ressources web sont embarquées dans le jar : aucune ressource n'est chargée d'un tiers.
6. **API versionnée et additive**, sous un préfixe de version (`/api/v1/`).
7. **Aucun accès distant ni multi-utilisateur.** Quand le mode équipe est activé (`MINOS_HOSTED_MODE=enabled`), la surface refuse de démarrer. Un accès distant exige une décision séparée qui définit authentification et autorisation par projet.
8. **Un module de surface.** La surface vit dans le module `minos-explorer`, au même rang que `minos-mcp` ; aucun autre module ne porte d'écoute HTTP.

Exclus : écoute sur une interface non locale, TLS et comptes utilisateurs, exposition du plan de contrôle d'équipe, écriture par HTTP.

## Do's and Don'ts

### Do

- Démarrer la surface HTTP par une commande explicite, et l'arrêter avec elle.
- Écouter sur la boucle locale seulement, et contrôler `Host`, `Origin` et le jeton de session sur chaque requête d'API.
- Servir les ressources web depuis le jar, avec une politique de sécurité de contenu qui interdit les scripts en ligne et les origines tierces.
- Insérer tout contenu indexé comme texte.
- Faire déléguer chaque route aux services MINOS existants, bornes comprises.
- Faire évoluer l'API HTTP de façon additive sous son préfixe de version.
- Placer tout le code de la surface HTTP dans le module `minos-explorer`.

### Don't

- Ne pas rendre la surface HTTP nécessaire à la CLI, au MCP ou à l'API Java.
- Ne pas offrir d'option d'écoute sur une interface non locale.
- Ne pas traiter la boucle locale comme une authentification.
- Ne pas exposer de route qui écrit, synchronise un index ou prépare une projection.
- Ne pas transmettre au navigateur de credentials PostgreSQL, d'adresse de provider ni de chemin absolu.
- Ne pas introduire de framework serveur ni de ressource chargée d'un CDN.
- Ne pas démarrer la surface quand le mode équipe est activé.

## Consequences

### Positives

- L'index sémantique devient explorable sans outil tiers, sur les deux stockages.
- Aucune dépendance serveur nouvelle ; la recherche reste celle des services existants.
- La consultation et la préparation restent séparées, comme pour le MCP.

### Négatives

- MINOS acquiert une première écoute réseau, donc une surface à qualifier et à maintenir sur Windows et Linux.
- L'interface web est un second langage (JavaScript) dans le dépôt, sans chaîne de build par choix.
- Le runtime Docker exige un service et un réseau d'entrée supplémentaires.

### Risques

- Un contrôle `Host`/`Origin` incomplet exposerait du code indexé à une page tierce : couvert par des tests de refus exigés par les spécifications.
- Le module `jdk.httpserver` doit figurer dans le runtime embarqué de la distribution Windows.

## Compliance and Enforcement

- Tests de la surface (à écrire avec elle) : refus sans jeton, refus d'un `Host` hors liste, refus d'un `Origin` étranger, absence d'en-tête CORS, absence de route d'écriture, refus de démarrage en mode équipe.
- `scripts/architecture/check-module-boundaries.py` : la surface ne dépend d'aucun adaptateur.
- Règle Archgate `GEN-0077…rules.ts` / `no-server-framework-in-poms` : aucun framework serveur.
- Revue : aucune option d'écoute non locale ; aucune ressource externe dans les fichiers web.
- Aucune règle Archgate compagnon tant que la surface n'existe pas (`rules: false`) ; elle est écrite avec le module.
- Tant que le module n'existe pas, la décision n'est pas mise en œuvre : aucune capacité n'est livrée par son acceptation.

## Mise en œuvre

Changement OpenSpec `explorer-l-index-semantique-dans-un-navigateur`.

- Surface : module `minos-explorer` (`ExplorerSession`, `ExplorerServer`, `RequestGuard`, `ExplorerApi`,
  `ListeningPolicy`, page embarquée). Aucune dépendance tierce ; `jdk.httpserver` est tenu explicite dans le
  runtime Windows embarqué (`scripts/release/build-windows-distribution.ps1`).
- Commande : `minos explorer serve [--port <n>]`. `minos-cli` déclare `ExplorerLaunchRoute`, que `minos-app`
  fournit par `ServiceLoader` (`ExplorerLaunchRouteProvider`) : aucune surface ne dépend de `minos-explorer`.
- Lectures : `SemanticVectorStore.documentPage` et `storedDocument`, `SemanticCorpusQueryService`,
  `SemanticExplorationApi` v1.
- Contrôles tenus par des tests : `ExplorerServerTest` (boucle locale, jeton, `Host`, `Origin`, absence de CORS
  et de cookie, en-têtes, bornes), `ExplorerRoutesTest` (aucune écriture dans `MINOS_HOME`, aucun chemin absolu,
  inventaire des méthodes), `ExplorerSessionTest` et `ExplorerLaunchRouteProviderTest` (refus en mode équipe),
  `ModuleArchitectureTest` (seule la route de l'assemblage atteint la surface ; aucun autre module n'ouvre
  d'écoute réseau ; la surface n'utilise aucun adaptateur de stockage), `WebResourcesContentTest` (contenu
  inséré comme texte, aucune ressource externe).
- Aucune règle Archgate compagnon n'est écrite (`rules: false`) : les contraintes sont tenues par ces tests et
  par `scripts/architecture/check-module-boundaries.py`.

## Écarts constatés

- **Écoute en conteneur (point 2).** Un port publié par Docker atteint le processus par l'interface du
  conteneur, pas par sa boucle locale. Dans le runtime Docker, la surface écoute donc sur cette interface. Il
  n'existe toujours aucune option utilisateur : l'écoute n'est élargie que par la variable interne
  `MINOS_EXPLORER_CONTAINER_LISTEN=enabled`, posée par le Compose, et seulement si
  `MINOS_RUNTIME_LOCATION=docker` et si le processus tourne sous Linux avec un marqueur de conteneur ; toute
  autre combinaison est un refus de démarrage (`ExplorerUnitsTest`, `ExplorerSessionTest`). Le port n'est
  publié que sur `127.0.0.1` de l'hôte ; jeton, `Host` et `Origin` sont inchangés. Décision du propriétaire,
  2026-11-13.
- **Sortie réseau du conteneur.** Le réseau d'entrée du service `minos-explorer` n'est pas interne : ce
  conteneur a une route sortante, que `minos-mcp` n'a pas. Couper le masquerading d'adresse ne la ferme pas
  sur Docker Desktop (relevé le 2026-11-13). L'écart est déclaré dans l'étiquette du service et dans
  `docs/user/docker-runtime.md` ; le propriétaire l'accepte en l'état (décision du 2026-11-13), le service restant optionnel et non qualifié.
- **Contrôle d'origine (point 3).** En plus d'`Origin`, la surface refuse toute requête dont l'en-tête
  `Sec-Fetch-Site` vaut autre chose que `same-origin` ou `none`, page d'accueil comprise
  (`RequestGuard.check`). Un navigateur qui ouvre l'adresse depuis un lien d'une autre page reçoit donc `403`
  avec un corps vide ; l'adresse saisie dans la barre d'adresse est servie. Le maintien, l'ouverture de la
  seule page d'accueil ou un corps `403` lisible restent à décider par le propriétaire.
- **`::1`.** La surface écoute sur `::1` quand la machine a cette adresse ; elle ne démarre pas si le port y
  est déjà pris, et n'y écoute pas sur une machine sans IPv6.
- **Jeton et journaux (point 3).** L'adresse d'accès, jeton compris, est écrite une fois sur la sortie
  d'erreur. En Docker, elle reste dans le journal du conteneur jusqu'à sa suppression.
- **Disponibilité.** Un processus local peut occuper les fils de connexion sans jeton ; la surface coupe ces
  connexions après 10 secondes et se rétablit seule.

## References

- Changement OpenSpec `openspec/changes/explorer-l-index-semantique-dans-un-navigateur/`.
- Étude : [`docs/project/research/explorateur-vectoriel-2026-11/`](../../project/research/explorateur-vectoriel-2026-11/README.md).
- ADR liés : ARCH-0017, ARCH-0068, ARCH-0069, GEN-0077, SEC-0071, BE-0029, BE-0031.
