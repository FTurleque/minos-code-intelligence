/// <reference path="../rules.d.ts" />

// Règles de l'ADR 0043 (retrait des artefacts de jalon), décision § 1 et § 2 :
// - un gate permanent vit sous scripts/quality, scripts/architecture ou scripts/docs, ou est exécuté par
//   sous-processus depuis un gate vivant (ACTIVE_MILESTONE_GATES de check-post-mne.py, lu ici par son AST) ;
//   il ne porte jamais de numéro de jalon (`mNN`) dans son nom de fichier ni dans son répertoire ;
// - un workflow qui garde son numéro de jalon n'est admis que comme rejeu manuel délibéré (`workflow_dispatch`).
// Contrôles propres à Archgate : scripts/quality/check-milestone-artifact-references.py garde les références des
// scripts, pas leur nom.

const PERMANENT_DIRECTORIES = ["scripts/quality", "scripts/architecture", "scripts/docs"]; // décision § 1, « Permanent »
const POST_MNE = "scripts/remediation/check-post-mne.py";
const WORKFLOWS = ".github/workflows";
const MANUAL_REPLAY = "workflow_dispatch";
// Un segment `mNN` / `MNN` délimité à gauche (début, séparateur) et suivi d'autre chose qu'un chiffre.
const MILESTONE = /(?:^|[^A-Za-z0-9])[mM]\d{1,2}(?!\d)/;
const IGNORED_SEGMENTS = new Set(["__pycache__"]);

async function activeMilestoneGates(ctx: RuleContext): Promise<string[] | undefined> {
  const tree = await ctx.ast(POST_MNE, "python");
  for (const node of tree.body) {
    if (node._type !== "Assign") continue;
    const targets = node.targets as PythonAstNode[];
    if (targets.length !== 1 || targets[0].id !== "ACTIVE_MILESTONE_GATES") continue;
    const value = node.value as PythonAstNode;
    if (!["Tuple", "List"].includes(value._type)) return undefined;
    const paths = (value.elts as PythonAstNode[]).map((elt) =>
      elt._type === "Constant" && typeof elt.value === "string" ? elt.value : undefined,
    );
    return paths.every((path) => path !== undefined) ? (paths as string[]) : undefined;
  }
  return undefined;
}

function milestoneSegment(path: string): string | undefined {
  // Le nom de fichier et les répertoires sous scripts/ ; « scripts » lui-même n'en porte pas.
  return path
    .split("/")
    .slice(1)
    .find((segment) => MILESTONE.test(segment));
}

function triggersOf(on: YamlValue | undefined): string[] {
  if (typeof on === "string") return [on];
  if (Array.isArray(on)) return on.filter((item): item is string => typeof item === "string");
  if (on && typeof on === "object") return Object.keys(on);
  return [];
}

export default {
  rules: {
    "permanent-gates-carry-no-milestone-number": {
      description:
        "Aucun gate permanent (scripts/quality|architecture|docs, ou ACTIVE_MILESTONE_GATES de check-post-mne.py) ne porte de numéro de jalon mNN dans son nom ou son répertoire (contrôle propre à Archgate)",
      severity: "error",
      async check(ctx) {
        const indirect = await activeMilestoneGates(ctx);
        if (!indirect) {
          ctx.report.violation({
            message: `ACTIVE_MILESTONE_GATES introuvable ou non littéral dans ${POST_MNE} : les gates exécutés par sous-processus ne peuvent plus être lus.`,
            file: POST_MNE,
            fix: "Garder ce tuple littéral, ou mettre à jour la règle 0043 si le mécanisme a changé.",
          });
          return;
        }
        const permanent: string[] = [];
        for (const directory of PERMANENT_DIRECTORIES) {
          const files = (await ctx.glob(`${directory}/**/*`)).filter(
            (file) => !file.split("/").some((segment) => IGNORED_SEGMENTS.has(segment)),
          );
          if (files.length === 0) {
            ctx.report.violation({
              message: `${directory}/ est vide ou introuvable : la règle ne peut rien vérifier (l'ADR 0043 y range les gates permanents).`,
              file: directory,
            });
          }
          permanent.push(...files);
        }
        for (const file of [...new Set(permanent)].sort()) {
          const segment = milestoneSegment(file);
          if (segment) {
            ctx.report.violation({
              message: `Gate permanent nommé par un numéro de jalon (« ${segment} ») : l'ADR 0043 § 2 l'interdit.`,
              file,
              fix: "Renommer le fichier (ou son répertoire) sans numéro de jalon et mettre à jour ses appelants dans le même commit.",
            });
          }
        }
        for (const path of indirect) {
          const segment = milestoneSegment(path);
          if (segment) {
            ctx.report.violation({
              message: `${path} est exécuté par sous-processus à chaque PR (ACTIVE_MILESTONE_GATES) : c'est un gate permanent, il ne peut pas porter le numéro de jalon « ${segment} ».`,
              file: POST_MNE,
              fix: "Déplacer ce gate sous scripts/quality, scripts/architecture ou scripts/docs sans numéro de jalon, et mettre à jour le tuple.",
            });
          }
        }
      },
    },

    "milestone-workflows-are-manual-replays": {
      description:
        "Un workflow dont le nom porte un numéro de jalon n'est déclenché que par workflow_dispatch (rejeu manuel délibéré) ; sinon c'est un gate permanent (contrôle propre à Archgate)",
      severity: "error",
      async check(ctx) {
        const files = [...(await ctx.glob(`${WORKFLOWS}/*.yml`)), ...(await ctx.glob(`${WORKFLOWS}/*.yaml`))].sort();
        if (files.length === 0) {
          ctx.report.violation({ message: `Aucun workflow sous ${WORKFLOWS}/ : la règle ne peut rien vérifier.`, file: WORKFLOWS });
          return;
        }
        for (const file of files) {
          const name = file.split("/").pop() as string;
          if (!MILESTONE.test(name)) continue;
          const document = (await ctx.readYAML(file)).content;
          const on = document && typeof document === "object" && !Array.isArray(document) ? document.on : undefined;
          const triggers = triggersOf(on);
          const automatic = triggers.filter((trigger) => trigger !== MANUAL_REPLAY);
          if (triggers.length === 0 || automatic.length > 0) {
            ctx.report.violation({
              message: `Workflow de jalon déclenché par ${automatic.length > 0 ? automatic.join(", ") : "(aucun déclencheur lisible)"} : un gate permanent ne porte pas de numéro de jalon (ADR 0043 § 2).`,
              file,
              fix: `Le renommer sans numéro de jalon s'il garde un invariant, ou le limiter à ${MANUAL_REPLAY} s'il est un rejeu manuel du jalon.`,
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
