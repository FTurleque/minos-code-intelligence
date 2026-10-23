/// <reference path="../rules.d.ts" />

// Ports des invariants A7 (check_reactor_modules), A3 (check_package_ownership) et A14 (check_package_roots)
// de scripts/architecture/check-module-boundaries.py. Le script Python reste la source de vérité et le gate de la
// CI : les tables MODULES et PACKAGE_ROOTS_BY_MODULE y sont lues, jamais recopiées ici.

const SCRIPT = "scripts/architecture/check-module-boundaries.py";
const ROOT_POM = "pom.xml";
const PACKAGE_DECLARATION = /^\s*package\s+([A-Za-z_][\w.]*)\s*;/;

function governedModules(script: string): string[] {
  const block = /^MODULES\s*=\s*\(([^)]*)\)/m.exec(script);
  if (!block) return [];
  return [...block[1].matchAll(/"([^"]+)"/g)].map((match) => match[1]);
}

function reactorModules(pom: string): string[] {
  const withoutComments = pom.replace(/<!--[\s\S]*?-->/g, "");
  return [...withoutComments.matchAll(/<module>\s*([^<]*?)\s*<\/module>/g)].map((match) =>
    match[1].replace(/\/+$/, ""),
  );
}

/** Le groupId littéral du POM racine (hors <parent>), comme repository_group_id du script. */
function reactorGroupId(pom: string): string {
  const own = pom.replace(/<!--[\s\S]*?-->/g, "").replace(/<parent>[\s\S]*?<\/parent>/, "");
  const match = /<groupId>\s*([^<\s]+)\s*<\/groupId>/.exec(own);
  return match && !match[1].includes("${") ? match[1] : "";
}

