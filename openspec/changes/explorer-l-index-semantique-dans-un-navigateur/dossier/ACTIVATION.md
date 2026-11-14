# Activer le dossier durable de `exploration-semantique`

`exploration-semantique.md`, dans ce dossier, est le dossier durable de la capacité **dans sa forme finale**. Il
ne peut pas vivre à son emplacement durable avant l'archivage : `scripts/docs/check-capability-dossiers.py`
refuse un dossier dont la capacité n'existe pas sous `openspec/specs/`
(`no capability 'exploration-semantique' under openspec/specs`, constaté le 2026-11-14).

Une répétition a été faite le 2026-11-14 : le dossier placé à son emplacement durable, avec une spec de
référence construite à partir du delta et sa section « Documentation liée », passe le gate
(`Capability dossiers consistent: 22 dossier(s) for 22 capability spec(s).`). Les deux fichiers ont ensuite été
retirés.

## À l'archivage

1. Archiver le changement : `openspec archive explorer-l-index-semantique-dans-un-navigateur -y`.
2. Déplacer le dossier, **avant** que l'archivage n'emporte ce répertoire, ou depuis l'archive :

   ```bash
   git mv openspec/changes/<emplacement du changement>/dossier/exploration-semantique.md docs/architecture/diagrams/capacites/exploration-semantique.md
   ```

3. Réécrire le « Purpose » provisoire de `openspec/specs/exploration-semantique/spec.md` (la carte 2D n'est pas
   livrée par ce changement), puis ajouter à la fin de cette spec :

   ```markdown
   ## Documentation liée

   - Dossier durable (vues, traçabilité, écarts) : [exploration-semantique](../../../docs/architecture/diagrams/capacites/exploration-semantique.md)
   ```

4. Ajouter la ligne de l'index `docs/architecture/diagrams/capacites/README.md`, à sa place alphabétique :

   ```markdown
   | [exploration-semantique.md](exploration-semantique.md) | `exploration-semantique` | Séquence d'une requête et de ses refus ; organigramme du démarrage et de l'écoute ; composants ; conteneurs Docker |
   ```

5. Supprimer ce fichier `ACTIVATION.md`, puis rejouer :

   ```bash
   python scripts/docs/check-capability-dossiers.py
   ```

   ```bash
   openspec validate --all --strict
   ```

## À vérifier à ce moment-là

- Les preuves citées par la table de traçabilité sont des chemins `fichier#méthode` relevés le 2026-11-13 : un
  test renommé depuis fait échouer le gate, qui le nomme.
- L'écart E2 du dossier (le « Purpose » et un scénario citent la projection) se referme quand
  `projeter-l-index-semantique-en-2d` est livré ou quand le « Purpose » est réécrit.
- Les tâches 3.8 (partie dossier) et 8.2 du changement se cochent à ce moment, pas avant.
