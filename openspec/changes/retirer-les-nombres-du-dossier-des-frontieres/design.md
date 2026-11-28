# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement documentaire et d'auto-test : un dossier de capacité et un cas d'un auto-test existant. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relu au commit `4f4cac814` (PR #829), le 2026-11-27.

| Fait | Preuve |
|---|---|
| En-tête : « Relevé au commit `4fee1ed4` (2026-11-25) », contenu modifié depuis par la PR #825 | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:4` |
| Vue des dépendances : « minos-app (assemblage, dépend de tous les autres modules) », sans nombre | `frontieres-de-modules.md:47` |
| Ligne de traçabilité des cycles connus : « « 4 cycles connus » contredit par l'exigence sur l'absence de cycle (E1) » | `frontieres-de-modules.md:122` |
| E1 : « quatre cycles (15 packages) », « 4 cycles connus », « 0 known cycles over 0 packages » | `frontieres-de-modules.md:220` |
| E2 : « 14 modules » dans trois exigences, « 13 modules » dans un scénario ; suite : scénario réécrit sans nombre | `frontieres-de-modules.md:221` |
| E4 : décomptes attendus `minos-api=14, …` contre « 3, 4, 1 et 0 » | `frontieres-de-modules.md:223` ; `openspec/specs/frontieres-de-modules/spec.md:522-525` |
| E8 : preuve `MinosApplication.java` (`gitIntelligence()`, `hostedControlPlaneService()`, ports du moteur) | `frontieres-de-modules.md:227` |
| La spécification interdit ces accesseurs (`:1696`) et garde des scénarios qui les appellent sur une copie (`:708`, `:731`) | `openspec/specs/frontieres-de-modules/spec.md` |
| Auto-test documentaire : étiquettes `A<n>` du paragraphe « Règles du garde », puces de `quality-gates.md`, fichiers du garde ; aucun contrôle de nombre | `scripts/architecture/test_check_module_boundaries_documents.py:434-486` |

## Écarts

1. **Plus large que le constat.** L'audit cite E2 et E4. La recherche sur le dossier trouve aussi des nombres dans E1 (`:220`) et dans la ligne de traçabilité des cycles (`:122`) : ils citent la spécification, mais le lecteur ne peut pas les distinguer d'un décompte de la politique, et ils vieillissent de même.
2. **E2 est déjà résolu.** Sa colonne « Suite » dit que le scénario fautif a été réécrit ; l'écart reste listé ouvert. Il passe « Clos », sans nombre.
3. **E8 cite comme preuve ce qui n'existe plus.** Le défaut restant n'est pas dans `MinosApplication.java` mais dans les scénarios de la spécification (`:708`, `:731`) qui supposent ces accesseurs.
4. **Renvoi à E2.** La ligne de traçabilité « Les packages de `minos-application` sont séparables » (`frontieres-de-modules.md:127`) cite « compte de modules faux (E2) » : la mention suit la clôture de E2.

## Décisions

### D1 — Refus des nombres en chiffres suivis d'un nom de la politique [Proposition, recommandation]

- (a) **Recommandé.** Le cas d'auto-test cherche dans le dossier `\b\d+\s+(?:autres\s+)?(?:modules?|dépendances?|découvertes?|packages?|cycles?)\b` (insensible à la casse) et échoue en nommant chaque ligne trouvée. Il ne lit ni les étiquettes `A<n>`, ni les commits, ni les numéros de ligne (`:220`). Il ne voit pas un nombre écrit en lettres : limite écrite dans la docstring du cas.
- (b) Comparer les nombres du dossier à ceux de la politique : fait vivre les nombres que l'exigence interdit.
- (c) Statu quo (revue seule) : l'exigence reste sans contrôle, ce que le constat relève.

### D2 — Dossier seul, spécification inchangée [Proposition, recommandation]

- (a) **Recommandé.** Corriger le dossier et ajouter l'auto-test. Les écarts E1, E4 et E8 restent ouverts et sans nombre ; leur correction dans la spécification demande des exigences `MODIFIED` dont le texte complet doit tenir en 500 caractères, à proposer dans un changement à part si le propriétaire le souhaite.
- (b) Joindre ici les trois `MODIFIED` : ferme les écarts, mais élargit le changement au-delà du constat et touche trois exigences relues par d'autres auto-tests.

### Pourquoi une exigence ajoutée plutôt que modifiée

L'exigence en vigueur « Le dossier de la capacité nomme chaque règle du garde » (`spec.md:1708-1709`) dit déjà que le dossier n'écrit aucun nombre dérivé de la politique (modules, dépendances, découvertes). Son énoncé fait 499 caractères : y ajouter les packages, les cycles et le contrôle par l'auto-test dépasserait la limite de 500 de `validate --strict`. L'exigence ajoutée en est le complément contrôlé ; à l'archivage, les deux restent cohérentes (la nouvelle liste contient l'ancienne).

## Vérification

- Rouge : le nouveau cas échoue sur le dossier au HEAD et nomme les lignes 122, 220, 221.
- Correction : `python scripts/architecture/test_check_module_boundaries_documents.py -v` vert ; le cas en mémoire (réinsertion de « 12 autres modules ») échoue en nommant la ligne.
- `python scripts/docs/check-capability-dossiers.py`, `python .claude/scripts/run_gates.py` verts.

## Documentation durable à mettre à jour

`docs/architecture/diagrams/capacites/frontieres-de-modules.md` (corps, puis ligne de l'exigence ajoutée à l'archivage).

## Traçabilité

| Constat | Exigence | Tâches | Preuve |
|---|---|---|---|
| AUD-ARC-18 | frontieres-de-modules › Le dossier des frontières n'écrit aucun décompte de la politique (nombres en chiffres) | 1.1 à 1.4 | `scripts/architecture/test_check_module_boundaries_documents.py#test_the_dossier_writes_no_count_of_the_policy` (rouge sur les lignes 122, 220 et 221 avant correction du dossier) ; `#test_a_count_written_back_into_the_dossier_is_seen` ; sur une copie du dépôt avec la ligne ajoutée, code 1 et ligne nommée |
| AUD-ARC-18 | même exigence, preuves d'écart sans méthode que `MinosApplication` ne déclare pas | 1.2, 1.4 | commande `git grep` du troisième scénario sans résultat ; aucun auto-test |
