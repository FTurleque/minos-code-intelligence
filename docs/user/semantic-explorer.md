# Explorer l'index sémantique dans un navigateur

`minos explorer serve` ouvre, sur le poste, une page web **en lecture seule** qui liste les documents de l'index
sémantique d'un projet, montre le contenu indexé de chacun à côté de sa source actuelle, lance une recherche et
trouve les documents voisins d'un document. Rien n'est indexé, vectorisé ni modifié par cette page : l'index se
construit toujours par `minos semantic-index-sync <projet>`.

La surface est optionnelle. Tant que la commande n'est pas lancée, MINOS n'écoute sur aucun port ; le serveur MCP
reste en STDIO.

## Matrice de support

| Usage | État |
|---|---|
| Liste, détail, voisins, recherche — installation native, stockage local | Livré, testé automatiquement sous Windows et Linux ; **parcours dans un navigateur à valider par le propriétaire** |
| Idem — installation native, PostgreSQL externe | Lectures testées contre PostgreSQL/pgvector ; parcours complet non qualifié |
| Service `minos-explorer` du runtime Docker connecté | Démarrage, publication du port et contrôles d'accès relevés sur Docker Desktop (Windows) avec un `MINOS_HOME` vide ; **non qualifié avec des données réelles, PostgreSQL géré ni un hôte Linux** |
| Runtime Docker hors ligne (`compose-mcp.prod.yaml`) | Non supporté : aucun service d'exploration |
| Poste natif dont le backend MCP est Docker | Refusé par la commande, qui renvoie vers le service Docker |
| Mode équipe (`MINOS_HOSTED_MODE=enabled`) | Refusé : la surface n'a pas d'autorisation par projet |
| Accès depuis une autre machine, TLS, comptes utilisateurs | Non supporté, sans cible |
| Carte 2D des embeddings | Non livrée (changement séparé) |

## Démarrer

```bash
minos explorer serve
```

La commande écrit sur la sortie d'erreur l'adresse à ouvrir, puis sert jusqu'à `Ctrl+C` :

```text
MINOS explorer is listening on port 51234 (loopback only), read-only. Stop it with Ctrl+C.
Open this address in a browser of this machine; it carries the session token, do not share it:
http://127.0.0.1:51234/#token=…
```

- Le port est choisi par le système. `--port <n>` (0 à 65535) en impose un ; c'est la seule option.
- L'adresse porte le **jeton de la session** dans son fragment (`#token=…`). Le navigateur ne l'envoie à aucun
  serveur et la page le retire de la barre d'adresse dès son chargement. Il vaut pour cette exécution seulement :
  relancer la commande en crée un autre.
- Sans ce jeton, la page affiche « Accès refusé ». Rouvrez l'adresse imprimée par la commande.

Codes de sortie : `0` arrêt demandé, `1` port indisponible, `2` erreur d'usage ou démarrage refusé.

## Ce que montre la page

- **En-tête** : projet, état de l'index (`READY`, `STALE`, `MISSING`, `DISABLED`, `NO_ACTIVE_SNAPSHOT`), provider,
  modèle, dimensions, snapshot indexé et snapshot actif. Un bandeau nomme la commande à lancer quand l'index
  n'est pas prêt.
- **Liste** : les documents par clé stable, 50 par page (« Page suivante »). Nature `CHUNK` (extrait de source),
  `SYMBOL` (synthèse du symbole) ou `FILE` (résumé du fichier), fichier, symbole, module, langage, lignes.
- **Filtres** : nature, module, langage, préfixe de fichier. Un filtre retire des lignes ; il ne change ni un
  score ni un rang. Module et langage viennent du snapshot actif : si l'index n'est pas aligné sur lui, ils
  sont « non résolus » et un filtre sur ces champs ne retient rien.
- **Recherche** : mode sémantique (similarité cosinus, de −1 à 1) ou hybride (score de 0 à 1 : sémantique 50 %,
  lexical 35 %, graphe 15 %). Le classement et les scores sont ceux de `semantic-search` et de la recherche
  hybride ; les limitations rendues par le service sont affichées telles quelles. La recherche exige un index
  `READY`.
- **Détail** : le *contenu indexé* (le texte qui a été vectorisé) et, dans un bloc séparé, la *source actuelle*
  relue sur le disque aux mêmes lignes. La page dit si elles sont identiques, si la source a changé depuis
  l'indexation, ou si le fichier est introuvable. Pour `SYMBOL` et `FILE`, le contenu indexé est une synthèse :
  il ne se compare pas à la source.
