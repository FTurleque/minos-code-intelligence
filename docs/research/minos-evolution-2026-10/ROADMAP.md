# Roadmap intégrale — évolution retrieval et intelligence IDE

Date : 2026-10-04. **Toutes les tâches sont à faire.** Aucune date de release fixée.
Base : develop `c9a339088f81b6b31c65c7ad12bc718be2a98a7b`.
[Étude](README.md) · [Évaluation](EVALUATION.md) · [Guide Claude](CLAUDE-HANDOFF.md)

## Pilotage

P0 = nécessaire au socle et aux garanties ; P1 = amélioration après prérequis ; P2 = chantier exploratoire conditionnel. Une priorité ne supprime pas les dépendances. L'unité d'estimation est le **jour de travail effectif d'un développeur expérimenté assisté de Claude**, revue comprise de façon indicative ; ni durée calendaire ni garantie de vitesse de l'IA.

Une tâche = une unité de revue, normalement une PR. Scinder une tâche dépassant 3 jours si elle mélange contrats, migration et comportement. Le développement ne commence qu'après décision sur l'ADR pertinent. Un no-go clôt une expérimentation avec ses preuves et n'autorise pas la suite conditionnelle.

| Lot | Résultat | Dépendances principales | Sortie |
|---|---|---|---|
| U0 | baseline et protocole | état actuel/audit | G0 |
| U1 | classement lexical/hybride | U0 | G1/G3 |
| U2 | unités cohérentes et corpus | U0, caractérisation U1 | G1/G3 |
| U3 | contexte compact et budget | U1 et U2 | G2/G5 |
| U4 | embeddings CPU optionnels | U0 puis U1/U2 finaux | go/no-go |
| U5 | fraîcheur et actualisation | U2 diagnostic | G4/G5 |
| U6 | ergonomie MCP et Claude | U3 et U5 diagnostic | mesure tâches agents |
| U7 | bridge IntelliJ optionnel | U0/U6 profils | go/no-go G6 |
| U8 | intégration et livraison | socle qualifié, options retenues | G7 |

U1/U2/U5 peuvent être travaillés séparément après leurs prérequis mais touchent des contrats communs : un responsable doit arbitrer les DTO/cache keys avant leurs merges. Ceci décrit des dépendances de développement futures, pas une consigne de lancer plusieurs agents.

## Découpage de livraison

- **Tranche A** : U0, U1, U2-01/02 et U3. Valeur : recherche et contexte, sans nouvelle dépendance d'exécution.
- **Tranche B** : U2-03/04, U5, U6 puis U8. Valeur : corpus complémentaire, fraîcheur et usage Claude.
- **Option C** : U4 seulement après spike concluant.
- **Option D** : U7 seulement après spike concluant ; ne bloque pas le socle.
- Chaque tranche publiable rejoue le sous-ensemble U8 applicable. Les tâches U8 ci-dessous décrivent le passage final du programme ; elles ne dispensent pas de qualification intermédiaire.
- Ni ANN, ni V4/mmap, ni édition du code, ni indexation distante hostile ne sont inclus. Ces sujets demandent leurs décisions propres.

## Charge indicative

| Lot | Charge |
|---|---|
| U0 | 6–11 jours |
| U1 | 8–14 jours |
| U2 | 6–11 jours |
| U3 | 4–7 jours |
| U4 | 7–12 jours |
| U5 | 6–11 jours |
| U6 | 4–7 jours |
| U7 | 10–16 jours |
| U8 | 4–7 jours |

Socle hors options U4/U7 : **38–68 jours effectifs**, avant marge de 20–30 % pour intégration, disponibilité des machines et incertitudes. Les accès d'entreprise, budgets modèles, revues et interruptions augmentent le calendrier. Recalculer après U0 : un spike peut supprimer une option entière. Aucun gain de délai arbitraire attribué à Claude.

## Contrat commun de réalisation

Pour chaque tâche : lire le code courant ; préciser hypothèse et fichiers ; conserver le comportement hors périmètre ; tests qui vérifient un risque réel ; rapport du SHA, commandes et résultats ; limitations et rollback. Aucun PASS inventé, aucun contournement de gate/sandbox. Les nouveaux noms de fichiers/classes sont des propositions ; les chemins existants sont des points d'entrée à revérifier au démarrage.

