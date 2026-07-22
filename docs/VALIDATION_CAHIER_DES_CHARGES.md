# Validation du cahier des charges — MINOS

Date de validation : 22 juillet 2026

Statut : **Validé**

Le cahier des charges de MINOS, documenté dans [`CAHIER_DES_CHARGES.md`](CAHIER_DES_CHARGES.md), est validé comme base fonctionnelle et technique de haut niveau du projet.

Cette validation confirme notamment :

- le positionnement de MINOS comme couche de **Code Intelligence** ;
- la séparation des responsabilités entre MINOS et NEXUS ;
- la place envisagée de MINOS dans l'écosystème JARVIS / NEXUS / Alfred / Brainiac ;
- les principes local-first, multi-langages, agnostique de l'indexeur et orienté preuves ;
- la réutilisation prioritaire de briques open source matures plutôt que leur réimplémentation systématique ;
- l'objectif d'exposer une connaissance structurée, compacte et exploitable par les agents IA ;
- les capacités fonctionnelles cibles et les extensions futures décrites dans le cahier des charges.

## Ce que cette validation ne clôt pas

La validation du cahier des charges ne signifie pas que toutes les décisions d'architecture sont acceptées.

Restent notamment à traiter pendant C0 :

- l'acceptation ou le rejet des ADR structurantes ;
- le rôle définitif de SCIP ;
- le rôle définitif de Glean ;
- le contrat `CodeKnowledgeStore` ;
- la stack technique initiale ;
- le périmètre final du MVP et sa priorisation ;
- les critères mesurables définitifs ;
- le plan détaillé des expérimentations M0.

La phase **C0 — Cadrage fonctionnel et architectural** reste donc ouverte jusqu'à validation de ces éléments.
