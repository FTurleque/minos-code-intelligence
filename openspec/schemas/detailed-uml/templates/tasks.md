# Tasks

<!-- Rédiger en français. Format suivi par apply : groupes « ## <n>. <nom> », tâches
     « - [ ] <n>.<m> <description> ». Seule une case contenant x compte comme faite.
     Chaque tâche : ce qui est fait, exigences couvertes (capacité › titre), comment vérifier.
     Dans chaque groupe : rouge, puis correction, puis preuve ; tests et documentation dans le
     groupe qui les exige. Tâche humaine : **(manuelle)**. -->

## 1. <!-- premier lot cohérent, ordonné par dépendance -->

- [ ] 1.1 <!-- Rouge : écrire le test ou le gate qui échoue sur <comportement> (capacité › titre) ; vérifier qu'il échoue pour la bonne raison -->
- [ ] 1.2 <!-- Correction : <changement> ; vérifier que le test 1.1 passe ; rejouer <check-*.py / scope JaCoCo> -->
- [ ] 1.3 <!-- Documentation : mettre à jour <diagramme du dossier de capacité / page docs/ / vue arc42> ; vérifier les liens -->

## 2. <!-- lot suivant -->

- [ ] 2.1 <!-- ... -->

## 3. Clôture

- [ ] 3.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 3.2 Mettre à jour le dossier durable de chaque capacité touchée ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [ ] 3.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [ ] 3.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, docs/user, docs/developer, arc42, ADR) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive <change> -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
