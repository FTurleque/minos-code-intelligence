# Décision M0 — Faisabilité technique de MINOS

Date : 22 juillet 2026

Verdict : **ADOPTER_AVEC_CONTRAINTES**

État : **M0 TECHNIQUE TERMINÉ — livraison de la PR #4 bloquée par la CI**

## Décision exécutive

MINOS peut construire une Code Intelligence locale, multi-langages et
indépendante des fournisseurs à partir d'index SCIP réels.

Le chemin retenu est :

```text
projet
  -> indexeur sélectionné par capacité
  -> index SCIP ou format fournisseur adapté
  -> adaptateur MINOS
  -> modèle et identités MINOS
  -> CodeKnowledgeStore léger appartenant à MINOS
  -> services de requêtes MINOS
```

Glean n'est pas le backend par défaut. Il reste une option avancée future,
réouvrable uniquement lorsqu'une traversée, un volume ou une dérivation
mesurable dépasse les capacités du chemin léger.

Cette décision clôt la question technique M0. Elle ne valide pas GitHub
Actions, ne rend pas la PR #4 fusionnable et ne démarre pas M1.

## Réponse aux hypothèses M0

| Hypothèse | Résultat | Décision |
|---|---|---|
| SCIP fournit un socle sémantique exploitable | confirmée avec différences fournisseur | protocole privilégié, jamais modèle métier |
| Le domaine peut rester agnostique | confirmée sur Java, TypeScript et Glean C1 | conserver les frontières actuelles |
| MINOS peut fonctionner sans Glean | confirmée sur huit index réels | chemin léger par défaut |
| Glean justifie son coût pour le MVP | non confirmée | backend optionnel différé |
| Java 24 est viable | confirmée localement et avec `scip-java` | conserver Java 24 |
| Un second écosystème est absorbable | confirmée avec TypeScript | négocier les capacités fournisseur |

## Preuves principales

### Java et SCIP

- `scip-java 0.13.1` qualifié sur `java-simple`, `java-24-smoke`, un reactor
  Maven multi-module, une compilation partielle et `ariane-chatbot` ;
- Java 24.0.1 et Maven `release=24` réellement exécutés ;
- quatre index Java finaux ingérés dans MINOS ;
- 13/13 symboles obligatoires et 5/5 cibles d'usage sur `java-simple` ;
- limites `scip lint` / snapshot conservées comme incompatibilités de SCIP CLI
  0.7.1 avec les plages typées.

### TypeScript

- `scip-typescript 0.4.0` qualifié sur quatre index finaux ;
- mêmes contrats `domain`, `store` et `query` que Java ;
- références cross-project et héritage exploitables ;
- kinds absents, surcharges fusionnées et appel sur type absent conservés comme
  limitations fournisseur explicites.

### Backend léger

- huit index réels ingérés par `InMemoryCodeKnowledgeStore` ;
- 48/48 compteurs et digests identiques entre deux campagnes ;
- pires p95 observés : 1,443 ms pour `find_symbol` et 10,249 ms pour
  `find_usages` ;
- aucune dépendance Glean requise.

`InMemoryCodeKnowledgeStore` reste une baseline, pas le stockage persistant de
production. Le stockage embarqué final devra respecter le même port.

### Glean

- Glean 0.2.0.1 construit et exécuté sous Ubuntu 24.04 WSL2 ;
- ingestion directe de l'index moderne en échec sur `decodeScipRange` ;
- ingestion réussie après conversion de 128 plages et 27 plages englobantes ;
- 13/13 symboles, 9/13 kinds, 5/5 cibles d'usage et deux implémentations ;
- aucune relation `CALLS` explicite ni statut de non-résolution équivalent à
  MINOS ;
- 4,52 GB de store Cabal, environ 0,8 s au p95 par processus CLI et pics RSS
  de 204 340 KiB à l'ingestion et 155 376 KiB en requête.

Ces coûts ne sont pas compensés par une capacité MVP décisive sur le corpus M0.

## Décisions architecturales confirmées

