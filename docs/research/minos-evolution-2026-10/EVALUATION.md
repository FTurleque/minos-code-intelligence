# Protocole d'évaluation proposé

Statut : **à implémenter et exécuter**. Aucun résultat comparatif n'a été produit pour cette étude.
Liens : [étude](README.md), [roadmap](ROADMAP.md), [ADR-0048](../../adr/0048-evaluation-comparative-reproductible.md).

## 1. Questions et périmètres comparables

Trois pistes distinctes :
- Retrieval : MINOS actuel, MINOS candidat, Semble, rg avec sélection de plages, rg avec lecture complète.
- Symbolique : MINOS, Serena LSP, puis Serena JetBrains si disponible/licencié, sur les capacités communes.
- Tâches agent : client/modèle fixes, mêmes droits et budgets, chaque combinaison d'outils évaluée séparément.

Ne pas classer un moteur de recherche comme « incorrect » parce qu'il ne renomme pas le code. Les fonctions absentes sont NOT_SUPPORTED, les prérequis manquants NOT_RUN, les exécutions échouées FAILED. Aucun de ces états n'est transformé en zéro artificiel dans une moyenne de qualité ; publier aussi le taux de couverture et les échecs.

## 2. Corpus et vérité attendue

Cible initiale : **240 requêtes annotées**, au moins quatre dépôts locaux autorisés, commits immuables :
- 80 recherche d'implémentation en langage naturel, moitié FR moitié EN ;
- 60 recherche précise de symboles, surcharges et homonymes ;
- 40 architecture, impact et tests liés ;
- 30 documentation/configuration ;
- 30 cas négatifs, ambiguïtés et éléments absents.

Chaque requête a un id, catégorie, langue, difficulté, réponse pertinente avec fichier/plage/symbole, pertinence graduée 0–3, justification humaine et portée de qualification. Faire relire les annotations ambiguës par le propriétaire. Les sorties d'un concurrent ne constituent pas une vérité terrain.

Inclure MINOS et des fixtures Java/TypeScript ; ajouter au moins un projet Java avec dépendances et un corpus différent du projet d'entraînement des règles. Utiliser CivilRH seulement avec autorisation et disponibilité locales : aucun code d'entreprise dans le dépôt public ni dans les journaux publiés. Prévoir des cas XML/SQL/config si c'est un usage cible, sans prétendre que leurs références sont résolues.

Répartir 60 % développement, 20 % validation, 20 % holdout, stratifiés par catégorie. Garder les variantes FR/EN et les questions presque identiques dans la même partition. Réserver si possible un dépôt entier pour tester la généralisation. Geler SHA-256 du manifeste et des annotations avant réglage. Tout changement ultérieur crée une version du jeu.

## 3. Reproductibilité

Manifestes : SHA MINOS/Semble/Serena, version client et identifiant modèle agent, prompts et profils, version provider/langage/JDK, digest modèle d'embedding/tokenizer, OS, CPU, RAM, limites, stockage, taille/fichiers/symboles/documents, périmètre d'exclusion, réseau, paramètres.

Les adaptateurs du banc exécutent des commandes prédéfinies via tableaux d'arguments, jamais une chaîne shell issue du corpus. Chaque outil a un répertoire de données jetable ; ne pas modifier la configuration IA personnelle. Timeouts et groupes de processus tués à l'arrêt. Les données brutes vont hors du dépôt, dans un répertoire explicitement choisi ; seuls manifests assainis et synthèses autorisées peuvent être versionnés.

Mesurer séparément :
1. téléchargement/provisionnement initial (non inclus dans « requête chaude ») ;
2. premier démarrage processus et chargement modèle ;
3. première indexation ;
4. requête après redémarrage avec index disque ;
5. requêtes chaudes ;
6. modification d'un fichier et synchronisation ;
7. temps complet de tâche agent.

Une mesure « froide » doit préciser processus/cache outil/page cache OS ; ne pas prétendre vider le cache OS sans l'avoir fait. Pour chaque lot déterministe : 5 échauffements, au moins 30 observations par classe/taille, 3 redémarrages ; ordre des outils alterné. Pour les tâches agents : au moins 20 tâches, 3 répétitions indépendantes par variante, même révision initiale restaurée. Respecter le budget payant autorisé ; sinon NOT_RUN.

## 4. Métriques