Les seuils G0–G7 sont définis dans EVALUATION.md. Tous les nouveaux profils restent opt-in jusqu'à décision de promotion. Les migrations de caches reconstruisibles ne modifient pas les snapshots autoritatifs.

## Backlog exécutable

### U0-01 — Figer l'état de départ

- **État :** TODO. **Priorité :** P0. **Charge :** 1–2 j. **Dépendances :** —.
- **Points d'entrée :** docs/research/minos-evolution-2026-10 ; docs/audit ; docs/adr.
- **Travail :** Relire develop et les PR intervenues depuis le SHA de l'étude ; cartographier les tâches déjà réalisées, dont caches et composition ; vérifier la numérotation ADR.
- **Livrable / acceptation :** Inventaire actuel/à faire avec fichiers et SHA ; aucune duplication de l'audit ; ADR contradictoires identifiés.
- **Vérification :** Revue des liens et comparaison des chemins réels.

### U0-02 — Construire le corpus annoté

- **État :** TODO. **Priorité :** P0. **Charge :** 2–3 j. **Dépendances :** U0-01.
- **Points d'entrée :** benchmarks/retrieval (nouveau) ; fixtures/retrieval (nouveau).
- **Travail :** Créer manifeste et 240 questions selon EVALUATION ; partitions sans fuite ; fixtures de références et réponses absentes ; garder les sources privées hors Git.
- **Livrable / acceptation :** Annotations relues, split gelé et hashé ; chaque réponse attendue a une justification ; périmètre autorisé explicite.
- **Vérification :** Validation du schéma, détection doublons et chemins hors corpus ; revue humaine.

### U0-03 — Créer les adaptateurs du banc

- **État :** TODO. **Priorité :** P0. **Charge :** 2–4 j. **Dépendances :** U0-02.
- **Points d'entrée :** benchmarks/retrieval (nouveau) ; benchmarks/scalability.
- **Travail :** Piloter MINOS, Semble, rg ciblé/plein fichier, Serena sur fonctions comparables ; épingler versions, capturer ressources et erreurs ; timeouts et profils isolés.
- **Livrable / acceptation :** Un run reproductible sans modifier les clients personnels ; NOT_SUPPORTED/NOT_RUN distincts ; commande de reproduction.
- **Vérification :** Smoke sur mini corpus, timeout, arrêt descendants et canari réseau.

### U0-04 — Établir baseline et gates

- **État :** TODO. **Priorité :** P0. **Charge :** 1–2 j. **Dépendances :** U0-03.
- **Points d'entrée :** benchmarks/retrieval ; EVALUATION.md.
- **Travail :** Exécuter le socle et les comparateurs disponibles ; fixer machine, budget mémoire et seuils ; enregistrer limites Windows AppContainer et licences manquantes.
- **Livrable / acceptation :** Rapport G0 approuvé ; pas de chiffres concurrents substitués aux mesures ; budget expérimentation arrêté.
- **Vérification :** Rejeu indépendant d'un échantillon ; contrôle SHA et statistiques.

### U1-01 — Caractériser classement et contrats existants

- **État :** TODO. **Priorité :** P0. **Charge :** 1–2 j. **Dépendances :** U0-04.
- **Points d'entrée :** HybridSearchService ; minos-api ; McpToolSchemas ; minos-bootstrap tests.
- **Travail :** Capturer ordre, scores minimumScore, égalités, fallback sans embedding, erreurs et réponses CLI/API/MCP ; réutiliser les golden actuels.
- **Livrable / acceptation :** Baseline contractuelle avant changement ; cas négatifs et queries Unicode présents.
- **Vérification :** Tests de caractérisation utiles ; aucune mise à jour aveugle des golden.

### U1-02 — Isoler des stratégies internes de recherche

- **État :** TODO. **Priorité :** P0. **Charge :** 2–3 j. **Dépendances :** U1-01.
- **Points d'entrée :** minos-application/com/minos/application/semantic ; minos-bootstrap.
- **Travail :** Extraire sélection lexicale et fusion sans changer le comportement ; injecter un profil versionné à la composition ; éviter nouvelle dépendance adaptateur.
- **Livrable / acceptation :** Profil legacy strictement équivalent ; classes ciblées et responsabilités bornées ; aucun nouveau défaut produit.
- **Vérification :** Golden U1-01 et contrôle module boundaries ; ordre stable.

