# Spec Delta

## ADDED Requirements

### Requirement: Le tableau d'état de la mutation suit la liste des modules du reactor
Le tableau d'état de la mutation du document de couverture SHALL nommer exactement les modules déclarés par le `pom.xml` racine, plus le plugin IntelliJ. Un contrôle exécutable depuis la racine du dépôt SHALL comparer les deux listes et MUST échouer, en nommant le module, quand un module du reactor manque au tableau ou quand le tableau nomme un module qui n'existe plus. Ce contrôle SHALL être exécuté par le job des invariants.

#### Scenario: Dépôt réel après le changement
- **GIVEN** le `pom.xml` racine et `docs/developer/quality/code-audit-couverture.md`
- **WHEN** le contrôle du tableau d'état est exécuté
- **THEN** il réussit : les quatorze modules et `minos-intellij` ont chacun une ligne et un statut

#### Scenario: Module ajouté au reactor sans ligne d'état
- **GIVEN** une copie du dépôt où le `pom.xml` déclare un module absent du tableau
- **WHEN** le contrôle est exécuté sur cette copie
- **THEN** il échoue avec le code 1 et nomme le module manquant

#### Scenario: Module disparu encore nommé
- **GIVEN** une copie du document dont le tableau d'état porte une ligne `minos-storage-local`
- **WHEN** le contrôle est exécuté
- **THEN** il échoue et nomme la ligne qui ne correspond à aucun module

#### Scenario: Tableau d'état introuvable
- **GIVEN** une copie du document sans le tableau d'état
- **WHEN** le contrôle est exécuté
- **THEN** il échoue en disant que le tableau est introuvable, au lieu de réussir sans rien comparer

### Requirement: Une mesure de mutation antérieure à une fusion de modules n'est pas reportée sur le module fusionné
Un module créé ou fusionné depuis la dernière mesure SHALL porter le statut « non exécuté » tant qu'aucun rapport PIT complet n'a été produit pour lui. Les mesures des modules d'origine SHALL rester lisibles comme mesures datées, hors du tableau d'état, et SHALL NOT être additionnées pour tenir lieu de mesure du module fusionné. Un statut « exécuté » SHALL citer l'exécution, son système et le commit muté.

#### Scenario: Module fusionné sans rapport
- **GIVEN** le module `minos-storage`, issu de deux modules mutés avant leur fusion, sans rapport PIT propre
- **WHEN** le tableau d'état est lu
- **THEN** sa ligne porte « non exécuté » et renvoie aux mesures datées des modules d'origine

#### Scenario: Module nouveau sans rapport
- **GIVEN** le module `minos-explorer`, jamais muté
- **WHEN** le tableau d'état est lu
- **THEN** sa ligne porte « non exécuté » et sa raison

#### Scenario: Rapport produit par le workflow d'audit
- **GIVEN** les rapports PIT de `minos-storage` téléchargés d'une exécution de `code-audit.yml`
- **WHEN** `python scripts/quality/audit-report-summary.py pit --module minos-storage` est exécuté
- **THEN** la commande réussit et ses décomptes sont ceux reportés dans le document, avec l'exécution, le système et le commit
