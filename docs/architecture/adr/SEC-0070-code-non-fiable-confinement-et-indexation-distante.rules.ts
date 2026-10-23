/// <reference path="../rules.d.ts" />

// ADR-0070 § 6 : WorkerSandboxQualification porte la limitation lisible par machine
// WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041, sortie publique stable à ne pas renommer
// (Don't). Second exécutant de scripts/docs/product-facts.py --check
// (check_sandbox_claims_match_the_code), qui reste le gate de la CI : une règle vérifie la
// limitation dans le code de production, l'autre que le gate l'exige toujours.

const LIMITATION = "WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041";
const CLASSE = "WorkerSandboxQualification.java";
const GATE = "scripts/docs/product-facts.py";

/** Efface les commentaires Java en gardant les littéraux (longueur et sauts de ligne conservés). */
function sansCommentairesJava(source: string): string {
  const out = source.split("");
  const effacer = (debut: number, fin: number) => {
    for (let k = debut; k < fin; k++) if (out[k] !== "\n" && out[k] !== "\r") out[k] = " ";
  };
  const n = source.length;
  let i = 0;
  while (i < n) {
    const c = source[i];
    if (c === "/" && source[i + 1] === "/") {
      let j = i;
      while (j < n && source[j] !== "\n") j++;
      effacer(i, j);
      i = j;
    } else if (c === "/" && source[i + 1] === "*") {
      const fin = source.indexOf("*/", i + 2);
      const j = fin < 0 ? n : fin + 2;
      effacer(i, j);
      i = j;
    } else if (source.startsWith('"""', i)) {
      let j = i + 3;
      while (j < n && !source.startsWith('"""', j)) j += source[j] === "\\" ? 2 : 1;
      i = Math.min(n, j + 3);
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
      i = Math.min(n, j + 1);
    } else {
      i++;
    }
  }
  return out.join("");
}

function contientLaLimitation(noeud: unknown): boolean {
  const n = noeud as { _type?: string; value?: unknown };
  return n?._type === "Constant" && typeof n.value === "string" && n.value.includes(LIMITATION);
}

export default {
  rules: {
    "decision-limitation-in-code": {
      description:
        "WorkerSandboxQualification porte le littéral public WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041, inchangé ; second exécutant de product-facts.py --check",
      severity: "error",
      async check(ctx) {
        const trouves = (await ctx.glob(`**/src/main/java/**/${CLASSE}`)).map((c) => c.replace(/\\/g, "/"));
        if (trouves.length !== 1) {
          ctx.report.violation({
            message: `${trouves.length} fichiers ${CLASSE} en production (attendu : un seul) : la limitation de l'ADR-0070 § 6 n'est pas localisable.`,
          });
          return;
        }
        const code = sansCommentairesJava(await ctx.readFile(trouves[0]));
        if (!code.includes(`"${LIMITATION}"`)) {
          ctx.report.violation({
            message: `Le littéral "${LIMITATION}" a disparu de ${CLASSE} (hors commentaires) : c'est une sortie publique stable que l'ADR-0070 interdit de renommer.`,
            file: trouves[0],
            fix: `Rétablir la limitation "${LIMITATION}" dans l'unique branche de rétrogradation de WorkerSandboxQualification.`,
          });
        }
      },
    },
    "product-facts-requires-limitation": {
      description:
        "Le gate product-facts.py exige toujours le littéral WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041 (comparaison in / not in lue dans son AST Python, commentaires exclus)",
      severity: "error",
      async check(ctx) {
        const arbre = await ctx.ast(GATE, "python");
        const exigences = ctx.findAstNodes(arbre, "Compare").filter((comparaison) => {
          const operandes = [comparaison.left, ...((comparaison.comparators as unknown[]) ?? [])];
          return operandes.some(contientLaLimitation);
        });
        if (exigences.length === 0) {
          ctx.report.violation({
            message: `${GATE} ne compare plus aucun texte au littéral ${LIMITATION} : la CI n'exige plus la limitation de l'ADR-0070 § 6.`,
            file: GATE,
            fix: `Rétablir dans check_sandbox_claims_match_the_code le contrôle '"${LIMITATION}"' not in qualification.`,
          });
        }
      },
    },
  },
} satisfies RuleSet;
