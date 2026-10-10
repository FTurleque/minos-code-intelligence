# Prompt — Sprint 1 de l'audit du 10/10/2026 : analyse et spécification OpenSpec

> À coller dans une session Claude Code ouverte sur `N:\workspace-dev\minos-code-intelligence`, branche `develop` à jour.

---

Tu prépares le **sprint 1** de l'audit du 10 octobre 2026 de MINOS, « Remettre les dispositifs de mesure en marche ». Ce sprint couvre **9 constats**. Ta mission s'arrête à l'**analyse** et à la **spécification OpenSpec**. Tu n'écris aucun code de production ni aucun test, et tu ne modifies aucun workflow ni aucun script de gate : tout cela relève de l'étape `openspec apply`, que je lancerai moi-même après validation.

Rédige tout en français, comme le reste du dépôt.

## 1. Sources à lire avant tout

1. `openspec/config.yaml` : le contrat du dépôt (contexte, règles proposal/design/specs). Il fait foi.
2. `docs/audit/2026-10-10/README.md`, puis `sprints.md` § Sprint 1, puis `findings.md` pour chacun des 9 constats. `findings.json` porte les mêmes données sous forme structurée.
3. Les changements OpenSpec actifs, qui peuvent recouvrir ce sprint :
   - `openspec/changes/etendre-audit-outille-a-tout-le-perimetre/`
   - `openspec/changes/aligner-dependances-de-test-testcontainers/`
4. Les specs existantes de `openspec/specs/`, en particulier `audit-qualite-code`, `program-graph-et-architecture` et `confinement-code-non-fiable`. Un changement doit **amender** une capacité existante quand elle couvre déjà le sujet, et non en créer une parallèle.
5. Les ADR cités par les constats : 0042, 0043, 0057 § 7 et 0058, dans `docs/adr/`.

## 2. Les 9 constats du sprint

| ID | Sév. | Effort | Constat (résumé) | Débloque |
|---|---|---|---|---|
| AUD-DEP-09 | Moyenne | S | Les gates statiques, OSV, Gitleaks et le plugin ne sont pas des checks exigés par le ruleset (G6, plausible : ruleset non vu) | — |
| AUD-TST-07 | Faible | S | L'auto-test de `check-jacoco.py` (8 scénarios) n'est exécuté par aucun workflow | TST-01, TST-02 |
| AUD-TST-08 | Faible | S | Le scope `m29-backend-routing` lit un rapport JaCoCo obtenu par redirection du `<directory>` de `minos-app` (T4) | TST-02 |
| AUD-TST-03 | Moyenne | S | Les tests de confinement bubblewrap, cgroup v2 et AppContainer se sautent en silence par `assumeTrue`, sans interrupteur « requis » comme `minos.postgresql.tests.required` ; 38 tests sautés dans `minos-runtime-local` au `verify` Windows du 10/10 | DEP-01, PERF-08, SEC-06 |
| AUD-TST-06 | Faible | S | 21 tests de `minos-runtime-local` passent à vide sur l'autre OS (`if (plateforme) return;`) au lieu d'être comptés comme sautés | — |
| AUD-ARC-10 | Faible | S | Les 13 auto-tests de `check-module-boundaries.py` ne couvrent aucune règle A2, et la détection des dépendances internes repose sur le littéral `com.minos` (E02) | ARC-01, ARC-05, ARC-08 |
| AUD-ARC-06 | Faible | M | Quatre cycles de packages (15 packages, dont 8 dans `minos-application` autour de `ProjectResolver` et `com.minos.output`), et aucune garde ne contrôle les cycles (E10) | — |
| AUD-TST-13 | Faible | S | La CI du plugin IntelliJ ne se déclenche pas quand change le code qui produit le JSON qu'il consomme (`com.minos.output`, goldens) | QUA-01 |
| AUD-ARC-03 | Moyenne | S | `arc42/05` donne des dépendances fausses pour 7 modules sur 14 et `SYNTHESE.md` affirme PostgreSQL → application ; `check-current-docs.py` passe malgré tout (correction incomplète de G16/G17) | — |

**Critère de sortie du sprint** (formulé comme un état du dépôt) : chaque gate du job `invariants` et chaque auto-test de gate sont des checks exigés ; un test de confinement sauté fait échouer le job de son OS ; la garde de frontières refuse un cycle de packages et une arête interdite par l'ADR 0042 ; la documentation d'architecture courante est contrôlée contre les POM.

**Ordre interne** : aucune dépendance entre ces 9 constats. Le sprint en débloque 9 autres, listés dans la dernière colonne.

## 3. Phase d'analyse : vérifier avant de spécifier

Pour chaque constat :

1. **Revérifie la preuve au HEAD courant**, `fichier:ligne` par `fichier:ligne`. `develop` a pu avancer depuis `816cdd0c`. Si un constat est déjà corrigé ou que sa preuve est fausse, dis-le, et ne spécifie rien pour lui.
2. **Cherche le recouvrement** avec les deux changements actifs et avec les specs existantes. Un recouvrement se traite en amendant, pas en dupliquant.
3. **Relève les gates littéraux** concernés (`scripts/remediation/check-*.py`, `scripts/m*/check-*.py`, `check-current-docs.py`, `check-milestone-artifact-references.py`) : un déplacement de classe ou une réécriture de workflow peut les casser. L'ADR 0043 interdit d'ancrer un gate sur une phrase.
4. **Qualifie la vérifiabilité** : comment saura-t-on, après implémentation, que le constat est fermé ? La réponse doit être une commande locale ou une assertion de test, pas « la CI est verte ».

