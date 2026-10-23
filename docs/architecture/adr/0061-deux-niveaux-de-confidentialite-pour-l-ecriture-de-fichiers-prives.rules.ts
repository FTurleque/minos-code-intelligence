/// <reference path="../rules.d.ts" />

// ADR-0061 : deux niveaux, trois ouvertures en flux dans PrivateLocalStorage, et « aucun site brut
// nouveau dans PrivateLocalStorage » : la classe « garde ses cinq entrées » de tolérance, les
// ouvertures réutilisant ses sites bruts (§ Consequences). Le gate check-private-io.py reste celui
// de la CI : il compte les occurrences par entrée (cliquet des maxima) mais accepte une entrée
// ajoutée ; la règle complète ce gate en figeant le nombre d'entrées que l'ADR énonce. La liste de
// tolérance est celle que nomme le script (constante ALLOWLIST), jamais un chemin recopié.

const GATE = "scripts/architecture/check-private-io.py";
const CLASSE = "PrivateLocalStorage.java";
// Nombre énoncé par l'ADR (« PrivateLocalStorage garde ses cinq entrées »).
const ENTREES_DECLAREES = 5;
// Les trois ouvertures en flux de l'option B retenue (section Decision, Do's and Don'ts).
const OUVERTURES = ["openPrivateOutputStream", "createPrivateOutputStream", "openNewTreeFile"];

/** Efface commentaires et littéraux Java (longueur et sauts de ligne conservés). */
function masquerJava(source: string): string {
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
      j = Math.min(n, j + 3);
      effacer(i + 3, j - 3);
      i = j;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < n && source[j] !== c && source[j] !== "\n") j += source[j] === "\\" ? 2 : 1;
      j = Math.min(n, j + 1);
      effacer(i + 1, j - 1);
      i = j;
    } else {
      i++;
    }
  }
  return out.join("");
}

function normaliser(chemin: string): string {
  return chemin.replace(/\\/g, "/");
}

/** Le fichier unique PrivateLocalStorage.java du code de production, ou un message d'erreur. */
async function classePrimitive(ctx: RuleContext): Promise<string | null> {
  const trouves = (await ctx.glob(`**/src/main/java/**/${CLASSE}`)).map(normaliser);
  if (trouves.length !== 1) {
    ctx.report.violation({
      message: `${trouves.length} fichiers ${CLASSE} en production (attendu : un seul) : la primitive de l'ADR-0061 n'est pas identifiable.`,
    });
    return null;
  }
  return trouves[0];
}

export default {
  rules: {
    "private-local-storage-garde-ses-entrees": {
      description:
        "Aucun site brut nouveau dans PrivateLocalStorage : la liste de tolérance nommée par check-private-io.py porte exactement les entrées que l'ADR énonce ; complète le cliquet des maxima de ce gate",
      severity: "error",
      async check(ctx) {
        const script = await ctx.readFile(GATE);
        const nom = /^ALLOWLIST\s*=.*"([^"]+\.json)"/m.exec(script)?.[1];
        if (!nom) {
          ctx.report.violation({
            message: `La constante ALLOWLIST est introuvable dans ${GATE} : la liste de tolérance ne peut pas être lue.`,
            file: GATE,
          });
          return;
        }
        const liste = `${GATE.slice(0, GATE.lastIndexOf("/"))}/${nom}`;
        const classe = await classePrimitive(ctx);
        if (!classe) return;
        const entrees = await ctx.readJSON(liste);
        if (!Array.isArray(entrees)) {
          ctx.report.violation({ message: `${liste} n'est pas un tableau JSON d'entrées.`, file: liste });
          return;
        }
        const propres = entrees.filter(
          (entree) => typeof entree?.file === "string" && normaliser(entree.file) === classe,
        );
        if (propres.length !== ENTREES_DECLAREES) {
          const methodes = propres.map((entree) => entree.method).join(", ") || "aucune";
          ctx.report.violation({
            message: `${liste} porte ${propres.length} entrées pour ${CLASSE} (${methodes}) ; l'ADR-0061 en énonce ${ENTREES_DECLAREES} : aucun site brut nouveau dans PrivateLocalStorage.`,
            file: liste,
            fix: "Réutiliser un site brut existant de PrivateLocalStorage, ou amender l'ADR-0061 avant de changer la liste.",
          });
        }
      },
    },
    "trois-ouvertures-en-flux": {
      description:
        "Les trois ouvertures en flux de l'option B (openPrivateOutputStream, createPrivateOutputStream, openNewTreeFile) sont déclarées public static dans PrivateLocalStorage ; contrôle propre à Archgate",
      severity: "error",
      async check(ctx) {
        const classe = await classePrimitive(ctx);
        if (!classe) return;
        const code = masquerJava(await ctx.readFile(classe));
        for (const nom of OUVERTURES) {
          const declaration = new RegExp(`\\bpublic\\s+static\\s+[\\w<>\\[\\],.?\\s]*?\\b${nom}\\s*\\(`);
          if (!declaration.test(code)) {
            ctx.report.violation({
              message: `L'ouverture en flux ${nom} n'est plus déclarée public static dans ${CLASSE} : l'ADR-0061 en retient trois (niveaux durci et arbre privé).`,
              file: classe,
              fix: `Rétablir ${nom}, ou amender l'ADR-0061 si le niveau change.`,
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