| Axe | Mesure |
|---|---|
| Recherche | Recall@5/10, MRR@10, nDCG@10 macro et par catégorie/dépôt/langue |
| Symbolique | précision/rappel des références résolues, ambiguïtés correctement déclarées |
| Contexte | recall à 1k/2k/4k/8k tokens, doublons, preuves conservées |
| Performance | p50/p95 end-to-end et par phase, débit indexation, temps froid |
| Ressources | RSS total JVM + sidecars, tas, CPU, pic et disque |
| Fraîcheur | délai entre modification sauvegardée et réponse de nouvelle génération |
| Agent | réussite vérifiée, tests de tâche, nombre d'appels, temps, tokens entrée/sortie |
| Sécurité | accès hors racine, réseau interdit, mutation MCP : zéro réussite non autorisée |

Le comptage de production reste identifié comme estimation si TokenEstimator est utilisé. Pour le benchmark, figer un tokenizer de référence et mesurer la réponse réellement sérialisée, descriptions d'outils incluses séparément. Pour une session réelle, utiliser les compteurs fournis par le client quand disponibles ; sinon déclarer la limite. Ne pas convertir tokens en euros sans tarif et modèle effectivement utilisés.

## 5. Gates proposés à valider en U0

Seuils de décision, pas performances actuelles :

- **G0 baseline** : corpus, contrat et machine de référence approuvés ; aucun gain annoncé avant mesures.
- **G1 retrieval** : nDCG@10 global amélioré d'au moins 5 % relatif OU réduction d'au moins 20 % des tokens à recall comparable ; pas de perte supérieure à 2 points absolus Recall@10 sur une catégorie/langue suffisamment représentée.
- **G2 contexte** : à budget identique, Recall@10 ne baisse pas de plus de 2 points ; réduction d'au moins 20 % des tokens médians à recall comparable ; citations/provenance conservées dans 100 % des fixtures contractuelles.
- **G3 ressources** : p95 chaud <= 1,20 × baseline et RSS total <= 1,20 × baseline sur chaque taille commune, sauf gain qualité explicitement accepté ; prototype léger visé sans GPU. Les plafonds absolus du poste sont fixés avant le spike, pas après.
- **G4 fraîcheur** : aucun mélange de générations sur la suite de concurrence ; après fin de synchronisation le nouveau contenu est visible, les suppressions absentes. Cible indicative <= 5 s pour détecter un changement isolé ; durée d'indexation mesurée séparément.
- **G5 MCP** : outils legacy inchangés en profil par défaut ; zéro indexation, téléchargement ou mutation source provoqué par une requête de lecture.
- **G6 IDE** : 100 % des fixtures de résolution ciblées correctement résolues OU explicitement indisponibles ; taux de couverture publié et gain de résolution mesuré face au socle. Une indisponibilité généralisée ne vaut pas succès produit.
- **G7 promotion** : intégration, compatibilité, rollback, packaging et gates de la branche réellement passés ; une plateforme NOT_RUN ne peut être dite qualifiée.

Calculer des intervalles de confiance par bootstrap apparié, avec regroupement par dépôt pour limiter la fausse indépendance. Si l'incertitude rend le gain indécidable, étendre le jeu ou conserver le profil existant. Ne pas multiplier les réglages sur le holdout.

## 6. Ablations obligatoires

Même corpus et même découpage : lexical actuel, BM25 seul, vectoriel seul, BM25 + vecteur, ajout graphe, RRF, routage, déduplication. Puis comparer les découpages à classement fixe. Le bonus graphe doit être comparé avec/sans pour détecter le biais des utilitaires très connectés. Tester des requêtes visant tests, migrations et legacy avant toute pénalisation de ces fichiers.

Pour le modèle léger : comparer qualité FR/EN, CPU et mémoire à Ollama configuré, pas à un modèle fictif. Le mode local-hash est une baseline technique, pas un modèle learned de référence qualité.

## 7. Scénarios adverses et de fraîcheur

Modification, suppression, renommage, changement de branche, rollback de snapshot, changement de modèle/dimensions, cache corrompu, source modifiée pendant lecture, watcher overflow, crash pendant publication, embeddings indisponibles, IDE fermé/dumb mode, buffer non sauvé, déconnexion bridge, jeton invalide, symlink/junction hors racine, fichier géant et instructions malveillantes dans commentaires.

Critères : refus/fallback documenté, pas de fuite, pas de promotion partielle, snapshot valide précédent conservé, arrêt sans processus orphelin.

## 8. Rapport attendu

Une synthèse par candidat : manifestes, commandes exactes, mesures brutes assainies, écarts et intervalles, cas échoués, limitations, verdict PASS/FAIL/NOT_RUN/INCONCLUSIVE, décision de garder/changer le défaut, signature de revue et SHA testé. Publier les résultats négatifs aussi.

Le banc existant benchmarks/scalability est Windows/PowerShell et mesure un autre axe. Le réutiliser pour l'empreinte des snapshots ; ne pas le déclarer portable ni utiliser ses anciennes mesures comme baseline du nouveau code.
