# Audit de cohérence C0 — MINOS

Date : 19 juillet 2026

Statut : **Audit de cadrage**

## 1. Objectif

Cet audit vérifie que le dépôt MINOS reste cohérent avec la vision et les décisions exprimées pendant les échanges de cadrage ayant précédé l'implémentation.

Il ne valide pas encore les choix techniques. Il vérifie que les sujets discutés sont correctement représentés dans la documentation et que les décisions encore ouvertes ne sont pas présentées comme définitives.

---

## 2. Points confirmés comme cohérents

### Identité

- le projet s'appelle **MINOS** ;
- les anciens noms ATLAS et DAEDALUS ne sont plus utilisés comme nom du projet ;
- CEREBRO n'est pas attribué à MINOS et reste une question extérieure au projet.

### Positionnement

- MINOS = **Code Intelligence** ;
- NEXUS = **Context Intelligence** ;
- JARVIS = orchestration envisagée ;
- Alfred et Brainiac = agents ou profils spécialisés ;
- MINOS reste autonome.

### Philosophie

- local-first ;
- pas de dépendance obligatoire à un LLM ;
- pas de dépendance obligatoire au cloud ;
- résultats structurés et compacts ;
- priorité à la précision ;
- résultats heuristiques explicables ;
- réutilisation de briques open source avant réimplémentation.

### Multi-langages

- le cœur MINOS est agnostique du langage ;
- Java, TypeScript et Python ne constituent pas une liste fermée ;
- les fournisseurs sont sélectionnés selon leurs capacités ;
- SCIP est une piste privilégiée mais non obligatoire.

### Glean

- Glean est un backend privilégié à évaluer ;
- Glean n'est pas le domaine MINOS ;
- `CodeKnowledgeStore` isole le domaine du backend ;
- la stratégie reste proposée et non acceptée définitivement.

### Fonctionnalités

Le cahier des charges couvre désormais :

- registre des projets ;
- workspaces ;
- découverte du dépôt ;
- `.gitignore` et `.minosignore` ;
- symboles ;
- relations ;
- usages ;
- implémentations ;
- appels ;
- dépendances ;
- tests liés ;
- recherche structurée ;
- vue d'architecture ;
- analyse d'impact ;
- contexte compact ;
- indexation incrémentale future ;
- Git Intelligence future.

### Exposition

- CLI ;
- MCP ;
- API ;
- intégration future avec NEXUS ;
- consommation possible par JARVIS, Alfred et Brainiac ;
- utilisation possible par des skills IA sans que MINOS gère le registre de skills.

### Qualité

- stratégie de tests et fixtures ;
- métriques d'indexation et de requêtes ;
- métriques de précision ;
- métriques liées à l'efficacité pour les agents IA ;
- distinction entre faits, dérivations et heuristiques.

---

## 3. Corrections effectuées pendant l'audit

### Vue d'écosystème

La documentation ne contenait initialement qu'une vue simplifiée :

```text
Codebase → MINOS → NEXUS → Agent
```

La vue globale discutée a été réintroduite :

```text
                       JARVIS
                    Orchestration
                         │
            ┌────────────┴────────────┐
            │                         │
            ▼                         ▼
          NEXUS                     MINOS
   Context Intelligence       Code Intelligence
            │                         │
            └────────────┬────────────┘
                         ▼
                 ALFRED / BRAINIAC
```

### ECOSYSTEME.md

Le document était incomplet après la présentation de NEXUS.

Il a été complété avec :

- JARVIS ;
- MINOS ;
- NEXUS ;
- Alfred ;
- Brainiac ;
- AI Skills Registry ;
- règles d'autonomie ;
- statut non défini de CEREBRO.

### Cahier des charges

Plusieurs sujets discutés étaient dispersés dans la roadmap mais insuffisamment présents dans la source de vérité.

Ils ont été réintégrés dans `CAHIER_DES_CHARGES.md`, notamment :

- recherche structurée générale ;
- relations factuelles enrichies ;
- indexation incrémentale ;
- Git Intelligence ;
- stratégie de tests ;
- fixtures ;
- métriques ;
- métriques IA ;
- API conceptuelle ;
- AI Skills Registry ;
- décisions de stack ouvertes.

### Stack technique

Un `pom.xml` fixait prématurément Maven et Java 25.

Il a été retiré pendant C0 afin de conserver la cohérence avec la règle :

> **Documenter d'abord, décider ensuite, implémenter en dernier.**

La stack Java/Maven reste une orientation forte à étudier, mais n'est pas encore une décision acceptée.

### Langue de la documentation

La documentation principale est en français.

Les identifiants techniques standards peuvent rester en anglais lorsque cela améliore l'interopérabilité ou la lisibilité des contrats :

```text
find_symbol
CodeKnowledgeStore
IndexerProvider
RESOLVED
```

---

## 4. Décisions encore ouvertes

L'audit confirme que les sujets suivants ne doivent pas encore être considérés comme décidés :

- adoption définitive de SCIP ;
- adoption définitive de Glean ;
- rôle exact de `CodeKnowledgeStore` ;
- stratégie de stockage des métadonnées MINOS ;
- protocole Java ↔ Glean ;
- langages de validation du MVP ;
- version Java cible ;
- choix définitif de Maven ;
- framework éventuel de l'API ;
- granularité de l'indexation incrémentale ;
- format d'identité stable des symboles ;
- niveau de recherche générique inclus dans le MVP ;
- critères précis d'acceptation des indexeurs ;
- rôle futur éventuel de CEREBRO dans l'écosystème global.

---

## 5. Documents de référence après audit

Ordre de lecture recommandé :

1. `README.md` — présentation synthétique ;
2. `docs/CAHIER_DES_CHARGES.md` — source de vérité du cadrage ;
3. `docs/ECOSYSTEME.md` — positionnement dans l'écosystème ;
4. `docs/architecture/overview.md` — architecture interne candidate ;
5. `docs/MVP.md` — proposition de MVP ;
6. `docs/PLAN.md` — plan de travail C0/M0 ;
7. `docs/ROADMAP.md` — trajectoire envisagée ;
8. `docs/adr/` — décisions proposées ;
9. `docs/research/` — études techniques.

---

## 6. Conclusion

Après corrections, le dépôt est globalement aligné avec la vision discutée.

La principale règle à maintenir est que les documents de recherche et ADR ne doivent pas transformer une hypothèse en décision avant validation C0.

La prochaine étape recommandée n'est pas l'implémentation :

> **valider le cahier des charges section par section, prioriser les cas d'usage du MVP et accepter ou rejeter les ADR structurantes avant de lancer M0.**
