# Tasks

Ordre : rouge, correction, preuve. Aucun prérequis de série.

## 1. Écriture de la configuration du backend MCP (AUD-SEC-03)

- [x] 1.1 [AUD-SEC-03] Rouge : dans `minos-app` (`McpBackendStoreSymlinkTest` et un test de sauvegarde ajouté), (a) le contenu écrit est identique à l'octet près à celui d'aujourd'hui pour les quatre clés (fixture capturée avant la modification), nom de conteneur non ASCII compris, la ligne de commentaire datée de `Properties.store` étant fixée ou écartée ; (b) un lien symbolique substitué au fichier temporaire avant l'écriture est refusé et la cible du lien est intacte (point d'injection package-private ajouté avec le test) ; (c) le fichier final est privé (`0600` / ACL propriétaire seul). **Preuve du rouge : (b) l'écriture actuelle suit le lien ; (a) et (c) caractérisent l'existant.**
- [x] 1.2 [AUD-SEC-03] `McpBackendConfigurationStore.save` : `properties.store` en mémoire puis `PrivateLocalStorage.writePrivateFile(temporaire, octets)` (D1) ; retirer l'entrée `McpBackendConfigurationStore` / `Files.newBufferedWriter` de `scripts/architecture/private-io-allowlist.json`. Preuve : `./mvnw -B -ntp -pl minos-app -am test`, `python scripts/architecture/check-private-io.py` vert (`allowlisted-occurrences` baisse de 1).
- [x] 1.3 [AUD-SEC-03] Rejouer `python scripts/remediation/check-post-mne.py`, `python scripts/architecture/test_check_private_io.py -v`, `python .claude/scripts/run_gates.py --fast`, puis `./mvnw -B -ntp -pl minos-app -am verify` et `python scripts/quality/check-jacoco.py` (scope `m29-backend-routing`) ; aucun plancher abaissé.

## 2. Clôture

- [ ] 2.1 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (lot « application et registre », partie `minos-app` close).
- [ ] 2.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : aucune au-delà du suivi.
- [ ] 2.3 Baseline SpotBugs : relire `quality/spotbugs-baseline/minos-app.xml` pour les méthodes réécrites (comparaison par `instanceHash` dans `code-audit.yml`, qui n'est pas un check exigé) ; régénérer la baseline si une entrée disparaît ou change de place.