### U1-03 — Ajouter BM25 et segmentation d'identifiants

- **État :** TODO. **Priorité :** P0. **Charge :** 2–4 j. **Dépendances :** U1-02.
- **Points d'entrée :** minos-application semantic ; cache corpus existant.
- **Travail :** Indexer termes exacts et sous-termes camelCase/snake_case ; IDF/longueurs calculés par génération ; candidats top-k bornés et ties déterministes.
- **Livrable / acceptation :** Profil lexical expérimental ; corpus invalidé sur tokenizerVersion ; mémoire incluse au poids du cache.
- **Vérification :** Cas homonymes, acronymes, accents, C++/Java qualified names ; mesure BM25 seul.

### U1-04 — Expérimenter RRF et routage

- **État :** TODO. **Priorité :** P1. **Charge :** 2–3 j. **Dépendances :** U1-03.
- **Points d'entrée :** HybridSearchService ; configuration bootstrap.
- **Travail :** Fusion par rang des candidats lexical/vectoriel ; bonus graphe borné ; routage déterministe symbol-like vs prose ; ablations.
- **Livrable / acceptation :** rankingProfile/version exposés ; score non présenté comme probabilité ; minimumScore legacy inchangé ou contrat v2 explicite.
- **Vérification :** G1/G3, comparaison avec/sans graphe ; requêtes test/legacy.

### U1-05 — Décider le profil de classement

- **État :** TODO. **Priorité :** P0. **Charge :** 1–2 j. **Dépendances :** U1-04.
- **Points d'entrée :** benchmarks/retrieval ; docs/user ; ADR-0049.
- **Travail :** Mesurer holdout et publier décision garder legacy/promouvoir candidat ; flags et rollback ; pas de tuning sur holdout.
- **Livrable / acceptation :** Verdict justifié ; défaut ne change que si gates remplis ; limitations FR/EN documentées.
- **Vérification :** Reproduction d'un sous-ensemble ; golden du profil choisi.

### U2-01 — Auditer les unités et la cohérence source/snapshot

- **État :** TODO. **Priorité :** P0. **Charge :** 1–2 j. **Dépendances :** U1-01.
- **Points d'entrée :** SemanticDocumentFactory ; LocalSourceReader ; lifecycle.
- **Travail :** Mesurer chevauchements et vérifier l'accord fichier/snapshot lors de la construction ; inventorier les plages symboliques réellement disponibles.
- **Livrable / acceptation :** Taxonomie des doublons et races ; aucun diagnostic présenté comme bug sans reproduction.
- **Vérification :** Fixtures fichier modifié pendant construction, symboles imbriqués et code tronqué.

### U2-02 — Produire des unités adaptées au code

- **État :** TODO. **Priorité :** P1. **Charge :** 2–4 j. **Dépendances :** U2-01.
- **Points d'entrée :** SemanticDocumentFactory ; types SemanticDocument.
- **Travail :** Préférer frontières de symboles existantes ; fractionner les gros symboles avec signature et identifiant parent ; fallback borné texte/syntaxe déclaré ; version découpage.
- **Livrable / acceptation :** Pas de nouvelle relation structurelle déduite du découpage ; identités stables et migrations de cache définies.
- **Vérification :** Tests méthodes longues/imbriquées/Unicode ; benchmarks à classement fixe.

### U2-03 — Étendre au corpus docs/config de façon distincte

- **État :** TODO. **Priorité :** P1. **Charge :** 2–3 j. **Dépendances :** U2-02.
- **Points d'entrée :** domain semantic ; factory ; sources confinées.
- **Travail :** Concevoir contentScope code/docs/config/all ; documents non SCIP identifiés séparément ; exclusions, bornes et hash ; pas d'exécution de fichier.
- **Livrable / acceptation :** Défaut code conservé ; fichier sans symbole recherchable en mode explicite ; aucun faux symbolId.
- **Vérification :** Tests ignore, symlink, fichier géant, secrets exclus et corpus mixte.

