---
description: Passer en revue les PR de dépendances (Dependabot) et proposer fusionner / vérifier / refuser avec preuves
argument-hint: "[numéro de PR]"
---

Applique le skill `minos-dependency-update` : `$ARGUMENTS` (vide = toutes les PR ouvertes `build(deps)`).

1. `gh pr list --search "build(deps) in:title" --state open` (ou la PR indiquée) ; pour chacune, `gh pr diff`.
2. Classer : correctif/sécurité sans changement d'API · majeure ou comportement modifié · touche la baseline (Java, Maven, Gradle, IntelliJ — à ne jamais changer en silence) · image/digest · action GitHub.
3. Lancer les contrôles adaptés (`check-workflow-pins`, `check-image-pins`, `dependency:tree` pour la convergence) et lire l'état des checks de la PR.
4. Rendre un tableau `PR | nature | risque | vérifié | recommandation (fusionner / attendre / refuser / décision requise)`. Ne rien fusionner : la fusion passe par `/minos:merge` sur demande.
