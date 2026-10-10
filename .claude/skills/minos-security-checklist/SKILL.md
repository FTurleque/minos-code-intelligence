---
name: minos-security-checklist
description: Passer en revue un changement MINOS sous l'angle sécurité — confinement du code non fiable, secrets et environnement des processus, intégrité des outils exécutés, dépôts analysés, E/S privées, fuites dans les erreurs et journaux, réseau, Windows et Linux, supply-chain. À utiliser pour toute modification touchant runtime-local, providers, intégration Git, installation d'outils, scripts d'installation, MCP, plan de contrôle hébergé, stockage ou journalisation, ou quand l'utilisateur demande une revue de sécurité.
---

# Revue de sécurité MINOS

Règle de fond : `.claude/rules/securite.md`. Cette liste sert à **trouver** les problèmes ; chaque constat porte `fichier:ligne`, scénario d'abus concret, sévérité et correction, et dit s'il a été reproduit ou seulement lu.

## Questions par surface

**Processus lancés par MINOS** (`ProcessBuilder`, `Runtime.exec`) : quel environnement reçoivent-ils (`environment().clear()` ou liste) ? Un secret `MINOS_*`/`*TOKEN*` y passe-t-il ? Le répertoire courant est-il un projet non fiable (résolution d'un exécutable dans le cwd) ? Le processus est-il dans un Job Object / cgroup propriétaire, avec délai et destruction de l'arbre ?

**Code téléchargé ou exécuté** : l'empreinte SHA-256 est-elle épinglée au catalogue et vérifiée **avant** exécution ? Un miroir ou une variable d'environnement (`COURSIER_REPOSITORIES`…) peut-il substituer l'artefact ? Le programme est-il lancé « pour voir sa version » ?

**Dépôts et projets analysés** : JGit/`git` lit-il une configuration, des attributs ou un index que le dépôt contrôle ? Propriétaire contrôlé ? Remontée sans plafond ? Liens symboliques, jonctions, points de réanalyse, chemins hors racine, noms réservés Windows ?

**Fichiers et secrets** : écriture avec ACL/permissions héritées avant restriction ? fenêtre de lecture ? fichier existant accepté sans contrôle ? création/écriture hors des primitives (`check-private-io.py`) ? écrasement d'une cible publiée ?

**Sorties** : chemin absolu, nom d'utilisateur, e-mail d'auteur, cause d'exception, URL avec identifiants dans un message, une erreur MCP, un JSON ou un journal WARNING ? stdout MCP pollué ?

**Réseau et TLS** : egress des providers `DENY` ? téléchargements bornés ? `sslmode=verify-full` effectivement appliqué par le pilote ? redirections vers un hôte hors liste ?

**Hébergé / tenant** : chaîne d'audit HMAC (refus canoniques, bornes en octets), rotation de clés, droits minimaux, refus audités.

**Plateformes** : la garantie vaut-elle sous Windows (AppContainer, Job Object, ACL) **et** Linux (bubblewrap, cgroup v2) ? Un confinement indisponible échoue-t-il en CI au lieu d'être sauté ?

**Supply-chain** : action non épinglée, image sans digest, dépendance ajoutée sans raison, script de release qui télécharge sans empreinte.

## Sortie attendue
Tableau `sévérité | fichier:ligne | problème | scénario | correction | vérifié (reproduit/lu)`, puis les points **non examinés**. Pas de correctif de gouvernance silencieux : une exception de gate se propose, ne s'applique pas. Une vulnérabilité réelle s'annonce selon `SECURITY.md` (canal privé), jamais dans une issue publique détaillée.

Agent associé : `minos-security-reviewer` (lecture seule).