### U2-04 — Qualifier qualité et coût du découpage

- **État :** TODO. **Priorité :** P1. **Charge :** 1–2 j. **Dépendances :** U2-03.
- **Points d'entrée :** benchmarks/retrieval ; SemanticIndexService.
- **Travail :** Comparer ancien/nouveau découpage, stabilité après édition et volume vecteurs ; promotion conditionnelle.
- **Livrable / acceptation :** G1/G3 remplis ou ancien mode conservé ; coût supplémentaire des documents publié.
- **Vérification :** Holdout découpage et rebuild après changement de version.

### U3-01 — Dédupliquer et diversifier le contexte

- **État :** TODO. **Priorité :** P0. **Charge :** 1–2 j. **Dépendances :** U1-05,U2-02.
- **Points d'entrée :** HybridContextBuilder.
- **Travail :** Fusionner plages compatibles d'un même fichier/génération ; éviter répétitions SYMBOL/CHUNK/FILE ; diversité sans supprimer preuves d'impact.
- **Livrable / acceptation :** Ordre et provenance conservés ; troncature et raisons de sélection explicites.
- **Vérification :** Cas plusieurs symboles/fichiers, mêmes noms, générations différentes ; G2.

### U3-02 — Budgéter la réponse complète

- **État :** TODO. **Priorité :** P0. **Charge :** 2–3 j. **Dépendances :** U3-01.
- **Points d'entrée :** TokenEstimator ; HybridContextBuilder ; sérialiseurs surfaces.
- **Travail :** Distinguer contenu et enveloppe JSON ; compteurs estimés nommés ; réserver overhead dynamique ; mesurer tokenizer référence hors runtime.
- **Livrable / acceptation :** Budget défini sur périmètre documenté ; aucun usedTokens présenté comme facture exacte ; sérialisation bornée.
- **Vérification :** Chaînes échappées, Unicode, longues métadonnées, nombreux items et faible budget.

### U3-03 — Ajouter des formats compacts compatibles

- **État :** TODO. **Priorité :** P1. **Charge :** 1–2 j. **Dépendances :** U3-02.
- **Points d'entrée :** minos-mcp ; minos-api ; minos-cli.
- **Travail :** Vue résumé avec références consultables puis détail explicite ; ne pas charger le code intégral par défaut ; conserver v1.
- **Livrable / acceptation :** Nouvelles options additives et profils documentés ; génération/provenance toujours accessibles.
- **Vérification :** Golden par surface et G2/G5 ; reprise d'une citation périmée.

### U4-01 — Évaluer les modes d'embedding CPU

- **État :** TODO. **Priorité :** P1. **Charge :** 2–3 j. **Dépendances :** U0-04.
- **Points d'entrée :** EmbeddingProvider ; prototypes hors production.
- **Travail :** Comparer modèle statique natif Java et sidecar isolé ; exactitude tokenizer/vecteur par rapport à référence, FR/EN, dimensions, OOV et limites.
- **Livrable / acceptation :** Rapport de choix natif/sidecar/no-go avec licence modèle, empreinte, coût maintenance et distribution.
- **Vérification :** Vecteurs référence avec tolérance fixée ; G1/G3 ; aucun artefact téléchargé implicitement.

### U4-02 — Implémenter le provider retenu sous option

- **État :** TODO. **Priorité :** P1. **Charge :** 2–4 j. **Dépendances :** U4-01.
- **Points d'entrée :** EmbeddingProvider ; minos-bootstrap ; adaptateur si nécessaire.
- **Travail :** Réutiliser SPI ; si sidecar, port neutre compatible frontières, framing borné et process supervisé ; providerId/model digest explicites.
- **Livrable / acceptation :** Option désactivée par défaut ; Ollama/local-hash inchangés ; échec explicite ou fallback structuré indiqué.
- **Vérification :** Modèle absent/corrompu, timeout, dimensions invalides, concurrence et fermeture.

### U4-03 — Provisionner et distribuer le modèle

