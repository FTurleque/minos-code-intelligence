# Tasks

Ordre : relevé et décisions (1), puis dans chaque section rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Les tests de comportement sont propres à Windows ; dire pour chaque preuve sur quel système elle a été exécutée. À faire après `etendre-le-garde-d-e-s-privees-aux-ecritures-brutes`.

## 1. Relevé et décisions (AUD-SEC-06)

- [ ] 1.1 [AUD-SEC-06] Sous Windows, mesurer le comportement actuel : poser un refus conditionnel (XD) explicite puis hérité, appeler le durcissement, relire le SDDL (survie, disparition ou erreur) ; compter les objets durcis au premier contact pendant une indexation de ce dépôt et la durée d'un `icacls … /save`. Consigner dans `design.md` (« Non mesuré » levé).
- [ ] 1.2 [AUD-SEC-06] Relever les chaînes exactes exigées dans `WindowsAppContainerWorkerSandboxBackend.java` par `scripts/remediation/check-mne.py:146`, `scripts/remediation/check-mnd.py:77`, `scripts/remediation/check-post-mne.py:144`, `scripts/remediation/check-minos-01.py:151` et `scripts/remediation/check-post228-hardening.py:42-53`, et ce que fait `:620` après un octroi échoué. Consigner dans `design.md`.
- [ ] 1.3 [AUD-SEC-06] **Décision à obtenir** : D2 (lecture par `icacls /save`, recommandé, ou appel natif FFM avec ADR) et D3 (refuser, recommandé, ou conserver par un durcissement sans `setAcl`). Consigner dans `design.md`.

## 2. Refus conditionnels (AUD-SEC-06)

- [ ] 2.1 [AUD-SEC-06] Rouge : dans `PrivateLocalStorageWindowsAclTest`, variante XD du test `:229` (refus conditionnel explicite sur un répertoire, puis hérité par un fichier) — après l'appel, le SDDL relu contient encore l'entrée `XD`, que l'appel ait réussi ou échoué ; test « DACL illisible » par échec injecté dans la sonde de capacités. **Preuve du rouge : au HEAD l'entrée `XD` a disparu (résultat de 1.1).**
- [ ] 2.2 [AUD-SEC-06] `PrivateLocalStorage` : lecture de la DACL complète au premier contact dans `WindowsDacl` (D2), puis refus ou conservation (D3) **avant** `setAcl` ; message sans chemin absolu ; Javadoc `:62-72` réécrite (D5). Le test `:229` (XA retiré) reste vert sans être modifié. Preuve sous Windows : `./mvnw -B -ntp -pl minos-engine -am test` vert, `PrivateLocalStorageWindowsAclTest` sans test sauté ; `python scripts/remediation/check-p0-p2.py`, `python scripts/architecture/check-private-io.py` (entrée de liste ajoutée si un appel brut est nécessaire, avec justification) et `python scripts/architecture/check-module-boundaries.py` verts.

## 3. Primitive d'octroi (AUD-SEC-06)

- [ ] 3.1 [AUD-SEC-06] Rouge : dans `minos-runtime-local`, package `sandbox`, tests de la primitive (nouvelle) avec lanceur injecté — `SystemRoot` absent : « non accordé », aucun processus ; lanceur qui ne se termine pas : tué au délai, « non accordé » ; code de sortie non nul : « non accordé ». Test Windows : octroi sur un répertoire porteur d'un refus, SDDL avant et après identiques hors l'entrée ajoutée. **Preuve du rouge : `runIcacls` (`:720-727`) lève une `NullPointerException` sans `SystemRoot` et attend sans délai.**
- [ ] 3.2 [AUD-SEC-06] Créer la classe d'octroi package-private (D4) ; `requireInheritableOwnerAccess` (`:620`) et `isAclGrantable` (`:703`, `:712`) l'utilisent ; supprimer `runIcacls` en conservant les chaînes relevées en 1.2. Preuve : `./mvnw -B -ntp -pl minos-runtime-local -am test` vert (dire les tests sautés sous Linux) ; `python scripts/remediation/check-mne.py`, `check-mnd.py`, `check-post-mne.py`, `check-minos-01.py`, `check-post228-hardening.py` verts ; scope JaCoCo `provider-sandbox-windows` (`python scripts/quality/check-jacoco.py`).
- [ ] 3.3 **(manuelle)** [AUD-SEC-06] À la PR : `Verify (windows-2022)` vert en mode de confinement requis ; relever dans les rapports Surefire que `PrivateLocalStorageWindowsAclTest` et `WindowsAppContainerWorkerSandboxBackendTest` ne comptent aucun test sauté ; noter le run id dans `SUIVI.md`.

## 4. Clôture

- [ ] 4.1 [AUD-SEC-06] `docs/user/troubleshooting.md` : l'erreur nouvelle et sa résolution (si D3 = refuser) ; `docs/developer/indexing-and-storage.md` si elle décrit le durcissement Windows. Si D2 = FFM : ADR au prochain numéro libre de `docs/adr/README.md` et `docs/TOOLCHAIN_POLICY.md`. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-SEC-06 : statut, décisions D2 et D3, mesures de 1.1, run id ; signaler AUD-ARC-05 comme débloqué et ce qu'il devra reprendre : les deux lancements d'`icacls` de `WindowsDacl`).
- [ ] 4.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/user/troubleshooting.md`, `.claude/rules/securite.md` (points 6 et 9, si le texte cite la limite).
