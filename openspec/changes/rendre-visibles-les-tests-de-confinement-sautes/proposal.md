# Proposal

## Why

L'audit du 10 octobre 2026 (sprint 1) constate deux défauts de visibilité des tests de confinement de `minos-runtime-local` :

- **AUD-TST-03** (chemin critique) : les tests bubblewrap, cgroup v2 et AppContainer se sautent en silence par `assumeTrue(discovered.isPresent(), …)`, sans interrupteur « requis » comparable à `minos.postgresql.tests.required`. Si la découverte du bac à sable échoue sur un runner (profil AppArmor, délégation cgroup, mise à jour d'image), la preuve de non-régression de S3 (isolation entre deux instances partageant une racine cgroup) disparaît en restant verte. Tant que ce défaut subsiste, ni la migration du JDK (AUD-DEP-01), ni les corrections de performance du bac à sable (AUD-PERF-08), ni AUD-SEC-06 ne sont vérifiables.
- **AUD-TST-06** : 21 tests passent à vide sur l'autre système d'exploitation (`if (plateforme) return;`) au lieu d'être comptés comme sautés. Les rapports gonflent le nombre de tests verts.

L'analyse apporte deux faits que l'audit ne pouvait pas voir (il n'avait ni bubblewrap, ni cgroup, ni AppContainer sur son poste) : (1) sur les runners GitHub actuels, **ces tests s'exécutent réellement** — la propriété « requise » ne rougira donc pas la CI ; (2) la propriété ne peut pas être posée avant d'avoir séparé les sauts de plateforme des sauts de capacité, sinon `LinuxCgroupJobOwnershipIsolationTest` (aucune garde de plateforme) échouerait sous Windows.

## What Changes

- Propriété `minos.sandbox.tests.required` (défaut `false` dans le POM parent, transmise à Surefire comme `minos.postgresql.tests.required`), passée à `true` par les deux étapes `Maven clean verify` de `pr-ci.yml`.
- Aide de test `SandboxTestSupport` (`minos-runtime-local/src/test`) : quand la propriété est vraie, l'absence d'un confinement **applicable à la plateforme courante** est un échec avec le diagnostic de la découverte ; sinon un saut visible. Les 26 `assumeTrue` de disponibilité (bubblewrap, racine cgroup déléguée, capacité de propriété forte, AppContainer) l'utilisent.
- Gardes de plateforme explicites : les 21 `if (plateforme) return;` deviennent `@EnabledOnOs`, et `LinuxCgroupJobOwnershipIsolationTest` reçoit `@EnabledOnOs(OS.LINUX)`. Un test inapplicable est compté « sauté » sur l'autre système.
- Liste nommée des sauts d'hypothèse d'environnement conservés (compte administrateur, `/dev/shm`, verrou étranger lisible, exemption volontaire des runners CI pour la non-élévation), pour qu'aucun saut de confinement ne reste anonyme.
- Documentation du mode « requis » dans `docs/developer/testing.md`.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `confinement-code-non-fiable` : exigences ajoutées sur la preuve du confinement (qualification exigible, absence = échec, tests propres à une plateforme comptés comme sautés ailleurs). Le comportement du lanceur n'est pas modifié ; la capacité garantit déjà « sous Windows comme sous Linux » et manquait d'une exigence sur la **preuve** de cette garantie.

## Hors périmètre

- Tout code de production du bac à sable.
- La propriété PostgreSQL et ses tests.
- Un second garde qui lirait les rapports Surefire pour refuser un saut par hypothèse non répertorié : écarté ici (voir `design.md`, D6), possible plus tard.
- `historical-qualification.yml` (rejeu manuel) : n'est pas modifié.
- AUD-DEP-01, AUD-PERF-08, AUD-SEC-06 : bénéficiaires du sprint, non traités.
- L'exemption `CI` de `WindowsNonElevatedIndexingTest` (non-élévation) reste : un runner GitHub tourne élevé par conception.

## Impact

- **Modules du reactor touchés** : `minos-runtime-local` (tests uniquement) ; POM parent (une propriété, une ligne `systemPropertyVariables`) ; aucun code de production.
- **Surfaces publiques impactées** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés).
- **ADR** : aucun nouvel ADR, aucun amendement.
- **CI** : `pr-ci.yml`, deux lignes de commande Maven.
- **Gates et scopes à rejouer** : `check-single-execution.py` (une exécution `mvnw` par OS, inchangée), `check-current-docs.py`, `check-audit-remediation-v2.py` (noms d'étape conservés), `check-p0-p2.py` (`delegate-linux-cgroup.sh --attach-pid $$` conservé), `check-minos-01.py` ; scopes JaCoCo `provider-sandbox-linux`, `provider-sandbox-windows`, `provider-execution-trust-boundary`.
- **Plateformes** : changement par nature spécifique à chaque OS ; Windows vérifiable sur le poste du mainteneur, Linux seulement sur un runner (tâche à autorisation).
