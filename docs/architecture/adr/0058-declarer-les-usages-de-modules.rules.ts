/// <reference path="../rules.d.ts" />

// ADR 0058, décision 1 : la table ALLOWED_DEPENDENCIES de scripts/architecture/check-module-boundaries.py et la
// table ALLOWED de ModuleArchitectureTest « restent identiques ». Aucun gate Python ne le vérifie : cette règle est
// le seul contrôle automatique. Les deux tables sont lues dans leurs fichiers, jamais recopiées ici.

const SCRIPT = "scripts/architecture/check-module-boundaries.py";
const TEST = "minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java";

type Table = Map<string, { line: number; targets: Set<string> }>;

function lineAt(text: string, index: number): number {
  return text.slice(0, index).split("\n").length;
}

function names(list: string): Set<string> {
  return new Set([...list.matchAll(/"([^"]+)"/g)].map((match) => match[1]));
}

/** Le dictionnaire Python `ALLOWED_DEPENDENCIES: dict[...] = { ... }` jusqu'à l'accolade fermante en colonne 0. */
function scriptTable(script: string): Table | null {
  const start = /^ALLOWED_DEPENDENCIES\s*:[^=\n]*=\s*\{/m.exec(script);
  if (!start) return null;
  const end = script.indexOf("\n}", start.index);
  if (end < 0) return null;
  const offset = start.index + start[0].length;
  const block = script.slice(offset, end).replace(/#[^\n]*/g, (comment) => " ".repeat(comment.length));
  const table: Table = new Map();
  for (const entry of block.matchAll(/"([^"]+)"\s*:\s*frozenset\(\s*(\{[^}]*\})?\s*\)/g)) {
    table.set(entry[1], { line: lineAt(script, offset + (entry.index ?? 0)), targets: names(entry[2] ?? "") });
  }
  return table;
}

/** La table Java `Map<String, Set<String>> ALLOWED = Map.ofEntries( ... );`. */
function testTable(source: string): Table | null {
  const start = /\bALLOWED\s*=\s*Map\.ofEntries\(/.exec(source);
  if (!start) return null;
  const end = source.indexOf(");", start.index);
  if (end < 0) return null;
  const offset = start.index + start[0].length;
  const block = source.slice(offset, end).replace(/\/\/[^\n]*/g, (comment) => " ".repeat(comment.length));
  const table: Table = new Map();
  for (const entry of block.matchAll(/Map\.entry\(\s*"([^"]+)"\s*,\s*Set\.of\(([^)]*)\)\s*\)/g)) {
    table.set(entry[1], { line: lineAt(source, offset + (entry.index ?? 0)), targets: names(entry[2]) });
  }
  return table;
}

export default {
  rules: {
    "allowed-dependency-tables-identical": {
      description:
        "ADR 0058 : ALLOWED_DEPENDENCIES de check-module-boundaries.py et ALLOWED de ModuleArchitectureTest déclarent exactement les mêmes modules et les mêmes arêtes",
      severity: "error",
      async check(ctx) {
        const script = scriptTable(await ctx.readFile(SCRIPT));
        const test = testTable(await ctx.readFile(TEST));
        if (!script || script.size === 0) {
          ctx.report.violation({
            message: `La table ALLOWED_DEPENDENCIES est introuvable ou vide dans ${SCRIPT} : l'identité des deux tables (ADR 0058) ne peut pas être vérifiée.`,
            file: SCRIPT,
          });
          return;
        }
        if (!test || test.size === 0) {
          ctx.report.violation({
            message: `La table ALLOWED (Map.ofEntries de Map.entry/Set.of) est introuvable ou vide dans ${TEST} : l'identité des deux tables (ADR 0058) ne peut pas être vérifiée.`,
            file: TEST,
          });
          return;
        }
        for (const [module, entry] of script) {
          const other = test.get(module);
          if (!other) {
            ctx.report.violation({
              message: `Le module ${module} figure dans ALLOWED_DEPENDENCIES mais pas dans la table ALLOWED de ModuleArchitectureTest.`,
              file: TEST,
              fix: `Ajouter Map.entry("${module}", Set.of(...)) à ALLOWED dans ${TEST}, avec les mêmes arêtes que ${SCRIPT}:${entry.line}.`,
            });
            continue;
          }
          for (const target of entry.targets) {
            if (!other.targets.has(target)) {
              ctx.report.violation({
                message: `L'arête ${module} → ${target} figure dans ALLOWED_DEPENDENCIES (${SCRIPT}:${entry.line}) mais pas dans ModuleArchitectureTest.`,
                file: TEST,
                line: other.line,
                fix: `Ajouter "${target}" à l'entrée ${module} de ALLOWED, ou la retirer de ALLOWED_DEPENDENCIES : les deux tables restent identiques (ADR 0058).`,
              });
            }
          }
          for (const target of other.targets) {
            if (!entry.targets.has(target)) {
              ctx.report.violation({
                message: `L'arête ${module} → ${target} figure dans ModuleArchitectureTest (${TEST}:${other.line}) mais pas dans ALLOWED_DEPENDENCIES.`,
                file: SCRIPT,
                line: entry.line,
                fix: `Ajouter "${target}" à l'entrée ${module} de ALLOWED_DEPENDENCIES, ou la retirer de ALLOWED : les deux tables restent identiques (ADR 0058).`,
              });
            }
          }
        }
        for (const [module, entry] of test) {
          if (!script.has(module)) {
            ctx.report.violation({
              message: `Le module ${module} figure dans la table ALLOWED de ModuleArchitectureTest mais pas dans ALLOWED_DEPENDENCIES.`,
              file: SCRIPT,
              fix: `Ajouter "${module}" à ALLOWED_DEPENDENCIES dans ${SCRIPT}, avec les mêmes arêtes que ${TEST}:${entry.line}.`,
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
