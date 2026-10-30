---
description: Rédiger un ADR (numéro, gabarit, index, références) ou en compléter la mise en œuvre
argument-hint: "<titre de la décision | NNNN pour compléter un ADR existant>"
---

Rédige ou complète un ADR pour : `$ARGUMENTS`. Un ADR documente un choix **structurant et durable** (frontière de modules, modèle de confiance, format persistant, protocole public, politique de sécurité, toolchain), pas un correctif local. Pendant la **spécification** OpenSpec, un ADR est seulement nommé et planifié en tâche : il s'écrit à l'implémentation.

0. **Filtre** : appliquer la section « Quand écrire un ADR » de `docs/architecture/adr/README.md` (décision entre options réelles, portée durable, coût du retour arrière). Si une réponse manque, conclure « Aucun nouvel ADR nécessaire » et orienter le contenu (historique, roadmap, OpenSpec, étude, règle) ; si un ADR actif couvre déjà la décision, le citer. Une modification partielle d'un ADR actif donne un ADR consolidé qui le remplace ; l'ancien part dans `archive/`.
1. Lire `docs/architecture/adr/README.md` : dernier numéro (l'index fait foi), statuts (`Proposed | Accepted | Superseded | Rejected`), ADR voisins à citer ; réunir les faits à l'appui (code relu `fichier:ligne`, mesures, gates).
2. **Nouvel ADR** → copier `docs/architecture/adr/gabarit/template.md` en `docs/architecture/adr/{PRÉFIXE}-{NNNN}-titre-en-kebab-case.md` (préfixe du domaine : `ARCH`, `BE`, `DATA`, `GEN`, `SEC` ; numéro suivant de l'index), en français, statut `Proposed` (format et convention Archgate, GEN-0073) :
   - en-tête YAML en **ligne 1** : `id: {PRÉFIXE}-{NNNN}` (début du nom de fichier), titre `#` égal au `title`, `title`, `domain` (`architecture`, `backend`, `data`, `general`, `security`), `rules: false` (`true` seulement avec un compagnon `{même nom}.rules.ts` (identifiants de règle en kebab-case anglais, `files` si la règle ne regarde qu'une partie du dépôt) vu en échec sur une violation) ;
   - sections `Context`, `Decision`, `Do's and Don'ts` (`Do`, `Don't`, tirés du seul texte de la décision), `Consequences`, `Compliance and Enforcement` (contrôles qui existent vraiment), `Mise en œuvre`, `References` ;
   - relations (complète, amende, remplace) écrites dans les deux ADR ;
   puis ajouter la ligne à l'index (`| [NNNN](…) | Décision | Statut | Origine |`) et à `docs/architecture/arc42/09-decisions.md`. Vérifier `archgate adr list` : le nouvel ADR y figure. Ne jamais utiliser `archgate adr create`.
3. **ADR existant** → ne compléter que « Mise en œuvre » (ou « Écarts constatés » pour un écart avec le code, daté et prouvé, sans toucher à la décision) ; pour changer la décision, écrire l'ADR qui la remplace et passer l'ancien à `Superseded` (ou « Partially superseded by ADR-NNNN »). Le hook demande confirmation avant de modifier un ADR existant ; un nouvel ADR se crée librement.
4. Ne **pas** passer un ADR à `Accepted` sans décision explicite de l'utilisateur, avec date.
5. Si la décision change une règle gardée (frontières, E/S, supply-chain), mettre à jour le gate et `docs/developer/quality-gates.md` dans le même changement.
6. Rejouer `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` ; vérifier les liens.

ADR de référence : 0017 (MCP lecture seule), 0022 (reactor), 0040 (indexeurs embarqués), 0041 (indexation distante non fiable), 0042 (racine de composition), 0043 (retrait des artefacts de jalon), 0044 (un package, un module), 0057 et 0058 (frontières hexagonales).
