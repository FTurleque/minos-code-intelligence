/// <reference path="../rules.d.ts" />

// Règles de l'ADR GEN-0073 (registre d'ADR au format Archgate). Contrôles propres à Archgate : aucun gate Python
// ne lit l'en-tête, le nom ni les sections des ADR.
// - Décision § 2 : fichier {PRÉFIXE}-{NNNN}-{slug}.md, id {PRÉFIXE}-{NNNN}, préfixe du domaine, numéro unique,
//   titre # égal au title.
// - Décision § 3 : sections Decision et Do's and Don'ts (avertissement).
// - Décision § 1 : .archgate/adrs/ ne contient que .gitkeep.
// - Revue : chaque ADR figure dans l'index README.md.

const REGISTRY = "docs/architecture/adr";
const INDEX = `${REGISTRY}/README.md`;
const CONFIG = ".archgate/config.json";
const ADR_FILE = /^([A-Z]+)-(\d{4})-[^/]+\.md$/;
const BRIDGE_DIRECTORY = ".archgate/adrs";
const REQUIRED_SECTIONS = ["Decision", "Do's and Don'ts"];
// Préfixes des domaines intégrés à Archgate (reference/adr-schema) ; les domaines du projet sont lus dans CONFIG.
const BUILT_IN_PREFIXES: Record<string, string> = {
  architecture: "ARCH",
  backend: "BE",
  frontend: "FE",
  data: "DATA",
  general: "GEN",
};

async function registryAdrs(ctx: RuleContext): Promise<string[]> {
  // Le registre seul : ni archive/ ni gabarit/, qu'Archgate ne lit pas.
  return (await ctx.glob(`${REGISTRY}/*.md`))
    .filter((file) => ADR_FILE.test(file.split("/").pop() as string))
    .sort();
}

