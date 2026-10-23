/// <reference path="../rules.d.ts" />

// ADR 0027, décision 2 et « Don't » : le plugin minos-intellij est un client externe qui ne déclare aucune
// dépendance com.minos:*. La première règle lit les fichiers de build Gradle du plugin (scripts, verrous de
// dépendances, catalogues de versions, métadonnées de vérification) ; elle est plus large que le grep du workflow
// intellij-plugin.yml, qui ne voit que `implementation("com.minos:`. La seconde refuse, dans les sources du
// plugin, tout import d'un type MINOS hors de com.minos.intellij : sans dépendance com.minos:*, un tel type ne
// peut venir que d'un couplage interdit. Aucun gate Python ne fait ces contrôles.

const PLUGIN = "minos-intellij";
const BUILD_FILES = [
  `${PLUGIN}/*.gradle.kts`,
  `${PLUGIN}/*.gradle`,
  `${PLUGIN}/*.lockfile`,
  `${PLUGIN}/gradle/*.toml`,
  `${PLUGIN}/gradle/verification-metadata.xml`,
];
// Une coordonnée com.minos:<artefact>, un argument nommé `group = "com.minos",` (Kotlin DSL ou table TOML en ligne),
// ou un composant `<component group="com.minos"` des métadonnées de vérification.
const MINOS_ARTIFACT = /\bcom\.minos:|\bgroup\s*=\s*"com\.minos"\s*,|<component\s+group="com\.minos"/;
const COMMENT_LINE = /^\s*(\/\/|#|\*|\/\*|<!--)/;
const FOREIGN_IMPORT = /^\s*import\s+(?:static\s+)?(com\.minos\.(?!intellij(?:[.;\s]))[\w.*]+)/;

export default {
  rules: {
    "plugin-declares-no-minos-artifact": {
      description:
        "Décision 2 : aucun fichier de build Gradle de minos-intellij ne déclare ni ne verrouille un artefact com.minos:* (second exécutant, élargi, du grep du workflow intellij-plugin.yml)",
      severity: "error",
      async check(ctx) {
        const scripts = await ctx.glob(`${PLUGIN}/build.gradle.kts`);
        if (scripts.length === 0) {
          ctx.report.violation({
            message: `${PLUGIN}/build.gradle.kts est introuvable : l'absence de dépendance com.minos:* (ADR 0027) ne peut pas être vérifiée.`,
          });
          return;
        }
        for (const pattern of BUILD_FILES) {
          for (const match of await ctx.grepFiles(MINOS_ARTIFACT, pattern)) {
            if (COMMENT_LINE.test(match.content)) continue;
            ctx.report.violation({
              message: `${match.file}:${match.line} déclare un artefact MINOS (« ${match.content.trim()} ») : le plugin IntelliJ est un client externe sans dépendance com.minos:* (ADR 0027).`,
              file: match.file,
              line: match.line,
              fix: "Retirer la dépendance et passer par la CLI MINOS et son protocole minos-ide versionné (ADR 0027).",
            });
          }
        }
      },
    },
    "plugin-imports-only-its-own-package": {
      description:
        "Décision 2 : les sources de minos-intellij n'importent aucun type com.minos.* hors de com.minos.intellij (contrôle propre à Archgate)",
      severity: "error",
      async check(ctx) {
        const sources = await ctx.glob(`${PLUGIN}/src/**/*.java`);
        if (sources.length === 0) {
          ctx.report.violation({
            message: `Aucune source Java sous ${PLUGIN}/src : l'absence d'import MINOS (ADR 0027) ne peut pas être vérifiée.`,
          });
          return;
        }
        for (const match of await ctx.grepFiles(FOREIGN_IMPORT, `${PLUGIN}/src/**/*.java`)) {
          const imported = FOREIGN_IMPORT.exec(match.content)?.[1] ?? match.content.trim();
          ctx.report.violation({
            message: `${match.file}:${match.line} importe ${imported}, un type MINOS hors de com.minos.intellij : le plugin ne connaît MINOS que par la CLI (ADR 0027).`,
            file: match.file,
            line: match.line,
            fix: "Remplacer cet usage par un appel à la CLI MINOS (commande JSON du protocole minos-ide) ou par un type propre au plugin sous com.minos.intellij.",
          });
        }
      },
    },
  },
} satisfies RuleSet;
