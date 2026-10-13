# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-11** (Q18 de l'audit de septembre, toujours ouvert). HEAD analysé : `949e1569` (`develop`).

## État vérifié au HEAD

### AUD-QUA-11 — confirmé, décomptes légèrement différents

- Commande de l'audit (lignes de commentaire contenant une lettre accentuée, dans `minos-*/src/main`) : **173** fichiers sur **553** (170 sur 547 annoncés). Fichiers mixtes par l'heuristique de l'audit (présence, en plus, de mots anglais courants dans des commentaires) : **26** (27 annoncés). Ces chiffres sont repris de la contre-vérification faite au HEAD ; la commande n'a pas été rejouée pendant cette rédaction.
- Par module : `minos-engine` 61/169, `minos-application` 49/108, `minos-domain` 19/37, `minos-provider-scip` 14/41, `minos-cli` 13/39 ; `minos-api`, `minos-nexus`, `minos-integration-git`, `minos-storage-postgresql` : 0.
- `CONTRIBUTING.md` : aucune occurrence de « langue », « français », « anglais » (recherche faite). `AGENTS.md` porte la seule règle : identifiants et messages de code suivent la convention du module touché, Javadoc sans mélange de langues dans un même fichier.
- Baselines sensibles aux lignes (relu) : `scripts/architecture/private-io-allowlist.json` indexe ses entrées par fichier, méthode et plafond, **sans numéro de ligne** ; les fichiers de `quality/spotbugs-baseline/` portent des plages de lignes (`SourceLine start/end`).

**Non vérifié** : si la comparaison à la baseline SpotBugs tient compte des lignes ; si un gate littéral exige une chaîne située dans un commentaire d'un des fichiers mixtes ; aucun build, test ni gate exécuté.

### Écarts avec l'action proposée par l'audit

1. **L'heuristique de l'audit n'est pas reproductible à l'unité près** (26 contre 27) : une liste de mots courants ne fait pas un critère de gate. Le gate a besoin d'une règle déterministe, écrite et testée (D2).
2. **« Fixer la langue par module » suppose un choix que les chiffres ne font pas** : `minos-domain` a plus de la moitié de ses fichiers avec du français, `minos-api` aucun. C'est une décision (D1).
3. **`CONTRIBUTING.md` n'est pas le seul texte à tenir** : `AGENTS.md` énonce déjà une règle ; les deux doivent dire la même chose.
4. **Retoucher un commentaire déplace des lignes** : sans effet sur la liste du garde d'E/S privées ; effet sur la baseline SpotBugs à établir avant le premier lot (tâche 1.2).
5. **Coût SonarCloud nul** : des commentaires ne sont pas du code à couvrir.

## Goals / Non-Goals

**Goals** : une règle écrite, lisible par un contributeur et par un gate ; plus aucun nouveau fichier mixte ; les fichiers mixtes existants résorbés sans campagne.

**Non-Goals** : traduire le code ; juger la qualité des commentaires ; détecter la langue par un modèle ou une dépendance.

## Decisions

### D1. Langue de référence par module — **décision en attente du propriétaire**

**Option A (recommandée) : anglais pour les commentaires et la Javadoc de tous les modules de code**, plugin compris. Une seule règle, déjà vraie pour `minos-api` et pour la majorité des fichiers des modules chiffrés ci-dessus, sauf `minos-domain` ; les artefacts du dépôt (docs, ADR, OpenSpec, commits, PR) restent en français, comme l'écrit `AGENTS.md`. Les fichiers entièrement en français restent tolérés : ils ne sont pas mixtes, et ils basculent quand on les retouche.

**Option B : langue par module**, d'après la majorité au HEAD (français pour `minos-domain`, anglais ailleurs) ou d'après un choix du propriétaire. Respecte l'existant, au prix d'une table à tenir et d'une règle que personne ne retient.

**Option C : français partout sauf `minos-api`.** Aligné sur la langue des artefacts ; le plus gros volume à reprendre à terme.

Dans tous les cas, la langue de chaque module est déclarée dans **un seul fichier** lu par le gate ; sous A, tous les modules y portent la même valeur.

### D2. Critère « mixte » du gate

Le gate ne lit que les commentaires (`//`, blocs, Javadoc), après retrait des littéraux et du code — même découpage que celui dont se sert déjà `scripts/architecture/check-private-io.py`. Un commentaire est tenu pour français s'il contient une lettre accentuée ou un mot d'une liste fermée française ; pour anglais s'il contient un mot d'une liste fermée anglaise. Un fichier est **mixte** s'il a au moins un commentaire de chaque sorte. Les deux listes sont courtes, écrites dans le gate, et couvertes par son auto-test (un cas par mot ambigu rencontré en tâche 1.1 : noms propres, termes techniques, citations de code entre accolades Javadoc exclues).

Le critère est volontairement grossier : il ne décide pas de la langue d'un fichier, seulement de son mélange.

### D3. Liste décroissante, fail-closed

`scripts/quality/check-comment-language.py` (nouveau, `--self-test`, câblé dans `invariants`) et `scripts/quality/comment-language.json` (nouveau : langue par module, liste des fichiers mixtes tolérés). Le gate échoue si : un fichier mixte n'est pas listé ; un fichier listé est introuvable ; un fichier listé n'est plus mixte (l'entrée doit être retirée) ; un module du reactor n'a pas de langue déclarée. La liste initiale est le relevé de la tâche 1.1, pas les 26 de l'audit.

### D4. Reprise à la main, par lots relisibles

Les fichiers mixtes sont repris par lots de quelques fichiers par PR, commentaires seulement, un module à la fois. Aucun outil de traduction n'est appliqué en masse. Un commentaire dont le sens est incertain est gardé et signalé plutôt que réécrit.

### D5. Les deux textes disent la même chose

`CONTRIBUTING.md` reçoit la règle ; la phrase de `AGENTS.md` sur la Javadoc est alignée dans la même tâche (et `.claude/rules/` si une règle Java la reprend).

## Direction des dépendances

Sans objet : aucun import, aucun module (ADR 0022 non concerné).

## Qualification des capacités

Sans objet ; aucune capacité produit.

## Windows et Linux

Le gate ouvre les sources en UTF-8 explicite : la page de code par défaut d'un poste Windows ne doit pas changer le décompte. L'auto-test contient un cas à lettre accentuée pour le prouver sur les deux systèmes.

## Risks / Trade-offs

- **Faux positifs et faux négatifs du critère** : un commentaire français sans accent ni mot de la liste passe ; un nom propre accentué dans un commentaire anglais déclenche. Assumé : la liste tolérée absorbe les cas existants, l'auto-test fige les cas connus.
- **Baseline SpotBugs** : si la comparaison est sensible aux lignes, chaque lot doit régénérer la baseline du module ou garder le nombre de lignes des commentaires repris. À trancher par la mesure 1.2, sans masquer d'alerte.
- **Gates littéraux** : un commentaire peut porter une chaîne exigée par un `check-*.py` ; chaque lot rejoue tous les gates.
- **Sous l'option A**, la règle écrite et l'état du dépôt divergent tant que les fichiers français ne sont pas repris : le dire dans `CONTRIBUTING.md` (« règle pour tout commentaire nouveau ou modifié »).
