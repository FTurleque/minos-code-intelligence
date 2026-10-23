/// <reference path="../rules.d.ts" />

// Règles de l'ADR 0063 (format Archgate dans le registre arc42). Contrôles propres à Archgate : aucun gate Python
// ne lit l'en-tête ni les sections des ADR.
// - Décision § 2 : `id: ADR-NNNN`, les quatre chiffres du nom de fichier, en-tête YAML en ligne 1.
// - Décision § 3 : sections `Decision` et `Do's and Don'ts` (avertissement : l'ADR 0042 n'a pas de `## Decision` titrée).
// - Décision § 1 : `.archgate/adrs/` ne contient que `.gitkeep`.
// - Do « prendre le numéro suivant de l'index » et revue « figure dans README.md » : chaque ADR figure dans l'index.

const REGISTRY = "docs/architecture/adr";
const INDEX = `${REGISTRY}/README.md`;
const ADR_FILE = /^(\d{4})-[^/]+\.md$/;
const BRIDGE_DIRECTORY = ".archgate/adrs";
const REQUIRED_SECTIONS = ["Decision", "Do's and Don'ts"];

async function registryAdrs(ctx: RuleContext): Promise<string[]> {
  // Le registre seul : ni archive/ ni gabarit/, qu'Archgate ne lit pas.
  return (await ctx.glob(`${REGISTRY}/*.md`))
    .filter((file) => ADR_FILE.test(file.split("/").pop() as string))
    .sort();
}

function frontmatterId(text: string): string | undefined | null {
  // null : pas d'en-tête YAML en ligne 1 ; undefined : en-tête sans `id`.
  const lines = text.split(/\r?\n/);
  if (lines[0] !== "---") return null;
  const end = lines.indexOf("---", 1);
  if (end < 0) return null;
  for (const line of lines.slice(1, end)) {
    const match = /^id:\s*["']?([^"'\s#]+)["']?\s*(?:#.*)?$/.exec(line);
    if (match) return match[1];
  }
  return undefined;
}

export default {
  rules: {
    "adr-id-matches-file-number": {
      description:
        "Chaque ADR du registre ouvre en ligne 1 un en-tête YAML dont l'id vaut ADR- suivi des quatre chiffres du nom de fichier (décision § 2)",
      severity: "error",
      async check(ctx) {
        const adrs = await registryAdrs(ctx);
        if (adrs.length === 0) {
          ctx.report.violation({ message: `Aucun ADR NNNN-*.md sous ${REGISTRY}/ : la règle ne peut rien vérifier.`, file: REGISTRY });
          return;
        }
        for (const file of adrs) {
          const number = ADR_FILE.exec(file.split("/").pop() as string)?.[1];
          const expected = `ADR-${number}`;
          const id = frontmatterId(await ctx.readFile(file));
          if (id === null) {
            ctx.report.violation({
              message: "L'ADR n'ouvre pas d'en-tête YAML en ligne 1 : Archgate ne le lit pas.",
              file,
              line: 1,
              fix: `Commencer le fichier par ---, id: ${expected}, title, domain, rules, --- (gabarit ${REGISTRY}/gabarit/template.md).`,
            });
          } else if (id !== expected) {
            ctx.report.violation({
              message: `id ${id ?? "(absent)"} : l'id d'un ADR est ${expected}, d'après les quatre chiffres de son nom de fichier.`,
              file,
              fix: `Écrire id: ${expected} ; ne jamais renuméroter un ADR.`,
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
              fix: `Ajouter une ligne | [${name.slice(0, 4)}](${name}) | … | à la table « Index » de ${INDEX}.`,
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