- **Documents similaires** : les voisins d'un document, classés par similarité cosinus avec son vecteur
  **stocké**. Aucun appel au provider d'embeddings : l'action fonctionne même s'il est arrêté.

Un score est un signal de classement heuristique, pas un fait structurel : la proximité de deux documents n'est
ni un appel ni une dépendance.

Avec le provider `local-hash`, un badge permanent rappelle que l'index est une référence déterministe (hachage de
tokens) et non le produit d'un modèle de langage (limite `LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL`).

## Ce que la surface refuse

| Situation | Réponse |
|---|---|
| Requête d'API sans le jeton de session | `401`, sans détail |
| En-tête `Host` autre que `127.0.0.1:<port>`, `localhost:<port>` ou `[::1]:<port>` | `403` |
| Requête venant d'une autre origine (`Origin` étranger) | `403`, aucun en-tête CORS |
| Méthode d'écriture (`PUT`, `PATCH`, `DELETE`) | `405` : aucune route n'écrit |
| Curseur de liste émis pour un index remplacé depuis | `409` : la lecture reprend à la première page |
| Plus de 4 requêtes d'API simultanées, ou requête de plus de 30 s | `503` |
| Connexion depuis une autre machine | refusée : l'écoute est limitée à `127.0.0.1` et `::1` |

Aucune option n'élargit l'écoute. Aucune réponse, erreur ni ligne de journal ne contient la racine absolue d'un
projet. Le contenu indexé est affiché comme texte, jamais interprété comme HTML ; la page ne charge aucune
ressource d'un autre site.

Le jeton protège d'une page web tierce et d'un autre processus qui ne le connaît pas. Il ne protège pas d'un
programme qui lit la sortie d'erreur de la commande ou la mémoire du navigateur : c'est la limite d'une surface
locale mono-utilisateur.

Un processus du poste peut rendre la surface indisponible sans jeton : des connexions qui n'achèvent jamais
leur requête occupent ses fils. La surface les coupe au bout de 10 secondes et répond de nouveau seule, mais une
requête arrivée pendant ce temps peut être coupée avec elles ; il suffit de la relancer.

Utilisez l'adresse `127.0.0.1` imprimée par la commande, pas `localhost` : avec un port imposé par `--port`,
la surface refuse de démarrer si un autre processus tient déjà ce port sur `::1`, mais elle n'écoute pas sur
`::1` quand la machine n'a pas cette adresse.

## Runtime Docker

Le fichier Compose du runtime connecté déclare un service optionnel `minos-explorer` (profil `explorer`) :
image de requête, données montées en lecture seule, port publié sur `127.0.0.1` de l'hôte seulement. Voir
[Runtime Docker](docker-runtime.md#service-optionnel-minos-explorer). Ce service n'est pas encore qualifié sur
une installation réelle.

## Dépannage

| Symptôme | Cause | Action |
|---|---|---|
| « Accès refusé » | adresse ouverte sans son fragment `#token=…`, ou commande relancée depuis | rouvrir l'adresse imprimée par la commande en cours |
| `error: … the team mode is enabled` | `MINOS_HOSTED_MODE` ou `minos.hosted.mode` actif pour ce `MINOS_HOME` | la surface n'est pas disponible en mode équipe |
| `error: this MINOS home runs on the Docker backend` | le lanceur de ce poste est basculé sur Docker | utiliser le service `minos-explorer`, ou repasser en backend natif |
| `error: the exploration surface could not listen on the requested port` | port déjà pris | autre `--port`, ou aucun pour un port libre |
| `error: MINOS_EXPLORER_CONTAINER_LISTEN is reserved to the MINOS Docker runtime` | variable interne du runtime Docker posée sur un poste | la retirer de l'environnement |
| Bandeau « L'index a changé pendant la consultation » | l'index a été reconstruit pendant la lecture | la liste reprend d'elle-même à la première page |
| Recherche en erreur, liste disponible | provider d'embeddings injoignable | vérifier le provider ; liste, détail et voisins restent disponibles |
| Colonnes module et langage « non résolu » | index `STALE` : il ne décrit plus le snapshot actif | `minos semantic-index-sync <projet>` |

Contrat de l'API HTTP et construction : [surface d'exploration](../developer/semantic-explorer.md).
