/// <reference path="../rules.d.ts" />

// ADR 0057, décision 1 et « Don't » : aucune migration JPMS. Un descripteur module-info.java dans un module du
// reactor serait le premier pas de cette migration. Aucun gate Python ne le refuse : contrôle propre à Archgate.
// La liste des modules est lue dans les <modules> du POM racine, jamais recopiée ici.

const ROOT_POM = "pom.xml";

function reactorModules(pom: string): string[] {
  const withoutComments = pom.replace(/<!--[\s\S]*?-->/g, "");
  return [...withoutComments.matchAll(/<module>\s*([^<]*?)\s*<\/module>/g)].map((match) =>
    match[1].replace(/\/+$/, ""),
  );
}

export default {
  rules: {
    "no-jpms-module-descriptor": {
      description:
        "Décision 1 : aucune migration JPMS, donc aucun module-info.java dans les sources d'un module du reactor (contrôle propre à Archgate)",
      severity: "error",
      async check(ctx) {
        const modules = reactorModules(await ctx.readFile(ROOT_POM));
        if (modules.length === 0) {
          ctx.report.violation({
            message: `Aucun <module> utilisable dans ${ROOT_POM} : l'absence de JPMS (ADR 0057) ne peut pas être vérifiée.`,
            file: ROOT_POM,
          });
          return;
        }
        const descriptors = [
          ...(await ctx.glob("*/src/**/module-info.java")),
          ...(await ctx.glob("src/**/module-info.java")),
        ].map((file) => file.replace(/\\/g, "/"));
        for (const file of descriptors) {
          const owner = file.startsWith("src/") ? "<racine>" : file.split("/")[0];
          if (owner !== "<racine>" && !modules.includes(owner)) continue;
          ctx.report.violation({
            message: `${file} déclare un module JPMS dans ${owner} : l'ADR 0057 (décision 1) exclut toute migration JPMS.`,
            file,
            fix: "Supprimer ce descripteur ; adopter JPMS demande un nouvel ADR qui remplace la décision 1 de l'ADR 0057.",
          });
        }
      },
    },
  },
} satisfies RuleSet;