/** Le dictionnaire PACKAGE_ROOTS_BY_MODULE du script : module -> racines (premier segment sous le groupId). */
function packageRootsByModule(script: string): Map<string, Set<string>> | null {
  const start = /^PACKAGE_ROOTS_BY_MODULE\s*:[^=\n]*=\s*\{/m.exec(script);
  if (!start) return null;
  const end = script.indexOf("\n}", start.index);
  if (end < 0) return null;
  const block = script.slice(start.index + start[0].length, end).replace(/#[^\n]*/g, "");
  const table = new Map<string, Set<string>>();
  for (const entry of block.matchAll(/"([^"]+)"\s*:\s*frozenset\(\s*(\{[^}]*\})?\s*\)/g)) {
    table.set(entry[1], new Set([...(entry[2] ?? "").matchAll(/"([^"]+)"/g)].map((match) => match[1])));
  }
  return table;
}

type Declaration = { module: string; file: string; line: number; pkg: string };

/** Première déclaration `package` de chaque source Java d'un dossier (main ou test) des modules gouvernés. */
async function declarations(ctx: RuleContext, modules: string[], folder: "main" | "test"): Promise<Declaration[]> {
  const matches = await ctx.grepFiles(PACKAGE_DECLARATION, `minos-*/src/${folder}/java/**/*.java`);
  const seen = new Set<string>();
  const result: Declaration[] = [];
  for (const match of matches) {
    const file = match.file.replace(/\\/g, "/");
    const module = file.split("/")[0];
    if (!modules.includes(module) || seen.has(file)) continue;
    const pkg = PACKAGE_DECLARATION.exec(match.content);
    if (!pkg) continue;
    seen.add(file);
    result.push({ module, file, line: match.line, pkg: pkg[1] });
  }
  return result;
}

async function modulesOrViolation(ctx: RuleContext, invariant: string): Promise<string[] | null> {
  const modules = governedModules(await ctx.readFile(SCRIPT));
  if (modules.length === 0) {
    ctx.report.violation({
      message: `Le tuple MODULES est introuvable dans ${SCRIPT} : l'invariant ${invariant} ne peut pas être vérifié.`,
      file: SCRIPT,
    });
    return null;
  }
  return modules;
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
        if (governed.length === 0) {
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
    "one-package-one-module": {
      description:
        "A3 (§ 3) : un package n'est déclaré que par les sources de production d'un seul module ; un test vit dans le répertoire de son package et dans le module qui possède ce package (second exécutant de check_package_ownership)",
      severity: "error",
      async check(ctx) {
        const modules = await modulesOrViolation(ctx, "A3");
        if (!modules) return;
        const production = await declarations(ctx, modules, "main");
        if (production.length === 0) {
          ctx.report.violation({
            message: "Aucune déclaration `package` trouvée dans minos-*/src/main/java : l'invariant A3 ne peut pas être vérifié.",
          });
          return;
        }
        const owners = new Map<string, Map<string, Declaration>>();
        for (const declaration of production) {
          const byModule = owners.get(declaration.pkg) ?? new Map<string, Declaration>();
          if (!byModule.has(declaration.module)) byModule.set(declaration.module, declaration);
          owners.set(declaration.pkg, byModule);
        }
        for (const [pkg, byModule] of owners) {
          if (byModule.size < 2) continue;
          const sites = [...byModule.values()].map((site) => `${site.file}:${site.line}`).join(", ");
          for (const site of byModule.values()) {
            ctx.report.violation({
              message: `Le package ${pkg} est déclaré par les sources de production de ${byModule.size} modules (${[...byModule.keys()].join(", ")}) : ${sites}. La visibilité package traverserait un jar.`,
              file: site.file,
              line: site.line,
              fix: "Replier le package selon l'ADR 0068 § 3 : déplacer les classes dans le module le plus bas qui peut toutes les accueillir, sinon renommer le côté interne dans l'espace de noms de son module.",
            });
          }
        }
        for (const test of await declarations(ctx, modules, "test")) {
          const prefix = `${test.module}/src/test/java/`;
          const directory = test.file.slice(prefix.length).split("/").slice(0, -1).join(".");
          if (directory !== test.pkg) {
            ctx.report.violation({
              message: `Le test ${test.file} déclare le package ${test.pkg}, mais son répertoire correspond à ${directory || "<package par défaut>"}.`,
              file: test.file,
              line: test.line,
              fix: "Placer le test dans le répertoire de son package (ADR 0068 § 3).",
            });
            continue;
          }
          const byModule = owners.get(test.pkg);
          if (byModule && !byModule.has(test.module)) {
            ctx.report.violation({
              message: `Le test ${test.file} de ${test.module} est déclaré dans le package ${test.pkg}, dont la production appartient à ${[...byModule.keys()].join(", ")}.`,
              file: test.file,
              line: test.line,
              fix: `Déplacer le test dans le module qui possède ${test.pkg}, ou déplacer le code testé (ADR 0068 § 3).`,
            });
          }
        }
      },
    },
    "package-roots-owned-by-module": {
      description:
        "A14 (§ 5) : chaque package de production d'un module est sous une racine com.minos.<x> que ce module possède dans PACKAGE_ROOTS_BY_MODULE (second exécutant de check_package_roots)",
      severity: "error",
      async check(ctx) {
        const modules = await modulesOrViolation(ctx, "A14");
        if (!modules) return;
        const roots = packageRootsByModule(await ctx.readFile(SCRIPT));
        if (!roots || roots.size === 0) {
          ctx.report.violation({
            message: `La table PACKAGE_ROOTS_BY_MODULE est introuvable ou vide dans ${SCRIPT} : l'invariant A14 ne peut pas être vérifié.`,
            file: SCRIPT,
          });
          return;
        }
        const group = reactorGroupId(await ctx.readFile(ROOT_POM));
        if (!group) {
          ctx.report.violation({
            message: `Aucun <groupId> littéral dans ${ROOT_POM} : l'invariant A14 ne peut pas être vérifié.`,
            file: ROOT_POM,
          });
          return;
        }
        for (const module of roots.keys()) {
          if (!modules.includes(module)) {
            ctx.report.violation({
              message: `PACKAGE_ROOTS_BY_MODULE nomme ${module}, qui n'est pas un module gouverné (MODULES).`,
              file: SCRIPT,
              fix: `Retirer l'entrée "${module}" de PACKAGE_ROOTS_BY_MODULE, ou ajouter le module au reactor et à MODULES.`,
            });
          }
        }
        const reported = new Set<string>();
        for (const declaration of await declarations(ctx, modules, "main")) {
          const segment = declaration.pkg.startsWith(`${group}.`)
            ? declaration.pkg.slice(group.length + 1).split(".")[0]
            : "";
          const allowed = roots.get(declaration.module) ?? new Set<string>();
          const key = `${declaration.module}:${segment}`;
          if (allowed.has(segment) || reported.has(key)) continue;
          reported.add(key);
          ctx.report.violation({
            message: `${declaration.module} déclare le package ${declaration.pkg}, sous la racine ${group}.${segment || "<aucune>"} que ce module ne possède pas (racines : ${[...allowed].sort().join(", ") || "aucune"}).`,
            file: declaration.file,
            line: declaration.line,
            fix: `Créer le package sous une racine du module, de préférence son espace de noms ; une nouvelle racine est une décision (ADR 0068 § 5) qui s'ajoute à PACKAGE_ROOTS_BY_MODULE dans ${SCRIPT} par une modification relue citant sa raison.`,
          });
        }
      },
    },
  },
} satisfies RuleSet;
