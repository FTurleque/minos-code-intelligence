# Tasks

## 0. Préalable (utilisateur)

- [ ] 0.1 Rétablir le poste touché par H23 en administrateur (DACL du profil, entrées `S-1-15-2-…` du JDK) ; vérifier `java -XshowSettings:security -version` et `./mvnw -v`.

## 1. Test d'abord (machine jetable Windows)

- [ ] 1.1 Test Windows : lanceur tué après l'octroi (point d'arrêt dans le script), puis reprise ; rouge au HEAD (l'entrée reste).
- [ ] 1.2 Vérification en fin de classe des tests de bac à sable : aucune entrée de leur SID ne reste.

## 2. Journal et reprise

- [ ] 2.1 Journal durable des octrois dans le répertoire de récupération, avant `icacls /grant` ; retrait à la reprise sous la preuve de propriété A01 ; rejouer `check-minos-01.py`, `check-post228-hardening.py`, `check-audit-remediation-v2.py` et les tests de `WindowsAppContainerRecoveryOwnershipTest`.
- [ ] 2.2 Amender l'ADR 0038 si la conception le confirme.

## 3. Diagnostic

- [ ] 3.1 Recensement des entrées orphelines (lecture seule, retrait sur confirmation) ; tests.

## 4. Audit

- [ ] 4.1 Rejouer le PIT de `minos-runtime-local` sur un runner éphémère (`docs/quality/code-audit-couverture.md` § 7.3) ; consigner les résultats.