1. `CodeKnowledgeStore` reste une frontière appartenant à MINOS.
2. Aucun type SCIP, Protobuf, Glean, Angle ou Thrift ne traverse le cœur.
3. L'identité SCIP brute reste une `ProviderReference` opaque.
4. Une occurrence non résolue reste explicitement non résolue.
5. Les capacités absentes ne sont jamais inventées : kinds, appels,
   surcharges, rôles et relations restent qualifiés.
6. Les index fournisseur ne sont promus qu'après succès atomique.
7. Java 24, Maven Wrapper 3.3.4 et Maven 3.9.16 restent la toolchain.
8. Le package applicatif reste `com.minos`.
9. Glean reste optionnel et C2 Thrift / C3 sidecar sont différées.

## Identité canonique et parseur SCIP

M0 ne justifie pas un port complet de la grammaire des identifiants SCIP.

Décision :

```text
M0 / M1
  -> conserver CANONICAL lorsqu'une identité canonique est réellement fournie
  -> utiliser STRUCTURAL_FALLBACK ou PROVIDER_SCOPED_FALLBACK sinon
  -> ne pas fabriquer qualifiedName

M2
  -> requalifier le besoin d'un parseur uniquement avec des cas de recherche
     qualifiée, surcharge et migration inter-fournisseurs mesurables
```

La capacité à produire un `qualifiedName` canonique n'est donc pas déclarée
complète. Cette limitation est acceptée pour M0 et devient une porte de M2.

## Conditions de sortie M0

| Condition | État |
|---|---|
| `scip-java` sur fixtures et dépôt réel | satisfaite |
| second écosystème qualifié | satisfaite avec TypeScript |
| cœur sans type SCIP | satisfaite |
| `find_symbol` / `find_usages` sans Glean | satisfaite |
| Glean testé sur les mêmes usages | satisfaite au niveau décisionnel C1/E2 |
| coûts Windows/Linux documentés | satisfaite |
| performances mesurées | satisfaite sur le corpus M0 |
| précision mesurée | satisfaite sur les vérités terrain ciblées |
| décision backend | satisfaite |
| limites connues documentées | satisfaite |

Les métriques de réduction de contexte IA, la précision exhaustive, les
traversées avancées et la scalabilité au-delà du corpus deviennent des travaux
des jalons produit concernés. Elles ne doivent pas maintenir artificiellement
M0 ouvert.

## Frontière entre clôture technique et livraison

```text
M0 technique    TERMINÉ
PR #4           DRAFT, non fusionnable actuellement
GitHub Actions  NON VALIDÉ, issue #5 ouverte
M1              AUTORISÉ ARCHITECTURALEMENT, NON DÉMARRÉ
```

Le passage opérationnel à M1 demande encore :

1. une stratégie CI satisfaite ou une dérogation explicite du propriétaire ;
2. la fusion de la PR #4 sans contourner #5 ;
3. la création d'une branche M1 seulement après cette intégration.

## Première tranche recommandée pour M1

M1 doit commencer par la découverte et la négociation, sans refaire les
adaptateurs M0 :

1. registre local de projets et workspaces ;
2. détection des langages, builds et racines de sources ;
3. `IndexerRegistry` et sélection par profil de capacités ;
4. cycle de vie explicite de l'index et promotion atomique ;
5. conservation de la baseline mémoire pour les tests.

Le stockage persistant, les requêtes produit M2/M3 et une CLI stabilisée ne
doivent pas être absorbés prématurément dans cette première tranche.

## Rapports de preuve

- `RAPPORT_SCIP_JAVA_A1_A2.md` ;
- `RAPPORT_SCIP_JAVA_A3_ARIANE.md` ;
- `RAPPORT_SCIP_JAVA_A4_A5.md` ;
- `RAPPORT_SCIP_TYPESCRIPT_D1.md` ;
- `RAPPORT_SCIP_TYPESCRIPT_D2.md` ;
- `RAPPORT_BACKEND_MEMOIRE_E1.md` ;
- `RAPPORT_GLEAN_C1.md` ;
- `COMPARATIF_BACKENDS.md` ;
- `STRATEGIE_VALIDATION_CI_M0.md`.
