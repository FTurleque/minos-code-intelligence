# Tasks

Ordre dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Les signatures publiques de `ConfinedFileOpener` ne changent pas.

## 1. Relevé (AUD-QUA-10)

- [x] 1.1 [AUD-QUA-10] Relever, pour chacun des six sites de test qui posent le crochet (`ConfinedFileOpenerTest.java:59,86,109,128,235`, `OpenRegularFileNoFollowTest.java:73`, et les remises à zéro `:37` / `:29`), l'entrée publique visée et le chemin d'ouverture exercé (descripteurs de répertoire ou vérification sous descripteur tenu) ; relever les tests sautés sous Windows faute de privilège de lien symbolique. Consigner dans `design.md`.

## 2. Crochet en paramètre (AUD-QUA-10)

- [x] 2.1 [AUD-QUA-10] Rouge : dans `minos-engine`, test par réflexion « aucun champ `static` non `final` dans `ConfinedFileOpener` » (D2), et test « un appel n'influence pas le suivant » (ouverture avec crochet, puis ouverture publique sans remise à zéro). **Preuve du rouge : le test de structure échoue sur le champ `beforeOpenForTests` (`ConfinedFileOpener.java:64`).**
- [x] 2.2 [AUD-QUA-10] `ConfinedFileOpener` : supprimer le champ ; ajouter les deux surcharges package-private et passer le crochet aux deux méthodes privées (D1) ; conserver les chaînes exigées par `scripts/remediation/check-p0-p2.py:140-141`. Migrer `ConfinedFileOpenerTest` et `OpenRegularFileNoFollowTest` vers les surcharges et retirer leurs `@AfterEach` de remise à zéro ; chaque test garde son assertion de refus. Preuve : `./mvnw -B -ntp -pl minos-engine -am test` vert ; aucune occurrence de `beforeOpenForTests` dans le dépôt ; `python scripts/remediation/check-p0-p2.py`, `python scripts/remediation/check-audit-remediation-v2.py`, `python scripts/architecture/check-private-io.py` et `python .claude/scripts/run_gates.py --fast` verts.
- [x] 2.3 [AUD-QUA-10] Relever dans le rapport JaCoCo de `minos-engine` que les lignes modifiées de `ConfinedFileOpener` sont couvertes sur les deux chemins d'ouverture (seuil SonarCloud du nouveau code : 80 %) ; dire sur quel système chaque chemin a été exécuté.

## 3. Clôture

- [x] 3.1 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-QUA-10 : statut ; Q15 : partie crochet close, partie E/S du record close selon l'audit et relue à cette occasion). Ne pas modifier `docs/project/audits/archive/2026-09/AUDIT-2026-09.md`.
- [x] 3.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : aucune page utilisateur ; `docs/developer/testing.md` si elle décrit le point d'injection.
