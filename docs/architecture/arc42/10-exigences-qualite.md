# Section 10 — Exigences qualité

> Preuves : ADR-0010, ADR-0011, ADR-0015, ADR-0016, ADR-0022, ADR-0024,
> benchmark M15 (p50=3,88 ms, p95=5,34 ms), ADR-0035.

---

## 10.1 Tableau des qualités

| Identifiant | Qualité | Sous-qualité | Priorité |
|-------------|---------|-------------|---------|
| Q-1 | Exactitude / Honnêteté | Traçabilité, absence de résultat inventé | 1 |
| Q-2 | Confidentialité | Isolement local, chiffrement tenant | 2 |
| Q-3 | Extensibilité | Ajout de langage / provider | 3 |
| Q-4 | Stabilité des surfaces | Rétrocompatibilité CLI / MCP / API | 4 |
| Q-5 | Performance de requête | Latence p50 / p95 | 5 |
| Q-6 | Résilience | Promotion atomique, fail-closed | 6 |
| Q-7 | Maintenabilité | Frontières Maven, tests qualifiés | 7 |

---

## 10.2 Scénarios qualité

### QS-1 — Relation sans preuve (usage)

| Champ | Valeur |
|-------|--------|
| Qualité | Q-1 Exactitude |
| Stimulus | Un provider retourne une relation `CALLS` entre deux symboles |
| Environnement | Mode de requête nominal, provider SCIP Java |
| Réponse | La relation est enrichie de `Evidence{type=SCIP_INDEX}`, `ResolutionStatus=RESOLVED`, `ProviderReference` |
| Mesure | 100 % des relations dans un snapshot ont un champ `evidence` non null |
| Seuil | 0 relation sans `Evidence` |
| Méthode de vérification | Test unitaire `RelationshipNormalizerTest`, revue du codec snapshot |
| Propriétaire | Équipe MINOS |

---

### QS-2 — Code source ne quitte pas le poste (usage)

| Champ | Valeur |
|-------|--------|
| Qualité | Q-2 Confidentialité |
| Stimulus | L'utilisateur invoque `minos find-symbol --name X` en mode natif |
| Environnement | Poste développeur sans configuration cloud |
| Réponse | Aucun appel réseau sortant n'est émis par le processus MINOS |
| Mesure | Capture réseau nulle pendant une session de requête |
| Seuil | 0 connexion réseau sortante (hors Git local) |
| Méthode de vérification | Test d'intégration M14 replay + capture réseau |
| Propriétaire | Équipe MINOS |

---

### QS-3 — Ajout d'un provider Python sans modifier le cœur (changement)

| Champ | Valeur |
|-------|--------|
| Qualité | Q-3 Extensibilité |
| Stimulus | Implémentation d'un nouveau `IndexerProvider` pour Python via SCIP |
| Environnement | Codebase courante |
| Réponse | Aucun fichier de `minos-domain` ni `minos-engine` n'est modifié |
| Mesure | Diff git : 0 fichier touché dans `minos-domain/` et `minos-engine/` |
| Seuil | 0 modification dans les couches ≤ moteur |
| Méthode de vérification | ADR-0001 règles d'architecture + test de compilation Maven |
| Propriétaire | Équipe MINOS |

---

### QS-4 — Consommateur CLI M14 fonctionne sur M29 (changement)

| Champ | Valeur |
|-------|--------|
| Qualité | Q-4 Stabilité des surfaces |
| Stimulus | Un script M14 invoque `minos find-symbol` et `minos architecture` |
| Environnement | Distribution M29 installée |
| Réponse | Les commandes répondent avec les mêmes codes de sortie et le même format JSON |
| Mesure | 0 breaking change dans les contrats CLI, MCP, API depuis M9 |
| Seuil | Les tests de replay M14 passent sur M29 |
| Méthode de vérification | Tests Failsafe `minos-app` — replay M14 |
| Propriétaire | Équipe MINOS |

---

### QS-5 — Latence repeated query (usage)

| Champ | Valeur |
|-------|--------|
| Qualité | Q-5 Performance |
| Stimulus | 20 requêtes successives `find-symbol` sur le même snapshot in-memory |
| Environnement | Poste Windows, Java 24, snapshot M15 |
| Réponse | Toutes les requêtes après la première sont servies depuis `SnapshotQueryView` |
| Mesure | p50 ≤ 5 ms, p95 ≤ 10 ms |
| Seuil | p50 ≤ 5 ms (baseline observée : 3,88 ms) |
| Méthode de vérification | Benchmark E1-in-memory-repeat (`.minos-m0/benchmarks/`) |
| Propriétaire | Équipe MINOS |

---

### QS-6 — Snapshot corrompu détecté (défaillance)

| Champ | Valeur |
|-------|--------|
| Qualité | Q-6 Résilience |
| Stimulus | Le fichier snapshot est tronqué (crash disque) |
| Environnement | Démarrage de `minos mcp` en backend natif |
| Réponse | `SnapshotIntegrityService` détecte la corruption, refuse de promouvoir |
| Mesure | MINOS démarre sans snapshot actif et retourne une erreur claire |
| Seuil | 0 crash JVM non géré ; exit code 1 avec message lisible |
| Méthode de vérification | Test d'intégration `SnapshotIntegrityServiceTest` |
| Propriétaire | Équipe MINOS |

---

### QS-7 — Ajout d'un module Maven invalide (changement)

| Champ | Valeur |
|-------|--------|
| Qualité | Q-7 Maintenabilité |
| Stimulus | Un développeur ajoute une dépendance `minos-engine → minos-cli` |
| Environnement | Build Maven |
| Réponse | `mvn compile` échoue avec une erreur de dépendance cyclique |
| Mesure | Erreur de compilation détectée sans déploiement |
| Seuil | 100 % des dépendances interdites échouent à la compilation |
| Méthode de vérification | Contrainte Maven reactor + test de fumée CI |
| Propriétaire | Équipe MINOS |