- **État :** TODO. **Priorité :** P1. **Charge :** 2–3 j. **Dépendances :** U4-02.
- **Points d'entrée :** packaging ; scripts/install ; minos-bootstrap configuration.
- **Travail :** Manifeste versionné avec SHA-256, licence/notices, taille et chemin ; installation explicite atomique ; offline ; nettoyage sous ownership.
- **Livrable / acceptation :** MCP ne télécharge rien ; rollback modèle conserve snapshot ; aucune dépendance globale ajoutée silencieusement.
- **Vérification :** Archive hostile, coupure, droits Windows, réseaux coupés, mise à jour et désinstallation.

### U4-04 — Qualifier le provider sur la recherche finale

- **État :** TODO. **Priorité :** P1. **Charge :** 1–2 j. **Dépendances :** U4-03,U1-05,U2-04.
- **Points d'entrée :** benchmarks/retrieval ; docs/user ; ADR-0051.
- **Travail :** Mesurer modèle léger contre Ollama configuré, sans confondre local-hash et learned ; publier rôle recommandé ou abandon.
- **Livrable / acceptation :** G1/G3 et compatibilité passés ; no-go accepté comme sortie de spike si coût/qualité défavorables.
- **Vérification :** Holdout, RSS total, démarrage froid et canari réseau.

### U5-01 — Unifier le diagnostic de fraîcheur

- **État :** TODO. **Priorité :** P0. **Charge :** 1–2 j. **Dépendances :** U2-01.
- **Points d'entrée :** SemanticIndexService ; états existants ; surfaces.
- **Travail :** Inventorier génération structurée/sémantique et source ; définir raisons STALE et limites de détection ; épingler identité de requête.
- **Livrable / acceptation :** Réponse sans mélange de générations ; read-only réel ; distinction freshness inconnue/périmée.
- **Vérification :** Race publication/requête, rollback, changement modèle et fichier.

### U5-02 — Créer le coordinateur d'actualisation opt-in

- **État :** TODO. **Priorité :** P1. **Charge :** 2–4 j. **Dépendances :** U5-01.
- **Points d'entrée :** CLI lifecycle ; minos-bootstrap ; runtime local.
- **Travail :** Commande locale explicite watch/sync à spécifier ; réutiliser leases, reprise et promotions ; debounce et file bornée ; incrémental uniquement qualifié.
- **Livrable / acceptation :** Aucun processus lancé depuis le MCP ; mode absent inchangé ; pas de contournement sandbox ou remote index.
- **Vérification :** Création/suppression/rename, burst, branches, double processus et arrêt propre.

### U5-03 — Durcir la synchronisation et le rollback

- **État :** TODO. **Priorité :** P0. **Charge :** 2–3 j. **Dépendances :** U5-02.
- **Points d'entrée :** lifecycle ; stockage ; coordination.
- **Travail :** Reconciliation sur overflow ; crash/restart ; publication atomique de caches compatibles ; conserver snapshot précédent en échec.
- **Livrable / acceptation :** G4 ; cache jamais étiqueté READY pour autre snapshot ; vieux index nettoyé sous lease.
- **Vérification :** Injection de panne avant/après promotion, modèle indisponible et quotas.

### U5-04 — Rendre l'exploitation diagnostiquable

- **État :** TODO. **Priorité :** P1. **Charge :** 1–2 j. **Dépendances :** U5-03.
- **Points d'entrée :** doctor ; docs/user ; plugin UI.
- **Travail :** Exposer file pending/erreur/last success et recommandation de synchronisation ; procédures offline et charge élevée.
- **Livrable / acceptation :** L'utilisateur comprend l'état sans journal privé ; défaut AppContainer distingué d'un bug retrieval.
- **Vérification :** Scénarios opérateur Windows/Linux ; aucun chemin sensible dans erreur publique.

### U6-01 — Définir des profils MCP au démarrage

- **État :** TODO. **Priorité :** P1. **Charge :** 1–2 j. **Dépendances :** U3-03.
- **Points d'entrée :** MinosMcpTools ; McpToolSchemas ; configuration.
- **Travail :** Profils explicites minimal/recherche/analyse/complet ; défaut legacy ; mêmes handlers métier ; descriptions indiquant quand utiliser chaque outil.
- **Livrable / acceptation :** Pas de suppression silencieuse legacy ; unknown profile refusé ; capabilities listables.
- **Vérification :** tools/list golden par profil, client sans tools/list_changed, G5.

