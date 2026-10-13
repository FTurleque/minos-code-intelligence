# Tasks

Ordre : vérification, puis rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`.

## 1. Vérifications préalables (AUD-SEC-08)

- [ ] 1.1 [AUD-SEC-08] Vérifier comment Mermaid affiche `&lt;` et `&gt;` dans un libellé entre guillemets (documentation de la version courante et un rendu réel) ; comparer à la forme numérique et à la syntaxe `#…;`. Consigner la forme retenue dans `design.md` (D2).
- [ ] 1.2 [AUD-SEC-08] Vérifier si `%%{…}%%` placé à la suite d'un commentaire `%% …` est interprété comme directive, et si un code d'entité `#…;` fourni par le dépôt a un effet dans un libellé. Consigner le résultat dans `design.md` (risque confirmé ou non reproduit) et requalifier la confiance du constat.

## 2. Chevrons des libellés (AUD-SEC-08)

- [ ] 2.1 [AUD-SEC-08] Rouge : tests de `ArchitectureResultRenderer` (à côté de `ArchitectureResultRendererLimitationsTest`) — module `<img src=x onerror=alert(1)>` : la sortie ne contient pas `<img` ; module `a&b<c` : une seule entité pour l'esperluette ; module nominal avec chemin : `core<br/>packages/core`. **Preuve du rouge : `<img` figure dans la sortie au HEAD (`ArchitectureResultRenderer.java:274-280`).**
- [ ] 2.2 [AUD-SEC-08] Échapper `<` et `>` ; échapper nom et chemin séparément puis insérer `<br/>` (D1). Preuve : tests verts ; `./mvnw -B -ntp -pl minos-application -am test` ; `./mvnw -B -ntp -pl minos-app -am verify` (golden `mcp-all-tools.golden` inchangé).

## 3. Lignes de commentaire (AUD-SEC-08)

- [ ] 3.1 [AUD-SEC-08] Rouge : test avec un nom de projet `demo %%{init: {"theme":"dark"}}%%` — aucune ligne de la sortie ne contient `%%{` ; test du nom nominal — ligne inchangée. **Preuve du rouge : la séquence figure dans la sortie au HEAD (`ArchitectureResultRenderer.java:163-166`).**
- [ ] 3.2 [AUD-SEC-08] Neutraliser `%%{` dans les textes des commentaires (D3). Preuve : tests verts ; `./mvnw -B -ntp -pl minos-cli -am test` (aucun test dédié à `ArchitectureCommand` n'a été relevé dans `minos-cli` : le rendu partagé est couvert par les tests de `minos-application`).

## 4. Clôture

- [ ] 4.1 `docs/user/architecture-graph.md` : dire que les textes issus du dépôt sont échappés dans le rendu Mermaid et que l'assainissement final reste à la charge du moteur de rendu. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 4.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-SEC-08 : statut, confiance requalifiée, résultats de 1.1 et 1.2, site des commentaires ajouté).
- [ ] 4.3 `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/user/architecture-graph.md`.
