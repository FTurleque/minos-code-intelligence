# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement d'outillage : une règle d'un garde Python, son auto-test, sa documentation. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relu au commit `4f4cac814` (PR #829), le 2026-11-27.

| Fait | Preuve |
|---|---|
| `PUBLIC_METHOD = \bpublic\s+((?:static\|final\|synchronized\|abstract\|default)\s+)*([A-Za-z_][\w.<>,?\[\] ]*?)\s+([a-z]\w*)\s*\(` : après les modificateurs, le type de retour commence par une lettre ; `<T>` n'est pas reconnu | `scripts/architecture/module_boundary_ratchets.py:129-130` |
| `public_instance_methods` (retours d'instance) et `public_signatures` (méthodes et constructeurs publics, pour les paramètres) itèrent sur `PUBLIC_METHOD` ; le constructeur est lu par une seconde expression `public MinosApplication(` | `module_boundary_ratchets.py:134-157` |
| `minos_application_accessors` (retours, `:240-247`) et `minos_application_engine_parameters` (paramètres, `:251-277`) résolvent un nom par `engine_types_named` : import simple ou nom qualifié seulement | `module_boundary_ratchets.py:188-198`, `:235-236`, `:261-262` |
| Pour un paramètre, le type est le texte avant le nom du paramètre : `T` d'une méthode `<T extends ProjectRegistry>` ne nomme aucun type du moteur | `module_boundary_ratchets.py:268-273` |
| `MinosApplication` au HEAD : aucune méthode générique publique, aucun import joker | `grep -n "public <\|^import .*\*;" MinosApplication.java` vide |
| Auto-test des paramètres : cinq variantes non génériques refusées ; aucun cas générique ni joker | `scripts/architecture/test_check_module_boundaries_ratchets.py:376-396` |

### Mutations rejouées

Le 2026-11-27, dépôt extrait par `git archive HEAD` dans le scratchpad, une copie par mutation, méthode insérée avant `projectInspectionService()` dans `MinosApplication.java` ; `python scripts/architecture/check-module-boundaries.py` lancé dans la copie.

| Mutation | rc |
|---|---|
| Témoin (copie intacte) | 0 |
| `public void leakPlain(com.minos.registry.ProjectRegistry r) {}` | 1 |
| `public <T> void leakGeneric(com.minos.registry.ProjectRegistry r) {}` | 0 |
| `public <T> com.minos.registry.ProjectRegistry leakRet() { return null; }` | 0 |
| `public <T extends com.minos.registry.ProjectRegistry> void leakBound(T r) {}` | 0 |

Non rejoué : l'import joker (`import com.minos.io.*;` + `public void e(BoundedFileLease s)`), établi par le second vérificateur de l'audit.

## Écarts

1. **L'action de l'audit est plus étroite que le défaut.** Accepter `(?:<[^>]*>\s+)?` avant le type de retour fait lire `leakGeneric` et `leakRet`, mais pas `leakBound` : le paramètre a le type `T`, et seule la borne nomme le type du moteur. Elle casse aussi sur une borne imbriquée (`<T extends Map<String, ProjectRegistry>>`), que `[^>]*` coupe au premier `>`.
2. **Troisième forme de contournement.** Un import joker d'un package du moteur rend tout nom simple de ce package invisible à A11 (`:235-236`, `:261-262`). L'audit le signale dans le recoupement, pas dans l'action.
3. **Constructeur générique.** `public <T> MinosApplication(…)` n'est reconnu par aucune des deux expressions. Forme rare, couverte par la même lecture.
4. **Retours statiques (contre-vérification).** `public static com.minos.registry.ProjectRegistry leakStatic2()` donne rc=0 au HEAD : `public_instance_methods` écarte les méthodes statiques, et la règle des paramètres ne lit pas les retours. `public static <T> com.minos.registry.ProjectRegistry leakStatic()` donne aujourd'hui rc=1 par accident : l'expression avale `static <T> …` comme type de retour, modificateurs vides, et traite la méthode en méthode d'instance. Corriger la lecture des modificateurs (D1) ferait perdre ce refus accidentel. D3 tranche.

## Décisions

### D1 — Lire la section de paramètres de type dans le garde Python [Proposition, recommandation]

- (a) **Recommandé.** Une lecture équilibrée des chevrons (comme `parameter_declarations` le fait déjà pour les virgules) reconnaît `public [modificateurs] <…> type nom(` et `public <…> MinosApplication(`. Le texte de la section compte dans le texte du type de retour et dans celui de chaque paramètre : un type du moteur nommé dans une borne est rendu et pris par la méthode. Même règle, même job `invariants`, réponse en secondes.
- (b) Règle ArchUnit sur les signatures effacées (`ModuleArchitectureTest`) : l'effacement remplace `T` par sa borne et voit toutes les formes, mais ne tourne que dans les jobs `Verify` (~40 min), et doublerait A11 sans retirer la règle Python.
- (c) Statu quo écrit dans la docstring : laisse le cliquet « à zéro » contournable.

### D2 — Import joker d'un package du moteur refusé dans `MinosApplication` [Proposition, recommandation]

- (a) **Recommandé.** A11 échoue, en nommant le fichier et le package, sur `import <groupId>.<package>.*;` quand le package appartient à `minos-engine`. Fail-closed, sans résolution ambiguë.
- (b) Résoudre les noms simples contre chaque package importé par joker : plus permissif, et un nom présent dans deux packages devient ambigu.

### D3 — Retours des méthodes statiques publiques dans la règle [Proposition, recommandation]

- (a) **Recommandé.** Une méthode statique publique de `MinosApplication` qui rend un type du moteur échoue en A11, comme une méthode d'instance ; elle n'entre pas dans la table des accesseurs (cliquet à zéro, aucune entrée possible). Au HEAD, les méthodes statiques publiques (`open`, `builder`) rendent `MinosApplication` ou `Builder` : la règle reste verte.
- (b) Retours statiques hors règle, figés par un cas accepté de l'auto-test : laisse ouverte une fuite de même nature que celles que ferme ce changement.

### Pourquoi une exigence ajoutée plutôt que modifiée

Les exigences en vigueur sur A11 (« Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet », « La façade ne rend ni type du moteur ni racine de composition ») ont des énoncés proches de la limite de 500 caractères de `validate --strict`. Les étendre en `MODIFIED` la dépasserait ; l'exigence ajoutée les complète sans les répéter.

## Vérification

- Rouge : les nouveaux cas de l'auto-test échouent avant la correction ; les mutations du tableau donnent rc=0.
- Correction : `python scripts/architecture/test_check_module_boundaries_ratchets.py -v` vert ; `python scripts/architecture/check-module-boundaries.py` vert sur le dépôt, sortie identique à celle du HEAD hors ligne éventuelle de la règle ; chaque mutation du tableau et l'import joker donnent rc=1 et nomment la méthode (ou le package) et le type.
- `python .claude/scripts/run_gates.py` vert.

## Documentation durable à mettre à jour

`docs/developer/quality-gates.md` (puce A11 des accesseurs), docstring de `module_boundary_ratchets.py` (anglais), `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (ligne de l'exigence ajoutée, à l'archivage).

## Traçabilité

| Constat | Exigence | Tâches | Preuve attendue |
|---|---|---|---|
| AUD-ARC-17 | frontieres-de-modules › Une méthode générique ou un import joker de la façade ne contourne pas A11 | 1.1 à 1.4, 2.1 | mutations rouges (rc=1), auto-test vert, garde vert au HEAD |