### U6-02 — Exposer des réponses progressives

- **État :** TODO. **Priorité :** P1. **Charge :** 1–2 j. **Dépendances :** U6-01,U5-01.
- **Points d'entrée :** minos-mcp ; application DTO.
- **Travail :** Contexte résumé puis détail par références liées à génération ; TTL/invalidations si handle ; limiter profondeur et taille.
- **Livrable / acceptation :** Aucun handle périmé réinterprété ; pas de REPL/code arbitraire ; pas de secret en schéma.
- **Vérification :** Expire/génération changée, identifiant inconnu, budgets et déterminisme.

### U6-03 — Évaluer les profils avec Claude

- **État :** TODO. **Priorité :** P1. **Charge :** 2–3 j. **Dépendances :** U6-02.
- **Points d'entrée :** benchmarks/agent (nouveau) ; docs/user.
- **Travail :** 20 tâches contrôlées, modèle/client et prompt épinglés ; baseline outils natifs vs MINOS vs combinaisons ; budget autorisé explicite.
- **Livrable / acceptation :** Taux de réussite, appels et tokens complets publiés ; instruction de routage utile ; résultat négatif conservé.
- **Vérification :** 3 répétitions/tâche si budget disponible ; sinon NOT_RUN documenté.

### U7-01 — Spiker la valeur de l'intelligence IntelliJ

- **État :** TODO. **Priorité :** P2. **Charge :** 2–3 j. **Dépendances :** U0-04,U6-01.
- **Points d'entrée :** minos-intellij MinosProjectService ; prototype isolé.
- **Travail :** Mesurer usages/surcharges/dépendances avec API PSI publiques ; java21 plugin, CE/features installées ; comparer socle et Serena si disponible.
- **Livrable / acceptation :** Décision go/no-go avant produit ; matrice langage/IDE/dumb mode ; aucune promesse d'analyse absente.
- **Vérification :** Fixtures typées et dépendances externes ; timings/couverture, G6.

### U7-02 — Spécifier protocole et sécurité du bridge

- **État :** TODO. **Priorité :** P2. **Charge :** 2–3 j. **Dépendances :** U7-01.
- **Points d'entrée :** protocole versionné ; minos-engine port si requis.
- **Travail :** Définir allowlist lecture, identité projet/fichier/document, tailles/timeouts ; transport IPC ou loopback authentifié ; threat model.
- **Livrable / acceptation :** ADR-0054 décisionnée ; aucun shell/refactor/debug ; token hors logs et params MCP ; lifecycle précis.
- **Vérification :** Tests de contrat et menace : origine invalide, replay/session expirée, projet hors racine.

### U7-03 — Implémenter lecture symbolique bornée

- **État :** TODO. **Priorité :** P2. **Charge :** 3–5 j. **Dépendances :** U7-02.
- **Points d'entrée :** minos-intellij ; adaptateur bridge ; minos-bootstrap.
- **Travail :** Résolution symboles/usages/implémentations réellement disponibles ; read actions cancellables hors UI ; pas d'analyse forcée bloquante.
- **Livrable / acceptation :** IDE reste optionnel ; requêtes annulables ; buffer non sauvé et source disque distingués.
- **Vérification :** Projet fermé, dumb mode, timeout, deux projets, cancellation et Plugin Verifier.

### U7-04 — Corréler les observations IDE

- **État :** TODO. **Priorité :** P2. **Charge :** 2–3 j. **Dépendances :** U7-03,U5-01.
- **Points d'entrée :** application ; modèle observation distinct.
- **Travail :** Corrélation exacte quand prouvée ; sinon résultat provider-scoped ; snapshot jamais enrichi silencieusement ; état dirty explicite.
- **Livrable / acceptation :** Provenance/version toujours visibles ; aucune confiance artificielle ; socle continue sans IDE.
- **Vérification :** Same-name collision, disque/buffer divergent, changement branche et déconnexion.

### U7-05 — Qualifier et décider la diffusion du bridge

