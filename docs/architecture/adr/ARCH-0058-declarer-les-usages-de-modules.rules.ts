/// <reference path="../rules.d.ts" />

// ADR 0058, décision 1 : une seule politique de dépendances entre modules. Sa source unique est
// scripts/architecture/module-boundaries.json (AUD-ARC-04) : le garde Python la lit, et ModuleArchitectureTest la
// charge par ModuleBoundaryPolicy sans en recopier aucune table. Cette règle lit les mêmes fichiers que la règle A17
// de check-module-boundaries.py ; rien n'est recopié ici.

const POLICY = "scripts/architecture/module-boundaries.json";
const TEST = "minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java";
const READER = "minos-app/src/test/java/com/minos/app/architecture/ModuleBoundaryPolicy.java";

function lineAt(text: string, index: number): number {
  return text.slice(0, index).split("\n").length;
}

/** Le texte sans commentaires Java, littéraux de chaîne conservés. */
function withoutComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, (comment) => comment.replace(/[^\n]/g, " "));
}

async function readOrEmpty(ctx: RuleContext, file: string): Promise<string> {
  try {
    return await ctx.readFile(file);
  } catch {
    return "";
  }
}

/** La section allowedDependencies de la source unique est un objet non vide de listes. */
function allowedSectionReadable(policy: string): boolean {
  try {
    const section = (JSON.parse(policy) as { allowedDependencies?: unknown }).allowedDependencies;
    return (
      !!section &&
      typeof section === "object" &&
      !Array.isArray(section) &&
      Object.keys(section).length > 0 &&
      Object.values(section as Record<string, unknown>).every((targets) => Array.isArray(targets))
    );
  } catch {
    return false;
  }
}

const COPIED_TABLE =
  /\b(?:private\s+)?static\s+final\s+[^;=]*?\b([A-Z][A-Z0-9_]*)\s*=\s*(?:Map\.ofEntries|Map\.of|Set\.of|List\.of)\s*\(\s*(?:Map\.entry\s*\(\s*)?"minos-/g;

export default {
  rules: {
    "allowed-dependency-tables-identical": {
      description:
        "ADR 0058 : ModuleArchitectureTest charge la politique de module-boundaries.json, source unique lue aussi par check-module-boundaries.py, et n'en recopie aucune table",
      severity: "error",
      async check(ctx) {
        if (!allowedSectionReadable(await readOrEmpty(ctx, POLICY))) {
          ctx.report.violation({
            message: `La section allowedDependencies est absente, vide ou illisible dans ${POLICY} : la politique unique (ADR 0058) ne peut pas être vérifiée.`,
            file: POLICY,
          });
          return;
        }
        const test = await readOrEmpty(ctx, TEST);
        const reader = await readOrEmpty(ctx, READER);
        if (!test || !reader) {
          ctx.report.violation({
            message: `${!test ? TEST : READER} est absent : la lecture de la politique unique par le test ArchUnit (ADR 0058) ne peut pas être vérifiée.`,
            file: !test ? TEST : READER,
          });
          return;
        }
        const code = withoutComments(test);
        if (!/\bModuleBoundaryPolicy\s*\.\s*load\s*\(/.test(code)) {
          ctx.report.violation({
            message: `ModuleArchitectureTest ne charge plus la politique par ModuleBoundaryPolicy.load : il doit lire ${POLICY}.`,
            file: TEST,
            fix: `Charger la politique par ModuleBoundaryPolicy.load(Path.of("")) au lieu d'une table littérale.`,
          });
        }
        if (!/"module-boundaries\.json"/.test(withoutComments(reader))) {
          ctx.report.violation({
            message: `ModuleBoundaryPolicy ne nomme plus module-boundaries.json : le test ArchUnit ne lit plus la source unique.`,
            file: READER,
          });
        }
        for (const match of code.matchAll(COPIED_TABLE)) {
          ctx.report.violation({
            message: `ModuleArchitectureTest déclare de nouveau la table de politique ${match[1]} : elle se lit dans ${POLICY} (ADR 0058, une seule politique).`,
            file: TEST,
            line: lineAt(test, match.index ?? 0),
            fix: `Remplacer la table littérale ${match[1]} par la valeur chargée de ModuleBoundaryPolicy.`,
          });
        }
      },
    },
  },
} satisfies RuleSet;
