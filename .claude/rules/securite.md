# Sécurité (règle transversale)

MINOS indexe du code qui n'est pas de confiance et gère des secrets de plan de contrôle. Toute modification qui touche l'une de ces surfaces se relit avec cette liste ; le skill `minos-security-checklist` et l'agent `minos-security-reviewer` la déroulent.

1. **Fail-closed.** Un contrôle qui ne peut pas être fait échoue ; il ne se dégrade pas. Le code non fiable est confiné par le bac à sable OS ou refusé.
2. **Secrets.** Jamais en clair dans le code, les journaux, les messages, les fichiers à ACL héritée ni l'environnement d'un processus externe. Clés : `MINOS_TEAM_KEY_*`, `MINOS_TEAM_TOKEN`, `MINOS_REMOTE_TOKEN*`, mots de passe PostgreSQL (fichier restreint), jetons de CI. Gitleaks est un check exigé.
3. **Environnement des processus.** Liste d'autorisation (`ProviderProcessEnvironment` à l'indexation, `InstallationProcessEnvironment` pour les commandes d'installation) ; les motifs `MINOS_*`, `*TOKEN*`, `*SECRET*`, `*PASSWORD*`, `*KEY*`, `*CREDENTIAL*`, `GITHUB_*`, `NPM_*` sont exclus même s'ils figurent dans une liste ou parmi les variables déclarées par un plan (motif unique : `ProviderProcessEnvironment.isDeniedName`).
4. **Intégrité de ce qui est exécuté.** Un outil téléchargé est vérifié par empreinte SHA-256 épinglée au catalogue (ADR 0065) **avant** d'être exécuté ; le catalogue décrit versions et empreintes, jamais le serveur.
5. **Dépôts analysés.** Un dépôt Git n'est pas de confiance. `GitIntelligenceService` contrôle le propriétaire du dépôt (`requireExpectedOwner`) et redirige tout pilote `filter.*` qu'il déclare vers `PassThroughFilter` : aucun filtre du dépôt n'est exécuté (`GitIntelligenceTrustTest`). Tout nouvel appel JGit qui lit l'index ou le contenu d'un dépôt analysé passe par cette ouverture, jamais par une ouverture directe.
6. **E/S privées.** Création, écriture, lecture et verrouillage uniquement par les primitives confinées (`check-private-io.py`) ; permissions privées ; publication atomique sans écrasement d'une cible existante.
7. **Sorties.** Aucun chemin absolu, e-mail d'auteur, cause interne ni HTML non échappé dans une sortie publique ou un journal ; erreurs d'usage actionnables, erreurs internes opaques.
8. **Réseau.** Un provider a une politique d'egress `DENY` ; les téléchargements d'outils sont faits par MINOS, jamais par un provider, et vérifiés par l'empreinte SHA-256 du catalogue (ADR 0065). TLS PostgreSQL : `verify-full` reconnu à l'identique par le pilote.
9. **Windows et Linux.** Chaque garantie vaut sur les deux (AppContainer / Job Object d'un côté, bubblewrap / cgroup v2 de l'autre) ; une absence de confinement échoue en CI.
10. **Chaîne d'approvisionnement.** Actions épinglées par SHA, images par digest, dépendances auditées (OSV), SBOM CycloneDX de release ; pas de nouvelle dépendance sans raison écrite.

Une vulnérabilité s'annonce selon `SECURITY.md` ; ne pas ouvrir d'issue publique avec les détails d'une faille.
