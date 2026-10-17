# Tasks

Ordre : lot 1 (détecteur, AUD-SEC-05) puis lot 2 (messages, AUD-SEC-04). Dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`.

## 1. Détecteur : séquences de contrôle (AUD-SEC-05)

- [ ] 1.1 [AUD-SEC-05] Rouge : dans `PublicErrorMessagesTest`, cas `\u001b[31m/home/u/x` et `abcC:\Users\u\token` attendus sensibles ; cas « séquence ANSI sans chemin » dont le texte rendu ne garde aucun reste de séquence. **Preuve du rouge : les deux premiers ne sont pas détectés au HEAD (`PublicErrorMessages.java:76`).**
- [ ] 1.2 [AUD-SEC-05] `PublicErrorMessages` : retrait des séquences ANSI complètes et des caractères de contrôle avant l'analyse (D1) ; forme lecteur Windows reconnue sans frontière (D2). Preuve : tests verts ; `./mvnw -B -ntp -pl minos-engine -am test`.

## 2. Détecteur : pas de faux positif sur les chemins relatifs (AUD-SEC-05)

- [ ] 2.1 [AUD-SEC-05] Tests de non-régression écrits **avant** 1.2 et verts avant comme après : `module packages/app/src not found` rendu inchangé, `request to example.com/status failed` (`PublicErrorMessagesTest.java:109`) non sensible, un message de refus de découverte nommant `data/db` rendu inchangé. Ajouter le cas `v1.2/home/u`, attendu **non détecté**, avec un commentaire qui renvoie à la limite D2.
- [ ] 2.2 [AUD-SEC-05] Rejouer les tests des appelants : `./mvnw -B -ntp -pl minos-mcp -am test` (`MinosMcpErrorRedactionTest`, `MinosMcpTypedErrorsTest`) et `./mvnw -B -ntp -pl minos-cli -am test`. Relever tout message devenu générique et le consigner dans `design.md`.

## 3. Messages des primitives (AUD-SEC-04)

- [ ] 3.1 [AUD-SEC-04] Relevé : lister dans `minos-engine` et `minos-storage-local` les messages d'exception qui concatènent un `Path`, et les tests qui affirment un chemin dans un message. Confirmer les cinq sites du design ; consigner tout site supplémentaire dans `design.md`.
- [ ] 3.2 [AUD-SEC-04] Rouge : un test par site — `BoundedProperties.load` sur un répertoire, `FileProjectFingerprintSnapshotStore` et `FileIndexStateStore` sur un lien (garde de `BoundedPropertiesSymlinkTest`), `DurableAtomicFile` (message de durabilité, Linux) — affirmant que le message ne contient pas le répertoire temporaire. **Preuve du rouge : le chemin absolu figure dans chaque message au HEAD.**
- [ ] 3.3 [AUD-SEC-04] Remplacer le chemin par l'étiquette ou le nom de fichier aux cinq sites (D4). Preuve : tests verts ; `./mvnw -B -ntp -pl minos-storage-local -am test` ; `python scripts/architecture/check-private-io.py` vert.

## 4. Configuration présente mais non régulière (AUD-SEC-04)

- [ ] 4.1 [AUD-SEC-04] Rouge : tests de `MinosRuntimeSettings.load` — fichier absent (configuration vide, vert au HEAD), répertoire à la place du fichier (échec attendu), lien cassé (échec attendu, garde de lien). **Preuve du rouge : les deux derniers rendent une configuration vide au HEAD (`MinosRuntimeSettings.java:64`).**
- [ ] 4.2 [AUD-SEC-04] `BoundedProperties` : lecture à absence typée, existence testée sans suivre les liens (D3) ; `MinosRuntimeSettings.load` sans pré-test. Conserver les littéraux exigés par les gates. Preuve : tests verts ; `python scripts/remediation/check-audit-remediation-v2.py`, `python scripts/remediation/check-post-mne.py`, `python scripts/architecture/check-private-io.py` verts ; `python scripts/quality/check-jacoco.py` sur le scope qui inclut `MinosRuntimeSettings` ; `./mvnw -B -ntp -pl minos-engine -am verify`.
- [ ] 4.3 [AUD-SEC-04] Vérifier l'ouverture de bout en bout : `MINOS_HOME` neuf sans `minos.properties` (la CLI démarre) et `MINOS_HOME` avec un répertoire à la place (erreur d'usage sans chemin absolu). Preuve : `./mvnw -B -ntp -pl minos-app -am verify` (goldens inchangés).

## 5. Clôture

- [ ] 5.1 `docs/user/troubleshooting.md` : erreur « configuration présente mais non régulière ». Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 5.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-SEC-05 et AUD-SEC-04 : statuts, écarts, limite D2, site `InterProcessLocalProjectRegistry` cité à tort, sites supplémentaires de 3.1).
- [ ] 5.3 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/project/governance/STATUS.md`, `docs/user/troubleshooting.md`.