- **État :** TODO. **Priorité :** P2. **Charge :** 1–2 j. **Dépendances :** U7-04.
- **Points d'entrée :** plugin ; banc symbolique ; docs/user.
- **Travail :** Mesurer gain, stabilité, coût et compatibilité sur matrice disponible ; si insuffisant, garder expérimental ou abandonner.
- **Livrable / acceptation :** G6/G7 ou no-go motivé ; aucune fonction payante tierce requise sans choix explicite.
- **Vérification :** Qualification Windows/Linux et versions IDE réellement supportées.

### U8-01 — Qualifier le socle candidat

- **État :** TODO. **Priorité :** P0. **Charge :** 2–3 j. **Dépendances :** U3-03,U5-04,U6-03.
- **Points d'entrée :** minos-bootstrap tests ; surfaces ; CI existante.
- **Travail :** Rassembler compatibilité, concurrence, confidentialité, performance et rollback ; inclure U4/U7 uniquement si retenus et qualifiés.
- **Livrable / acceptation :** Rapport exact SHA avec PASS/FAIL/NOT_RUN ; aucun changement de snapshot format caché ; gates courants conservés.
- **Vérification :** Maven verify et gates applicables une fois par candidat final ; replay motivé si correction.

### U8-02 — Qualifier distribution et migration

- **État :** TODO. **Priorité :** P0. **Charge :** 1–2 j. **Dépendances :** U8-01.
- **Points d'entrée :** packaging Windows ; Docker lecture ; plugin si modifié.
- **Travail :** Test candidat installé, upgrade/downgrade profil, nouveaux caches supprimables ; choix options indépendants ; sécurité réseau.
- **Livrable / acceptation :** Ancienne config fonctionne ; repli profil legacy ; pas d'option imposée ; aucune release automatique.
- **Vérification :** Smoke MCP réel, modèle absent, upgrade interrompu, données utilisateur conservées.

### U8-03 — Réconcilier documentation et préparer release

- **État :** TODO. **Priorité :** P0. **Charge :** 1–2 j. **Dépendances :** U8-02.
- **Points d'entrée :** README ; STATUS ; ROADMAP ; ADR ; docs/user.
- **Travail :** Rapport gains/limites, décision ADR, guide Claude mis à jour, dette restante et note release ; version décidée par propriétaire.
- **Livrable / acceptation :** Documents distinguent conçu/implémenté/qualifié/publié ; PR revue ; main/tag/release séparés.
- **Vérification :** Liens et product facts ; check-current-docs ; revue propriétaire avant promotion.

## Traçabilité ADR → tâches

| ADR | Tâches |
|---|---|
| 0048 | U0-01 à U0-04, U6-03, U8 |
| 0049 | U1-01 à U1-05 |
| 0050 | U2-01 à U2-04, U3-01 à U3-03 |
| 0051 | U4-01 à U4-04 |
| 0052 | U5-01 à U5-04 |
| 0053 | U6-01 à U6-03 |
| 0054 | U7-01 à U7-05 |

## Dépendances à l'audit et décisions externes

- U0 reprend les statuts S23 au lieu de rouvrir D1/S11/C2/S14 comme nouveaux sujets.
- U4-03/U8-02 exigent la preuve offline réelle si cette promesse est faite ; les substituts ne suffisent pas.
- U0-03/U5/U8 documentent le blocage Java/AppContainer encore mentionné dans S23 ; aucun bypass pour obtenir un benchmark « vert ».
- U7 demande une matrice IDE compatible réellement disponible. Une licence Serena JetBrains n'est requise que pour son bras de comparaison ; si absente, NOT_RUN.
- L'ADR-0047 et les mesures de stockage restent indépendants. Reprendre leurs optimisations seulement si les nouvelles mesures montrent un blocage.
- Résoudre les contradictions README/STATUS/ROADMAP lors d'U0 puis U8, à partir de preuves ; ne pas modifier les scripts de contrôle pour faire accepter un récit inexact.

## Definition of Done finale

Chaque capacité annoncée a une preuve sur SHA identifié, plateforme identifiée, modèle/configuration identifiés ; contrats legacy testés ; budget et fraîcheur documentés ; rollback essayé ; ADR décisionnés ; matrice de résultats et limites publiée. Une PR fusionnée n'est pas une release publiée. Promotion develop → main, tag et publication relèvent d'une décision distincte du propriétaire.
