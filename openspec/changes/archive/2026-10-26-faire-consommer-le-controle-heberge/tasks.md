# Tasks

Ordre dans chaque section : rouge, correction, preuve. Les tests vivent dans la même tâche que le code et dans le module de leur classe. Gates à rejouer à chaque tâche :

- `python scripts/architecture/check-module-boundaries.py` et `python scripts/architecture/test_check_module_boundaries.py` ;
- `python scripts/quality/check-hosted-control-plane-consistency.py` ;
- `python scripts/quality/check-comment-language.py` ;
- `python .claude/scripts/run_gates.py --fast`.

Portées JaCoCo concernées : `m27-team-hosted-control-plane` et `global`.

## 1. Façade applicative (AUD-ARC-01, lot 3)

- [x] 1.1 [AUD-ARC-01] Rouge (`minos-application`) : `TeamControlPlaneTest` utilise un vrai `HostedControlPlaneService`, sur un magasin en mémoire et des clés fixes. Cas couverts :
  - chaque opération et sa projection ;
  - le refus d'un jeton forgé, propagé tel quel ;
  - le refus d'une politique hors bornes, avec le message du moteur ;
  - l'exhaustivité de `TeamRole` ;
  - le masquage des jetons de `TeamBootstrap` et de `TeamKeyRotation` ;
  - une entrée nulle présentée par un membre non autorisé, qui reçoit le refus d'autorisation du moteur et non une erreur de la façade.

  **Preuve du rouge : la façade n'existe pas.**
- [x] 1.2 [AUD-ARC-01] Ajouter `com.minos.application.team` : `TeamControlPlane`, ses modèles (D2) et `MinosApplication.teamControlPlane()` (D1). Commentaires en anglais, sans jalon. Preuve : les tests 1.1 sont verts, et `check-module-boundaries.py` est vert (A8, A14, A15).
- [x] 1.3 [AUD-ARC-01] `HostedControlPlaneRenderer` prend les modèles de la façade (D3), couvert dans son module par `HostedControlPlaneRendererTest`. Preuve : `RendererKeyOrderTest` est vert sans attente modifiée.

## 2. Les surfaces consomment la façade (AUD-ARC-01, lot 3)

- [x] 2.1 [AUD-ARC-01] Rouge : un auto-test de `test_check_module_boundaries.py` affirme qu'aucune surface de la liste réelle n'a d'entrée `hosted.*`. Il échoue au HEAD (13 entrées).
- [x] 2.2 [AUD-ARC-01] Faire passer les surfaces par la façade :
  - `LocalMinosTeamApi` (API) ;
  - `TeamCommand`, `MinosCli` et `MinosCliRunner` (CLI), avec les méthodes du builder renommées `teamControlPlane` et `teamControlPlaneSupplier`, et `MinosCliSurfaceTest` et `MinosCliBuilderTest` mis à jour ;
  - `MinosApplicationMcpBackend` (MCP).

  Les fixtures de test (`TeamFixtures`, `TeamCommandTest`, `MinosApplicationMcpBackendM27Test`) enveloppent le service, sans aucune attente modifiée. Preuve : les tests de `minos-api`, `minos-cli` et `minos-mcp` sont verts.
- [x] 2.3 [AUD-ARC-01] Cliquets :
  - `ENGINE_TYPES_NAMED_BY_SURFACE` perd les 13 entrées `hosted` (33 → 20) ;
  - `ENGINE_TYPES_USED_BY_SURFACE_BYTECODE` perd les 15 (28 → 13).

  Mutation sur copie : réimporter `com.minos.hosted.HostedRole` dans `LocalMinosTeamApi`, puis rappeler `opened.hostedControlPlaneService()` dans `MinosCliRunner`, fait chaque fois échouer A11. Preuve : le garde, son auto-test (2.1 vert) et `ModuleArchitectureTest` sont verts.
- [x] 2.4 [AUD-ARC-01] Gates littéraux et portées : `check-hosted-control-plane-consistency.py` attend `TeamControlPlane` dans `LocalMinosTeamApi.java`, et interdit `com.minos.hosted` dans ce fichier. `check-jacoco.py` (`m27-team-hosted-control-plane`) gagne le préfixe `com/minos/application/team/`. Preuve : rouge du gate M27 avant sa mise à jour, puis vert ; `check-post-mne.py` vert.

## 3. Sécurité et sorties (AUD-ARC-01, lot 3)

- [x] 3.1 [AUD-ARC-01] Régressions du plan de contrôle : tests `*Hosted*`, `*ControlPlane*` et `*Team*` de `minos-engine`, `minos-storage-local`, `minos-application`, `minos-api`, `minos-cli` et `minos-mcp`. Ils couvrent S1, S2 et B01 à B04. Passer le skill `minos-security-checklist` sur le diff.
- [x] 3.2 [AUD-ARC-01] Lancer `mvnw -pl minos-app -am test "-Dtest=A2SurfaceCharacterizationTest,ModuleArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false"`. Il doit être vert : les 12 goldens inchangés, et aucun golden régénéré.

## 4. Clôture

- [x] 4.1 [AUD-ARC-01] Mettre à jour la documentation :
  - ADR 0058 (mise en œuvre, « Point 3, lot 3 résorbé ») ;
  - arc42 § 5.3 et `docs/architecture/diagrams/c4-component-application.md` ;
  - `docs/developer/team-hosted-mode.md` ;
  - ligne du lot 3 dans `SUIVI.md`.

  Puis rejouer `python .claude/scripts/run_gates.py`, `check-current-docs.py`, `product-facts.py --check`, `openspec validate --all --strict` et `archgate check`.
- [x] 4.2 [AUD-ARC-01] Lancer SpotBugs (profil `audit-spotbugs`) sur `minos-application`, `minos-api`, `minos-cli` et `minos-mcp` : 0 nouvelle alerte. `check-jacoco.py` : `m27-team-hosted-control-plane`. Faire un `mvnw verify` complet sous Windows ; Linux est couvert par la CI. Avant archivage, mettre à jour `docs/project/governance/STATUS.md` si le compteur A11 y figure.
