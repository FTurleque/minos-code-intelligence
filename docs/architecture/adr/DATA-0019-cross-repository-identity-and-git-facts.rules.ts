/// <reference path="../rules.d.ts" />

// ADR-0019, amendement AUD-SEC-10 : « Aucun outil MCP n'expose l'adresse » e-mail d'un auteur.
// Contrôle textuel propre à Archgate (aucun gate Python ne le fait ; le test McpAuthorEmailGuardTest
// de minos-app interroge le catalogue MCP réel). Toute mention de l'adresse d'auteur dans le code de
// production de minos-mcp est refusée : identifiant, accesseur ou clé JSON écrite en littéral. Les
// commentaires sont effacés ; les littéraux sont gardés, car une clé de réponse en est un.
// Limite : un enregistrement sérialisé en bloc sans nommer le champ échappe à la recherche textuelle ;
// le test McpAuthorEmailGuardTest le couvre.

const SOURCES = "minos-mcp/src/main/**/*";
const ADRESSE_AUTEUR = /author[\s_.-]{0,2}e[\s_-]?mails?/gi;

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

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}

export default {
  rules: {
    "mcp-exposes-no-author-email": {
      description:
        "Amendement AUD-SEC-10 : aucune mention de l'adresse e-mail d'auteur (authorEmail, author_email…) dans minos-mcp/src/main ; contrôle propre à Archgate",
      severity: "error",
      async check(ctx) {
        const fichiers = await ctx.glob(SOURCES);
        if (!fichiers.some((chemin) => chemin.endsWith(".java"))) {
          ctx.report.violation({
            message: `Aucune source Java trouvée sous ${SOURCES} : l'absence d'adresse d'auteur dans le MCP ne peut pas être vérifiée.`,
          });
          return;
        }
        for (const chemin of fichiers) {
          const brut = await ctx.readFile(chemin);
          const texte = chemin.endsWith(".java") ? sansCommentairesJava(brut) : brut;
          for (const trouve of texte.matchAll(ADRESSE_AUTEUR)) {
            ctx.report.violation({
              message: `« ${trouve[0]} » dans la surface MCP : aucun outil MCP n'expose l'adresse e-mail d'un auteur (ADR-0019, AUD-SEC-10).`,
              file: chemin,
              line: ligne(texte, trouve.index ?? 0),
              fix: "Retirer l'adresse de la réponse MCP ; elle reste réservée à git-activity --include-author-emails.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
