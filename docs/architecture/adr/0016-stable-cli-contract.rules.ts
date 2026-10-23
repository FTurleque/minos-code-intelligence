/// <reference path="../rules.d.ts" />

// Règle de l'ADR 0016 : « les codes de sortie font partie du contrat CLI ». Le contrat publié est la section
// « Codes de sortie » de docs/user/cli.md ; les codes du programme sont les constantes de CliExitCodes.java. Les deux
// sont lus, aucun n'est recopié. Contrôle propre à Archgate : CliExitCodesTest fige les valeurs dans le code, aucun
// gate ne les compare à la documentation.

const CODES = "minos-cli/src/main/java/com/minos/cli/support/CliExitCodes.java";
const REFERENCE = "docs/user/cli.md";
const SECTION = "## Codes de sortie";

export default {
  rules: {
    "exit-codes-match-cli-reference": {
      description:
        "Les constantes de CliExitCodes.java et la liste de la section « Codes de sortie » de docs/user/cli.md énoncent les mêmes codes",
      severity: "error",
      async check(ctx) {
        const source = await ctx.readFile(CODES);
        const declared = new Map<number, { name: string; line: number }>();
        source.split(/\r?\n/).forEach((text, index) => {
          const match = /^\s*public\s+static\s+final\s+int\s+([A-Z][A-Z0-9_]*)\s*=\s*(\d+)\s*;/.exec(text);
          if (match) declared.set(Number(match[2]), { name: match[1], line: index + 1 });
        });
        if (declared.size === 0) {
          ctx.report.violation({
            message: `Aucune constante « public static final int » dans ${CODES} : les codes de sortie ne peuvent pas être lus.`,
            file: CODES,
          });
          return;
        }

        const lines = (await ctx.readFile(REFERENCE)).split(/\r?\n/);
        const start = lines.findIndex((line) => line.trim() === SECTION);
        const open = start < 0 ? -1 : lines.findIndex((line, index) => index > start && /^```/.test(line));
        const close = open < 0 ? -1 : lines.findIndex((line, index) => index > open && /^```/.test(line));
        if (start < 0 || open < 0 || close < 0 || lines.slice(start + 1, open).some((line) => /^#{1,2}\s/.test(line))) {
          ctx.report.violation({
            message: `${REFERENCE} n'a pas de section « ${SECTION} » suivie d'un bloc qui liste les codes : le contrat publié est illisible.`,
            file: REFERENCE,
            line: start < 0 ? undefined : start + 1,
          });
          return;
        }
        const documented = new Map<number, number>();
        for (let index = open + 1; index < close; index++) {
          const match = /^\s*(\d+)\s+\S/.exec(lines[index]);
          if (match) documented.set(Number(match[1]), index + 1);
        }

        for (const [code, constant] of declared) {
          if (!documented.has(code)) {
            ctx.report.violation({
              message: `Le code de sortie ${code} (${constant.name}) n'est pas publié dans la section « Codes de sortie » de ${REFERENCE}.`,
              file: CODES,
              line: constant.line,
              fix: `Documenter le code ${code} dans ${REFERENCE}, ou retirer la constante.`,
            });
          }
        }
        for (const [code, line] of documented) {
          if (!declared.has(code)) {
            ctx.report.violation({
              message: `${REFERENCE} publie le code de sortie ${code}, qu'aucune constante de CliExitCodes.java ne déclare.`,
              file: REFERENCE,
              line,
              fix: `Déclarer le code ${code} dans ${CODES}, ou le retirer de la documentation.`,
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
