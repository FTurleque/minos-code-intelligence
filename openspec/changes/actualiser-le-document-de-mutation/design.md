# Design

## Contexte

`docs/developer/quality/code-audit-couverture.md` est le relevé de l'audit outillé du 8 octobre 2026, complété le 16
octobre ; il porte la « source unique » de l'état de la mutation par module. Le reactor a changé depuis : stockages
fusionnés en `minos-storage`, module `minos-explorer` créé. Motivation : voir `proposal.md` (Why). Changement
documentaire et d'outillage : sections réduites.

## État vérifié au HEAD

HEAD `89be3fb4`. Lecture des fichiers ; aucune mutation exécutée.

| Fait | Preuve |
|---|---|
| Modules du reactor : domain, engine, runtime-local, storage, provider-scip, integration-git, application, bootstrap, nexus, cli, api, mcp, explorer, app | `pom.xml:24-37` |
| Tableau d'état : quinze lignes, dont `minos-storage-local` (E27) et `minos-storage-postgresql` (E30) ; ni `minos-storage` ni `minos-explorer` | `docs/developer/quality/code-audit-couverture.md`, § 5.2, tableau « Statut PIT par module — source unique » |
| Résultats par module des deux modules disparus | même document, `:243`, `:247` |
| Inventaire daté du 8 octobre qui les nomme aussi | même document, `:34`, `:38` |
| Point de reprise qui cite `-pl minos-storage-local` | même document, `:369-370` |
| Le document dit « les 14 modules du réacteur sont exécutés », avec les décomptes « 11 sur 14 » et « 3 sur 14 » ; la chaîne « modules sur 14 » n'y figure pas | même document, `:266` (phrase d'introduction du tableau d'état) |
| Job de mutation : `workflow_dispatch`, un module par exécution, délai de 120 min, PostgreSQL de test démarré | `.github/workflows/code-audit.yml:6`, `:117-119`, étapes « Start the PostgreSQL test server » et « Muter » |
| Job du plugin : `./gradlew --no-daemon test spotbugsMain pitest` sous Ubuntu | `.github/workflows/code-audit.yml:270-304` |
| Synthèse existante : `pit --module`, `pit --all` | `scripts/quality/audit-report-summary.py:167`, `:191` |
| Le plugin ne se mute jamais sur un poste | même document, tableau d'état, ligne `minos-intellij` (« Jamais sur un poste », § 7.1) |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit demande de « régénérer le document » : c'est un relevé daté (commit `902e2bfa`, 8 octobre) ; réécrire son inventaire et son registre effacerait l'état de leur date | `findings.json`, AUD-TST-10, action | `code-audit-couverture.md:1-12` | D1 : seule la source unique d'état est tenue à jour ; les sections datées reçoivent une note |
| Le scénario existant « Tous les modules nommés » est violé sans qu'aucun contrôle ne l'ait vu | `openspec/specs/audit-qualite-code/spec.md:70-87` | document | D2 : contrôle exécutable |
| L'audit cite les lignes `:34`, `:38`, `:243`, `:247` : ce sont l'inventaire et les résultats, pas le tableau d'état, qui est le site réellement normatif | `findings.json`, AUD-TST-10, preuve | document, § 5.2 | Le tableau d'état est le site corrigé ; les autres sont annotés |
| Le point de reprise (§ 7.3) donne une commande sur un module disparu | document, `:369-370` | exigence « Les commandes documentées correspondent à la configuration » (elle vise `code-audit.md`) | Annoter comme commande historique ; la commande courante vit dans `code-audit.md` |
| « Aucune mesure du `minos-storage` fusionné » : les classes ont été mutées dans leurs modules d'origine | constat | document, lignes E27 et E30 | Ne pas additionner ; statut « non exécuté » jusqu'au rejeu |

## Exigences couvertes

- audit-qualite-code › Le tableau d'état de la mutation suit la liste des modules du reactor — D2.
- audit-qualite-code › Une mesure de mutation antérieure à une fusion de modules n'est pas reportée sur le module
  fusionné — D1, D3.

## Objectifs et non-objectifs

Non applicable : voir la proposition.

## Responsabilités et frontières des composants

Non applicable : aucun module du reactor ne change.

## Modèle de domaine et invariants

Invariant contrôlé : ensemble des noms de la première colonne du tableau d'état = modules de `pom.xml` ∪
{`minos-intellij`}.

## Traitements et interactions

Non applicable.

## États et transitions

Statuts d'une ligne, inchangés : exécuté, partiel, non exécuté, bloqué (exigence existante).

## Contrats d'interface

Contrôle proposé : `python scripts/quality/audit-report-summary.py mutation-status --check [--root <racine>]`.
Code 0 si les listes sont égales ; code 1 avec, sur stderr, une ligne par écart (`missing: <module>`,
`unknown: <ligne>`, ou `status table not found`). Auto-test : `--self-test` sur des copies mutées du `pom.xml` et du
document. Le tableau est repéré par sa structure (en-tête de colonnes « Module | Statut PIT | … »), pas par une
phrase.

## Persistance et intégrité

Non applicable.

## Transactions, concurrence et idempotence

Non applicable.

## Sécurité et isolation

