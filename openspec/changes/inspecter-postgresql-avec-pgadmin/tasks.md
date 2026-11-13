# Tasks

Aucun constat d'audit (`AUD-…`) n'est à l'origine de ce changement. Les exigences sont citées « capacité › titre ». Plan commun : `docs/project/roadmap/explorateur-vectoriel-2026-11/README.md`. Toute commande Maven passe par le wrapper, `-pl` avec `-am`.

## 1. Lot 1 — Inspection pgAdmin (runtime Docker, PostgreSQL géré)

- [ ] 1.1 **(manuelle)** Vérifier sur un poste avec Docker qu'un conteneur attaché au seul réseau `minos-runtime` (interne) ne publie pas de port, et qu'un second réseau non interne le permet ; consigner le résultat dans le design (« État vérifié ») avant d'écrire le service. Limite : résultat propre à la version de Docker relevée.
- [ ] 1.2 Rouge : ajouter à l'auto-test des scripts Docker (`docker/scripts/`) et aux gates de composition les cas « sans profil `pgadmin` : aucun service ni port », « profil activé : un seul port, lié à `127.0.0.1` », « fichier de mot de passe absent : échec nommant la variable » (`distribution-docker` › pgAdmin est un service optionnel publié sur la seule boucle locale) ; vérifier qu'ils échouent parce que le service n'existe pas.
- [ ] 1.3 Correction : service `minos-pgadmin` (profil `pgadmin`), réseau d'entrée dédié, bloc `x-limits-pgadmin`, image épinglée par digest, secrets par fichiers privés, `servers.json` généré hors dépôt dans `docker/compose-mcp.connected.yaml` et `docker/.env.example` ; vérifier que 1.2 passe ; rejouer `scripts/quality/check-compose-limits.py` et `scripts/quality/check-image-pins.py`.
- [ ] 1.4 Rouge : test PostgreSQL (support existant `minos-storage/src/test/java/com/minos/storage/postgresql/PostgresTestSupport.java`) qui applique le script du rôle puis affirme : `DELETE` sur `semantic_documents` refusé après `SET default_transaction_read_only = off`, `SELECT root_value FROM projects` refusé, `SELECT` accordé sur la liste fermée (`distribution-docker` › pgAdmin se connecte par un rôle PostgreSQL limité à la lecture) ; vérifier l'échec faute de script.
- [ ] 1.5 Correction : script SQL du rôle `minos_inspect` (ressource versionnée, sans mot de passe) et action `EnablePgAdmin` de `docker/scripts/mcp-lifecycle.ps1` qui l'applique ; vérifier que 1.4 passe ; scope JaCoCo de `minos-storage` si du code Java est ajouté.
- [ ] 1.6 Rouge : cas d'auto-test « `RemovePgAdmin` supprime conteneur, volume pgAdmin, fichiers privés et rôle ; le volume PostgreSQL et le nombre de lignes de `semantic_documents` sont inchangés » (`distribution-docker` › Retirer pgAdmin ne supprime aucune donnée MINOS).
- [ ] 1.7 Correction : actions `StopPgAdmin` et `RemovePgAdmin` ; vérifier que 1.6 passe ; rejouer `docker/scripts/verify-run-configurations.ps1`.
- [ ] 1.8 Documentation : page `docs/user/` « Inspecter PostgreSQL avec pgAdmin » (reprise de `PGADMIN.md` : deux identités, requêtes de diagnostic, limites), entrée dans `docs/user/README.md` et `docs/user/docker-runtime.md` ; exécuter chaque requête de diagnostic contre une instance réelle et corriger la page ; vérifier les liens.
- [ ] 1.9 **(manuelle)** Ouvrir pgAdmin dans un navigateur sur le poste, exécuter les sept requêtes, relever le résultat. Critère de fin du lot : 1.2, 1.4, 1.6 verts et relevé joint.

## 2. Clôture

- [ ] 2.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 2.2 Mettre à jour le dossier durable de la capacité `distribution-docker` ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [ ] 2.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [ ] 2.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, docs/user, docs/developer, arc42, ADR ARCH-0082 « Mise en œuvre ») ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive inspecter-postgresql-avec-pgadmin -y`), puis vérifier que chaque spec de référence garde sa section `## Documentation liée`.