function frontmatter(text: string): Record<string, string> | null {
  const lines = text.split(/\r?\n/);
  if (lines[0] !== "---") return null;
  const end = lines.indexOf("---", 1);
  if (end < 0) return null;
  const fields: Record<string, string> = {};
  for (const line of lines.slice(1, end)) {
    const match = /^([a-zA-Z]+):\s*(.*?)\s*$/.exec(line);
    if (match) fields[match[1]] = match[2].replace(/^"(.*)"$/, "$1").replace(/\\"/g, '"');
  }
  return fields;
}

async function domainPrefixes(ctx: RuleContext): Promise<Record<string, string>> {
  const config = (await ctx.readJSON(CONFIG)) as { domains?: Record<string, string> };
  return { ...BUILT_IN_PREFIXES, ...(config.domains ?? {}) };
}

export default {
  rules: {
    "adr-id-matches-file-name": {
      description:
        "Chaque ADR s'appelle {PRÉFIXE}-{NNNN}-{slug}.md, son id vaut {PRÉFIXE}-{NNNN} et le préfixe est celui de son domaine (décision § 2)",
      severity: "error",
      async check(ctx) {
        const adrs = await registryAdrs(ctx);
        if (adrs.length === 0) {
          ctx.report.violation({ message: `Aucun ADR {PRÉFIXE}-NNNN-*.md sous ${REGISTRY}/ : la règle ne peut rien vérifier.`, file: REGISTRY });
          return;
        }
        const prefixes = await domainPrefixes(ctx);
        for (const file of adrs) {
          const [, prefix, number] = ADR_FILE.exec(file.split("/").pop() as string) as RegExpExecArray;
          const fields = frontmatter(await ctx.readFile(file));
          if (fields === null) {
            ctx.report.violation({
              message: "L'ADR n'ouvre pas d'en-tête YAML en ligne 1 : Archgate ne le lit pas.",
              file,
              line: 1,
              fix: `Commencer le fichier par ---, id: ${prefix}-${number}, title, domain, rules, --- (gabarit ${REGISTRY}/gabarit/template.md).`,
            });
            continue;
          }
          if (fields.id !== `${prefix}-${number}`) {
            ctx.report.violation({
              message: `id ${fields.id ?? "(absent)"} : l'id d'un ADR reprend le début de son nom de fichier, ${prefix}-${number}.`,
              file,
              fix: `Écrire id: ${prefix}-${number} ; ne jamais renuméroter un ADR.`,
            });
          }
          const expected = prefixes[fields.domain ?? ""];
          if (expected !== prefix) {
            ctx.report.violation({
              message: `Le préfixe ${prefix} ne correspond pas au domaine ${fields.domain ?? "(absent)"} (préfixe attendu : ${expected ?? "domaine inconnu"}).`,
              file,
              fix: `Renommer le fichier et l'id avec le préfixe du domaine, ou corriger le domaine (${CONFIG} pour un domaine du projet).`,
            });
          }
        }
      },
    },

    "adr-number-is-unique": {
      description: "Un numéro à quatre chiffres ne désigne qu'un ADR, registre et archive compris (décision § 2)",
      severity: "error",
      async check(ctx) {
        const owners = new Map<string, string[]>();
        const files = [...(await ctx.glob(`${REGISTRY}/*.md`)), ...(await ctx.glob(`${REGISTRY}/archive/*.md`))];
        for (const file of files) {
          const number = /^(?:[A-Z]+-)?(\d{4})-/.exec(file.split("/").pop() as string)?.[1];
          if (number) owners.set(number, [...(owners.get(number) ?? []), file]);
        }
        for (const [number, paths] of [...owners].sort()) {
          if (paths.length > 1) {
            ctx.report.violation({
              message: `Le numéro ${number} est porté par plusieurs ADR : ${paths.sort().join(", ")}.`,
              file: paths[0],
              fix: "Donner au nouvel ADR le numéro suivant de l'index ; un numéro n'est jamais réutilisé.",
            });
          }
        }
      },
    },

    "adr-listed-in-index": {
      description: "Chaque ADR du registre a un lien dans l'index docs/architecture/adr/README.md",
      severity: "error",
      async check(ctx) {
        const index = await ctx.readFile(INDEX);
        for (const file of await registryAdrs(ctx)) {
          const name = file.split("/").pop() as string;
          if (!index.includes(`](${name})`)) {
            ctx.report.violation({
              message: `L'ADR ${name} n'a pas de lien dans l'index ${INDEX}.`,
              file,
              fix: `Ajouter une ligne | [${name.split("-").slice(0, 2).join("-")}](${name}) | … | à la table « Index » de ${INDEX}.`,
            });
          }
        }
      },
    },

    "adr-heading-matches-title": {
      description: "Le titre # de chaque ADR reprend exactement son title (décision § 2)",
      severity: "error",
      async check(ctx) {
        for (const file of await registryAdrs(ctx)) {
          const text = await ctx.readFile(file);
          const fields = frontmatter(text);
          if (fields === null || fields.title === undefined) continue;
          const heading = text.split(/\r?\n/).find((line) => line.startsWith("# "));
          if (heading !== `# ${fields.title}`) {
            ctx.report.violation({
              message: `Titre « ${heading ?? "(absent)"} » différent du title « ${fields.title} ».`,
              file,
              fix: `Écrire le titre # ${fields.title}.`,
            });
          }
        }
      },
    },

    "adr-has-archgate-sections": {
      description: "Chaque ADR du registre a les sections ## Decision et ## Do's and Don'ts lues par review-context (décision § 3)",
      severity: "warning",
      async check(ctx) {
        for (const file of await registryAdrs(ctx)) {
          const headings = new Set(
            (await ctx.readFile(file))
              .split(/\r?\n/)
              .map((line) => /^##\s+(.+?)\s*$/.exec(line)?.[1])
              .filter((heading): heading is string => heading !== undefined),
          );
          for (const section of REQUIRED_SECTIONS) {
            if (!headings.has(section)) {
              ctx.report.warning({
                message: `Section « ## ${section} » absente : archgate review-context n'en donnera pas le résumé aux agents.`,
                file,
                fix: `Titrer la section correspondante « ## ${section} » sans en changer le texte.`,
              });
            }
          }
        }
      },
    },

    "archgate-adrs-holds-only-gitkeep": {
      description: ".archgate/adrs/ ne contient que .gitkeep : aucun pont ni second registre (décision § 1)",
      severity: "error",
      async check(ctx) {
        const files = await ctx.glob(`${BRIDGE_DIRECTORY}/**/*`);
        for (const file of files.sort()) {
          if (file === `${BRIDGE_DIRECTORY}/.gitkeep`) continue;
          ctx.report.violation({
            message: `${file} : ${BRIDGE_DIRECTORY}/ ne contient que .gitkeep ; le registre unique est ${REGISTRY}/.`,
            file,
            fix: `Supprimer ce fichier ; écrire ou modifier l'ADR dans ${REGISTRY}/.`,
          });
        }
      },
    },
  },
} satisfies RuleSet;