Produis cette analyse dans le `design.md` de chaque changement, section « État vérifié au HEAD », avant toute décision.

## 4. Découpage en changements OpenSpec

Je propose ce découpage, à confirmer ou à contester à l'issue de l'analyse. Un changement correspond à un ensemble cohérent qu'une seule PR peut porter.

| Changement proposé | Constats | Capacité OpenSpec probable |
|---|---|---|
| `imposer-les-gates-et-leurs-auto-tests` | DEP-09, TST-07, TST-08, TST-13 | `audit-qualite-code` (amendement) |
| `rendre-visibles-les-tests-de-confinement-sautes` | TST-03, TST-06 | `confinement-code-non-fiable` (amendement) |
| `durcir-les-gardes-d-architecture` | ARC-10, ARC-06, ARC-03 | `program-graph-et-architecture` ou une capacité « frontières de modules » à créer si aucune ne convient — justifie ce choix |

Points à trancher explicitement dans les `design.md` :

- **AUD-DEP-09** : la configuration du ruleset GitHub se fait hors du dépôt. Le changement doit dire ce qui est versionné (par exemple la liste des checks exigés et un job agrégateur toujours exécuté pour le plugin filtré par chemins) et ce qui reste une **action manuelle de ma part**, sous forme de tâche marquée « manuelle ». Il doit aussi dire comment on vérifie que le ruleset correspond à la liste, par exemple avec un script `gh api` lancé à la main et non en CI.
- **AUD-TST-03** : nom et sémantique de la propriété « requis » (calquée sur `PostgresTestSupport`) ; sur quels jobs elle passe à `true` ; ce qu'il advient d'un runner GitHub où bubblewrap ou la délégation cgroup v2 ne sont **pas** disponibles. Cette question est bloquante : si les runners Ubuntu ne permettent pas ces tests, la propriété les rendrait rouges. Lis `pr-ci.yml` pour voir comment la chaîne de bac à sable y est installée, et ne présume rien.
- **AUD-ARC-06** : casser les cycles déplace des classes (`ProjectResolver`, `com.minos.output`). C'est le seul constat d'effort M. Décide si on livre d'abord la règle ArchUnit `beFreeOfCycles()` **en cliquet** sur les 4 cycles actuels, puis les déplacements, ou les deux ensemble. Dis si cela exige un ADR ou un amendement de l'ADR 0057 § 7. Vérifie l'impact sur les surfaces publiques : `com.minos.output` est consommé par la CLI et le MCP, et les goldens doivent rester identiques à l'octet.
- **AUD-ARC-03** : générer les listes de dépendances d'`arc42/05` depuis les POM, ou les remplacer par un lien vers le fichier généré. Dis comment `check-current-docs.py` détectera un écart futur sans s'ancrer sur une phrase (ADR 0043).

## 5. Exigences sur les artefacts

- Respecte `openspec/config.yaml` : hors périmètre explicite, modules du reactor touchés, surfaces publiques impactées, ADR nouveau ou amendé, Windows **et** Linux couverts.
- Les specs utilisent `## ADDED Requirements` ou `## MODIFIED Requirements`, avec des scénarios `#### Scenario:` en GIVEN/WHEN/THEN. Chaque scénario doit être vérifiable par une commande locale ou un test.
- Dans `tasks.md`, chaque tâche porte l'ID du constat qu'elle ferme (`AUD-…`). Ordonne les tâches de chaque changement ainsi : d'abord le test ou le gate qui échoue (rouge attendu), puis la correction, puis la preuve. Termine par une tâche « Clôture » qui met à jour le suivi dans `docs/audit/2026-10-10/` (statut du constat, commit, preuve).
- Valide avec `openspec validate --all --strict`. Les 3 changements doivent passer.

## 6. Contraintes

- **Aucune CI** : ne pousse rien et n'ouvre aucune PR. Si une vérification exige un runner GitHub, écris-la comme tâche à exécuter plus tard et demande-moi l'autorisation.
- Les vérifications locales sont permises et attendues : scripts Python des gates, `--self-test`, `python -m unittest scripts/architecture/test_check_module_boundaries.py`, `openspec validate`. Un `mvnw verify` complet prend environ 21 minutes : ne le lance que si une décision en dépend, et pose `JAVA_HOME` avant (AUD-TST-18).
- Ne crée pas d'ADR dans ce lot : si un ADR est nécessaire, le `design.md` le nomme et en esquisse la décision, et une tâche le prévoit.
- Ne touche pas aux constats des sprints suivants, même s'ils sont voisins (TST-01, TST-02, QUA-01, ARC-01…). Cite-les seulement comme bénéficiaires.

## 7. Rendu attendu

1. Les 3 changements (ou le découpage que tu proposes à la place, justifié) sous `openspec/changes/`, validés en mode strict.
2. Une réponse finale courte :
   - le statut de chaque constat au HEAD (confirmé, déjà corrigé, ou preuve à revoir) ;
   - le découpage retenu ;
   - les décisions qui m'attendent, en particulier le ruleset (DEP-09), les runners (TST-03) et la stratégie de cycles (ARC-06) ;
   - la liste des tâches manuelles ;
   - la commande pour lancer l'implémentation du premier changement.
