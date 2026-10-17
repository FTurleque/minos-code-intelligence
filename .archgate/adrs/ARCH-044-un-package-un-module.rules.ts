/// <reference path="../rules.d.ts" />

// Port de l'invariant A7 de scripts/architecture/check-module-boundaries.py
// (fonction check_reactor_modules). Le script Python reste la source de vérité :
// la liste MODULES y est lue, jamais recopiée ici.

const SCRIPT = "scripts/architecture/check-module-boundaries.py";
const ROOT_POM = "pom.xml";

function governedModules(script: string): string[] | null {
  const block = /^MODULES\s*=\s*\(([^)]*)\)/m.exec(script);
  if (!block) return null;
  return [...block[1].matchAll(/"([^"]+)"/g)].map((match) => match[1]);
}

function reactorModules(pom: string): string[] {
  const withoutComments = pom.replace(/<!--[\s\S]*?-->/g, "");
  return [...withoutComments.matchAll(/<module>\s*([^<]*?)\s*<\/module>/g)].map((match) =>
    match[1].replace(/\/+$/, ""),
  );
}

export default {
  rules: {
    "governed-modules-match-reactor": {
      description:
        "A7 : la liste MODULES de check-module-boundaries.py est exactement l'ensemble des <modules> du pom.xml racine",
      severity: "error",
      async check(ctx) {
        const script = await ctx.readFile(SCRIPT);
        const pom = await ctx.readFile(ROOT_POM);
        const governed = governedModules(script);
        if (governed === null || governed.length === 0) {
          ctx.report.violation({
            message: `Le tuple MODULES est introuvable dans ${SCRIPT} : l'invariant A7 ne peut pas être vérifié.`,
            file: SCRIPT,
          });
          return;
        }
        const reactor = reactorModules(pom);
        if (reactor.length === 0) {
          ctx.report.violation({
            message: `Aucun <module> utilisable dans ${ROOT_POM} : l'invariant A7 ne peut pas être vérifié.`,
            file: ROOT_POM,
          });
          return;
        }
        const duplicated = [...new Set(governed.filter((name, index) => governed.indexOf(name) !== index))];
        const missing = reactor.filter((name) => !governed.includes(name));
        const extra = governed.filter((name) => !reactor.includes(name));
        for (const name of duplicated) {
          ctx.report.violation({ message: `MODULES liste ${name} plusieurs fois.`, file: SCRIPT });
        }
        for (const name of missing) {
          ctx.report.violation({
            message: `Le module ${name} du reactor est absent de MODULES : il échappe à toutes les règles de frontière.`,
            file: SCRIPT,
            fix: `Ajouter "${name}" à MODULES dans ${SCRIPT}.`,
          });
        }
        for (const name of extra) {
          ctx.report.violation({
            message: `MODULES liste ${name}, absent des <modules> de ${ROOT_POM}.`,
            file: SCRIPT,
            fix: `Retirer "${name}" de MODULES ou l'ajouter au reactor.`,
          });
        }
      },
    },
  },
} satisfies RuleSet;
