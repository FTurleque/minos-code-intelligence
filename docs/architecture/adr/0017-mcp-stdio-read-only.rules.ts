/// <reference path="../rules.d.ts" />

// ADR-0017 : le serveur MCP est en STDIO et en lecture seule ; aucune mutation de projet ou de code
// n'est introduite par ses tools. Contrôle textuel propre à Archgate (aucun gate Python ne le fait ;
// check-private-io.py interdit seulement les écritures brutes hors de sa liste de tolérance, pas les
// processus ni stdout). Les commentaires et les littéraux sont effacés avant la recherche.
//
// Exception unique : MinosMcpHandshakeProbe, client SDK de qualification de release (il lance le
// lanceur empaqueté et crée son MINOS_HOME) ; ce n'est pas le serveur. Elle ne vaut que tant que ce
// fichier reste un client : il importe io.modelcontextprotocol.client et jamais le côté serveur.

const SOURCES = "minos-mcp/src/main/java/**/*.java";
const SONDE_CLIENT = "MinosMcpHandshakeProbe.java";

const INTERDITS: { motif: RegExp; quoi: string }[] = [
  { motif: /\bProcessBuilder\b/g, quoi: "lancement de processus (ProcessBuilder)" },
  {
    motif: /\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\s*\(/g,
    quoi: "lancement de processus (Runtime.getRuntime().exec)",
  },
  {
    motif:
      /\bFiles\s*(?:\.|::)\s*(?:write|writeString|newOutputStream|newBufferedWriter|createFile|createDirector(?:y|ies)|createTemp(?:File|Directory)|create(?:Symbolic)?Link|delete|deleteIfExists|copy|move|setAttribute|setPosixFilePermissions|setLastModifiedTime|setOwner)\b/g,
    quoi: "écriture ou modification de fichier (java.nio.file.Files)",
  },
  {
    motif: /\bimport\s+static\s+java\s*\.\s*nio\s*\.\s*file\s*\.\s*Files\s*\.\s*[\w*]+\s*;/g,
    quoi: "import statique de java.nio.file.Files (masque les appels d'écriture)",
  },
  {
    motif: /\bnew\s+(?:FileOutputStream|FileWriter|RandomAccessFile)\b/g,
    quoi: "écriture de fichier (FileOutputStream, FileWriter ou RandomAccessFile)",
  },
  { motif: /\b(?:Asynchronous)?FileChannel\s*(?:\.|::)\s*open\b/g, quoi: "ouverture d'un canal de fichier" },
  { motif: /\.\s*(?:mkdirs?|createNewFile|renameTo|deleteOnExit)\s*\(/g, quoi: "modification de fichier (java.io.File)" },
  {
    motif: /\bPrivateLocalStorage\s*(?:\.|::)\s*(?:write|create|open|ensure|harden)\w*/g,
    quoi: "écriture par la primitive PrivateLocalStorage",
  },
  {
    motif: /\bDurableAtomicFile\s*(?:\.|::)\s*(?:publish|replace|delete\w*|move\w*|ensure\w*)/g,
    quoi: "écriture par la primitive DurableAtomicFile",
  },
];

const STDOUT = /\bSystem\s*\.\s*out\b|\bSystem\s*\.\s*setOut\s*\(|\bFileDescriptor\s*\.\s*out\b/g;
const TRANSPORT = /\bnew\s+StdioServerTransportProvider\s*\(/g;

/** Efface commentaires et littéraux (longueur et sauts de ligne conservés). */
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

function ligne(texte: string, index: number): number {
  let l = 1;
  for (let k = 0; k < index; k++) if (texte[k] === "\n") l++;
  return l;
}

function nomDeFichier(chemin: string): string {
  return chemin.replace(/\\/g, "/").split("/").pop() ?? chemin;
}

/** La sonde n'est exemptée que si elle reste un client SDK, jamais le serveur. */
function estSondeClient(chemin: string, code: string): boolean {
  return (
    nomDeFichier(chemin) === SONDE_CLIENT &&
    /\bimport\s+io\s*\.\s*modelcontextprotocol\s*\.\s*client\s*\./.test(code) &&
    !/\bimport\s+io\s*\.\s*modelcontextprotocol\s*\.\s*server\s*\./.test(code)
  );
}

/** Vrai si l'index tombe dans la liste d'arguments d'un `new StdioServerTransportProvider(`. */
function dansLeTransport(code: string, index: number): boolean {
  for (const appel of code.matchAll(TRANSPORT)) {
    const ouverture = (appel.index ?? 0) + appel[0].length - 1;
    if (ouverture > index) break;
    let profondeur = 0;
    for (let k = ouverture; k < code.length; k++) {
      if (code[k] === "(") profondeur++;
      else if (code[k] === ")" && --profondeur === 0) {
        if (index < k) return true;
        break;
      }
    }
  }
  return false;
}

async function sourcesServeur(ctx: RuleContext): Promise<{ chemin: string; code: string }[]> {
  const fichiers = await ctx.glob(SOURCES);
  const sources: { chemin: string; code: string }[] = [];
  for (const chemin of fichiers) {
    const code = masquerJava(await ctx.readFile(chemin));
    if (!estSondeClient(chemin, code)) sources.push({ chemin, code });
  }
  return sources;
}

export default {
  rules: {
    "mcp-sans-processus-ni-ecriture": {
      description:
        "Lecture seule : aucun lancement de processus ni écriture de fichier dans minos-mcp/src/main/java (hors sonde client de release) ; contrôle propre à Archgate",
      severity: "error",
      async check(ctx) {
        const sources = await sourcesServeur(ctx);
        if (sources.length === 0) {
          ctx.report.violation({
            message: `Aucune source trouvée sous ${SOURCES} : la lecture seule du serveur MCP ne peut pas être vérifiée.`,
          });
          return;
        }
        for (const { chemin, code } of sources) {
          for (const { motif, quoi } of INTERDITS) {
            for (const trouve of code.matchAll(motif)) {
              ctx.report.violation({
                message: `${quoi} dans le serveur MCP : « ${trouve[0].replace(/\s+/g, " ")} ». ADR-0017 : les tools MCP sont en lecture seule.`,
                file: chemin,
                line: ligne(code, trouve.index ?? 0),
                fix: "Déléguer à un service MINOS existant en lecture, ou sortir cette opération de la surface MCP.",
              });
            }
          }
        }
      },
    },
    "mcp-stdout-reserve-au-transport": {
      description:
        "STDIO : dans minos-mcp/src/main/java, System.out n'apparaît que comme argument de new StdioServerTransportProvider(...) ; contrôle propre à Archgate",
      severity: "error",
      async check(ctx) {
        for (const { chemin, code } of await sourcesServeur(ctx)) {
          for (const trouve of code.matchAll(STDOUT)) {
            const index = trouve.index ?? 0;
            if (/\bSystem\s*\.\s*out\b/.test(trouve[0]) && dansLeTransport(code, index)) continue;
            ctx.report.violation({
              message: `« ${trouve[0].replace(/\s+/g, " ")} » hors du transport STDIO : stdout est réservé au protocole MCP (ADR-0017).`,
              file: chemin,
              line: ligne(code, index),
              fix: "Journaliser sur stderr par System.Logger ; seul StdioServerTransportProvider écrit sur System.out.",
            });
          }
        }
      },
    },
  },
} satisfies RuleSet;
