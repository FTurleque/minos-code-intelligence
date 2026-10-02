# Suivi S23 - lot 4 (S14) : motif de refus de reprise

## Relocalisation

Constat d'audit : `refusalReason` (ADR 0039) serait ecrit tel quel par `minos index`, en texte et en JSON, hors de
`publicDiagnostic`. Sur develop (c3c87b4c) :

- `AutonomousIndexOperations.ResumeView` fait deja passer `refusalReason` par `CliCommandSupport.publicDiagnostic`
  a la construction (R1 lot 3) ; `LocalAutonomousIndexOperations.resumeView` (chemin de production) construit
  ce `ResumeView`, donc il n'existe pas d'autre ecriture publique ;
- `IndexCommand.resumedMap` / `resumedText` ne font que relayer la valeur du `ResumeView` ;
- `FileIndexStateStore` stocke le motif, ce n'est pas une sortie publique.

## Ce qui fuyait reellement (preuve rouge)

Test `IndexCommandRefusalReasonTest` (stub `AutonomousIndexOperations`, sorties texte et JSON de `IndexCommand`) :

| Entree | Avant | Apres |
|---|---|---|
| Motif avec chemin absolu (Windows ou POSIX) | deja remplace par `internal diagnostic redacted` (texte et JSON) : la premisse etait fausse | inchange |
| Motif avec ESC `\u001b[31m`, BEL, NUL, U+202E | texte : caracteres de controle ecrits tels quels (rouge : `control character U+7`) ; JSON : valide, les controles ASCII y sont echappes en `\uXXXX`, mais la valeur relue par un parseur les contient encore | texte et valeur JSON : chaque caractere remplace par `_` |

Le JSON restait donc valide ; la fuite reelle etait la sortie texte (sequences executees par le terminal), et la valeur
JSON non neutralisee pour un consommateur qui l'afficherait.

## Decision

Point d'etranglement unique : `CliCommandSupport.publicDiagnostic`, qui applique desormais
`DegradedEntry.printable` apres `PublicErrorMessages.sanitize` (meme regle que `failureMessage`). Tous les diagnostics
(`doctor`, `tools`, `provider`, `index`, `import-scip`, `remote index`) en profitent sans nouvel assainisseur.
Formes JSON et golden de `minos-app` inchanges.

## Journal

- `5604aed1` fix(cli) : `publicDiagnostic` applique la regle des caracteres de controle + test
  `IndexCommandRefusalReasonTest`.
- (suivant) docs : ce fichier de suivi.

## Resultats

- Cibles : `IndexCommand*,CliCommandSupport*` vert (21 tests). Rouge avant correction : 2 echecs sur 3 (controles en
  sortie texte) ; le test de chemin absolu etait deja vert.
- Gates : check-private-io (511 / 37 / 8 / 4), check-module-boundaries (14 / 511 / 45), check-current-docs,
  product-facts --check, check-milestone-artifact-references (98), check-workflow-pins (70) : tous SUCCESS.
- `./mvnw clean verify` complet unique : BUILD SUCCESS (Windows, JaCoCo m24 non rouge cette fois).

## A traiter plus tard

- `PublicErrorMessages.looksSensitive` ignore un chemin colle a un caractere d'identifiant (`...m` + `C:\Users\x`,
  y compris une sequence ESC `[31m` immediatement suivie du chemin) : le chemin passe alors la detection. Le cas est
  etroit mais reel ; a traiter dans `PublicErrorMessages` (hors perimetre de ce lot minuscule).