Le contrôle lit deux fichiers du dépôt, en UTF-8, sans rien exécuter. Mutation du plugin : uniquement par
`code-audit.yml` ou en conteneur ; un mutant du plugin exécuté sur le poste a déjà modifié l'ACL du profil
utilisateur (incident H23 du document). Aucune tâche de ce changement ne lance `pitest` localement pour le plugin.

## Erreurs et reprise

Tableau introuvable : échec explicite, jamais un succès à vide.

## Observabilité

Non applicable.

## Performance et mesure

Durée de la mutation de `minos-storage` fusionné : inconnue ; si le délai de 120 min est dépassé, borner par
`target_classes` et écrire « partiel » avec le périmètre muté (exigence existante sur les rapports incomplets).

## Compatibilité, migration et retour arrière

Non applicable.

## Diagrammes

Non applicable : changement documentaire.

## Décisions

### D1 — Tenir à jour la source unique, annoter le relevé daté

[Proposition] Dans le tableau d'état : retirer les deux lignes des modules disparus, ajouter `minos-storage` et
`minos-explorer` à « non exécuté », corriger la phrase d'introduction. Sous le tableau, une note « Mesures
antérieures à la fusion » renvoie aux résultats E27 et E30. En tête de l'inventaire § 2.1 et du § 7.3, une note
d'une ligne dit que ces sections décrivent le reactor du 8 octobre. Alternative écartée : régénérer tout le
document — un relevé daté ne se réécrit pas.

### D2 — Garde automatique du tableau

[Proposition — décision du propriétaire attendue]

- **A (recommandée)** : sous-commande de `audit-report-summary.py` avec auto-test, exécutée par le job `invariants`
  (contrat ci-dessus). La dérive est signalée à la PR qui ajoute ou retire un module.
- **B** : contrôle manuel à la clôture d'un changement qui touche `pom.xml`. Aucun code, mais c'est la situation qui
  a laissé le document dériver.

Emplacement : `audit-report-summary.py` lit déjà la liste des modules (`module_dirs`) ; `check-current-docs.py` est
écarté pour ne pas croiser le changement `doter-chaque-gate-d-un-auto-test` (sprint 1).

### D3 — Mesures rejouées par le workflow d'audit

[Proposition] Trois exécutions de `code-audit.yml` sur `main` : `mutation_module=minos-storage`,
`mutation_module=minos-explorer`, `run_intellij_plugin` (après `tester-la-logique-d-interface-du-plugin` s'il est
fusionné). Chaque résultat est reporté avec numéro d'exécution (E39 et suivants), système, commit et sortie de
`audit-report-summary.py pit --module`. Sans exécution, la ligne reste « non exécuté » : le changement peut être
fusionné et archivé dans cet état, les tâches manuelles restant nommées.

## Qualification des capacités

`audit-qualite-code` : après les tâches 1.x, l'état est **exact** ; la mutation de `minos-storage` et de
`minos-explorer` reste **non exécutée** jusqu'aux tâches manuelles 2.x. Rien n'est présenté comme mesuré avant.

## Risques et compromis

- [Risque] Le contrôle ancre sur une phrase du document → repérage par l'en-tête de colonnes ; auto-test du cas
  « tableau introuvable ».
- [Risque] Un auto-test ajouté au job `invariants` casse `check-single-execution.py` ou `check-ci-wiring.py` → les
  deux sont rejoués dans la tâche.
- [Risque] Délai de 120 min dépassé pour `minos-storage` → `target_classes` et statut « partiel ».

## Vérification

- `python scripts/quality/audit-report-summary.py mutation-status --check` et son `--self-test`.
- `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`,
  `python scripts/docs/check-current-docs.py`, `python scripts/docs/check-capability-dossiers.py`.
- Scénario existant « Décompte unique » : `grep -c "modules sur 14"` du document reste au plus égal à 1 (0 au HEAD :
  la phrase d'introduction du tableau, `:266`, écrit « 11 sur 14 » et « 3 sur 14 »).
- Non vérifiable localement : les mutations (workflow à la demande).

## Documentation durable à mettre à jour

`docs/developer/quality/code-audit-couverture.md`, `docs/developer/quality/code-audit.md`,
`docs/architecture/diagrams/capacites/audit-qualite-code.md`.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| audit-qualite-code › Le tableau d'état de la mutation suit la liste des modules du reactor | Dépôt réel après le changement | D1, D2 | 1.1, 1.2, 1.3 | contrôle — à écrire |
| idem | Module ajouté au reactor sans ligne d'état | D2 | 1.1 | auto-test — à écrire |
| idem | Module disparu encore nommé | D2 | 1.1 | auto-test — à écrire ; rouge au HEAD |
| idem | Tableau d'état introuvable | D2 | 1.1 | auto-test — à écrire |
| audit-qualite-code › Une mesure de mutation antérieure à une fusion de modules n'est pas reportée sur le module fusionné | Module fusionné sans rapport | D1 | 1.2 | relecture du tableau |
| idem | Module nouveau sans rapport | D1 | 1.2 | relecture du tableau |
| idem | Rapport produit par le workflow d'audit | D3 | 2.1, 2.2 | manquante jusqu'aux exécutions manuelles |

## Questions ouvertes

- D2 : option A ou B (recommandation : A). Sous l'option B, la première exigence perd son contrôle exécutable et son
  delta est à réécrire avant l'implémentation.
