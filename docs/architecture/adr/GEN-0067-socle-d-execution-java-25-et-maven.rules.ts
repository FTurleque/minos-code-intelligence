/// <reference path="../rules.d.ts" />

// Règles de l'ADR 0067 (socle d'exécution).
//
// Décision § 2 : `maven.compiler.release` du POM racine est la source de vérité de la version Java ; l'enforcer, les
// `java-version:` des workflows, les images `eclipse-temurin`, TOOLCHAIN_POLICY.md, AGENTS.md et openspec/config.yaml
// énoncent la même. Décision § 3 : Java 21 seulement dans la liste fermée des étapes du plugin.
// Ces quatre premières règles sont un second exécutant des règles 4 à 7 de scripts/quality/check-toolchain-baseline.py,
// qui reste le gate de la CI : elles lisent dans ce script (par son AST) les chemins, les expressions régulières et les
// listes fermées, et dans le POM la version ; elles ne recopient ni version, ni liste.
//
// Décision § 7 : aucun framework serveur (Spring, Quarkus, Micronaut) dans le cœur. Contrôle propre à Archgate.

const GATE = "scripts/quality/check-toolchain-baseline.py";
const GATE_CLI = "scripts/quality/gate_cli.py";

type Py = PythonAstNode;

async function constantsOf(ctx: RuleContext, file: string): Promise<Map<string, Py>> {
  const tree = await ctx.ast(file, "python");
  const constants = new Map<string, Py>();
  for (const node of tree.body) {
    if (node._type !== "Assign") continue;
    const targets = node.targets as Py[];
    if (targets.length === 1 && targets[0]._type === "Name") constants.set(targets[0].id as string, node.value as Py);
  }
  return constants;
}

function stringOf(node: Py | undefined): string | undefined {
  return node && node._type === "Constant" && typeof node.value === "string" ? node.value : undefined;
}

function numberOf(node: Py | undefined): number | undefined {
  return node && node._type === "Constant" && typeof node.value === "number" ? node.value : undefined;
}

/** `re.compile(r"...", re.MULTILINE?)` traduit en RegExp JavaScript (`(?P<nom>` devient `(?<nom>`). */
function regexOf(node: Py | undefined, everyMatch: boolean): RegExp | undefined {
  if (!node || node._type !== "Call") return undefined;
  const func = node.func as Py;
  if (func._type !== "Attribute" || func.attr !== "compile") return undefined;
  const args = node.args as Py[];
  const source = stringOf(args[0]);
  if (source === undefined) return undefined;
  const multiline = args.slice(1).some((arg) => arg._type === "Attribute" && arg.attr === "MULTILINE");
  return new RegExp(source.replace(/\(\?P</g, "(?<"), (everyMatch ? "g" : "") + (multiline ? "m" : ""));
}

/** Clés `(workflow, job)` -> nombre d'étapes d'un dictionnaire littéral du gate. */
function stepTableOf(node: Py | undefined): Map<string, number> | undefined {
  if (!node || node._type !== "Dict") return undefined;
  const table = new Map<string, number>();
  const keys = node.keys as Py[];
  const values = node.values as Py[];
  for (let index = 0; index < keys.length; index++) {
    const elts = (keys[index]?.elts ?? []) as Py[];
    const workflow = stringOf(elts[0]);
    const job = stringOf(elts[1]);
    const count = numberOf(values[index]);
    if (workflow === undefined || job === undefined || count === undefined) return undefined;
    table.set(`${workflow}\u0000${job}`, count);
  }
  return table;
}

function stringsOf(node: Py | undefined): string[] | undefined {
  if (!node) return undefined;
  if (node._type === "Call") return stringsOf((node.args as Py[])[0]);
  if (!["Tuple", "List", "Set"].includes(node._type)) return undefined;
  const values = (node.elts as Py[]).map(stringOf);
  return values.every((value) => value !== undefined) ? (values as string[]) : undefined;
}

function lineOf(text: string, index: number): number {
  return text.slice(0, index).split("\n").length;
}

interface Baseline {
  constants: Map<string, Py>;
  pom: string;
  pomText: string;
  version: string;
}

/** La version de référence `N`, lue dans le POM racine par l'expression RELEASE du gate ; undefined après violation. */
async function baselineOf(ctx: RuleContext): Promise<Baseline | undefined> {
  const constants = await constantsOf(ctx, GATE);
  const pom = stringOf(constants.get("POM"));
  const release = regexOf(constants.get("RELEASE"), true);
  if (!pom || !release) {
    ctx.report.violation({
      message: `POM ou RELEASE introuvable dans ${GATE} : la source de vérité de la version Java n'est plus lisible.`,
      file: GATE,
      fix: "Mettre à jour la règle 0067 avec le nouveau nom de ces constantes.",
    });
    return undefined;
  }
  const pomText = await ctx.readFile(pom);
  const releases = [...pomText.matchAll(release)].map((match) => (match.groups?.version ?? "").trim());
  if (releases.length !== 1 || !/^\d+$/.test(releases[0])) {
    ctx.report.violation({
      message: `${pom} doit énoncer exactement un maven.compiler.release numérique, trouvé ${releases.length}.`,
      file: pom,
    });
    return undefined;
  }
  return { constants, pom, pomText, version: releases[0] };
}

function missingConstant(ctx: RuleContext, names: string): void {
  ctx.report.violation({
    message: `Constante(s) ${names} introuvable(s) ou non littérale(s) dans ${GATE} : la règle ne peut pas lire ce que le gate contrôle.`,
    file: GATE,
    fix: "Garder ces constantes littérales dans le gate, ou mettre à jour la règle 0067.",
  });
}

const FRAMEWORK_GROUPS: Record<string, RegExp> = {
  // Décision § 7 : « Spring, Quarkus, Micronaut ».
  Spring: /^org\.springframework(\.|$)/,
  Quarkus: /^io\.quarkus(\.|$)/,
  Micronaut: /^io\.micronaut(\.|$)/,
};

export default {
  rules: {
    "enforcer-matches-java-release": {
      description:
        "Maven Enforcer exige exactement [N,N+1) pour N = maven.compiler.release du POM racine (second exécutant de check-toolchain-baseline.py, règle 4)",
      severity: "error",
      async check(ctx) {
        const baseline = await baselineOf(ctx);
        if (!baseline) return;
        const enforced = regexOf(baseline.constants.get("ENFORCED"), true);
        if (!enforced) return missingConstant(ctx, "ENFORCED");
        const expected = `[${baseline.version},${Number(baseline.version) + 1})`;
        const matches = [...baseline.pomText.matchAll(enforced)];
        if (matches.length === 0) {
          ctx.report.violation({
            message: `${baseline.pom} n'impose aucune version Java par l'enforcer (attendu ${expected}).`,
            file: baseline.pom,
            fix: `Ajouter <requireJavaVersion><version>${expected}</version></requireJavaVersion>.`,
          });
        }
        for (const match of matches) {
          const range = (match.groups?.range ?? "").trim();
          if (range !== expected) {
            ctx.report.violation({
              message: `${baseline.pom} impose Java ${range}, alors que le POM compile pour ${baseline.version} (attendu ${expected}).`,
              file: baseline.pom,
              line: lineOf(baseline.pomText, match.index ?? 0),
              fix: `Écrire ${expected}, ou changer maven.compiler.release dans la même PR.`,
            });
          }
        }
      },
    },

    "workflow-java-steps-match-release": {
      description:
        "Chaque java-version: de workflow vaut N, hors liste fermée PLUGIN_JAVA_STEPS (Java 21) et PREVIOUS_JAVA_STEPS du gate (second exécutant de check-toolchain-baseline.py, règle 5)",
      severity: "error",
      async check(ctx) {
        const baseline = await baselineOf(ctx);
        if (!baseline) return;
        const c = baseline.constants;
        const workflowsDir = stringOf(c.get("WORKFLOWS"));
        const job = regexOf(c.get("JOB"), false);
        const javaStep = regexOf(c.get("JAVA_STEP"), false);
        const pluginJava = stringOf(c.get("PLUGIN_JAVA"));
        const pluginSteps = stepTableOf(c.get("PLUGIN_JAVA_STEPS"));
        const previousJava = stringOf(c.get("PREVIOUS_JAVA"));
        const previousSteps = stepTableOf(c.get("PREVIOUS_JAVA_STEPS"));
        if (!workflowsDir || !job || !javaStep || !pluginJava || !pluginSteps || !previousJava || !previousSteps) {
          return missingConstant(ctx, "WORKFLOWS, JOB, JAVA_STEP, PLUGIN_JAVA(_STEPS) ou PREVIOUS_JAVA(_STEPS)");
        }
        const version = baseline.version;
        const files = [
          ...(await ctx.glob(`${workflowsDir}/*.yml`)),
          ...(await ctx.glob(`${workflowsDir}/*.yaml`)),
        ].sort();
        for (const file of files) {
          const name = file.split("/").pop() as string;
          const lines = (await ctx.readFile(file)).split(/\r?\n/);
          let current = "";
          const counted = { plugin: new Map<string, number>(), previous: new Map<string, number>() };
          lines.forEach((line, index) => {
            const declared = job.exec(line);
            if (declared) {
              current = declared.groups?.job ?? "";
              return;
            }
            const step = javaStep.exec(line);
            if (!step) return;
            const stated = step.groups?.version ?? "";
            const key = `${name}\u0000${current}`;
            if (stated === pluginJava && pluginJava !== version && pluginSteps.has(key)) {
              counted.plugin.set(current, (counted.plugin.get(current) ?? 0) + 1);
            } else if (stated === previousJava && previousJava !== version && previousSteps.has(key)) {
              counted.previous.set(current, (counted.previous.get(current) ?? 0) + 1);
            } else if (stated !== version) {
              ctx.report.violation({
                message: `Ce workflow installe Java ${stated}, alors que le POM compile pour ${version}.`,
                file,
                line: index + 1,
                fix: `Écrire java-version: '${version}' ; Java ${pluginJava} n'est admis que dans les étapes du plugin listées par PLUGIN_JAVA_STEPS de ${GATE}.`,
              });
            }
          });
          for (const [table, allowedJava, steps, label] of [
            [counted.plugin, pluginJava, pluginSteps, "PLUGIN_JAVA_STEPS"],
            [counted.previous, previousJava, previousSteps, "PREVIOUS_JAVA_STEPS"],
          ] as const) {
            for (const [jobName, count] of table) {
              const allowed = steps.get(`${name}\u0000${jobName}`) ?? 0;
              if (count > allowed) {
                ctx.report.violation({
                  message: `${file}, job \`${jobName}\` : ${count} étapes Java ${allowedJava}, la liste fermée ${label} de ${GATE} en admet ${allowed}.`,
                  file,
                  fix: `Retirer l'étape en trop, ou relever ${label} dans ${GATE} si c'est voulu.`,
                });
              }
            }
          }
        }
      },
    },

    "temurin-images-match-release": {
      description:
        "Chaque FROM eclipse-temurin: d'un Dockerfile du dépôt a le majeur N (second exécutant de check-toolchain-baseline.py, règle 6)",
      severity: "error",
      async check(ctx) {
        const baseline = await baselineOf(ctx);
        if (!baseline) return;
        const temurin = regexOf(baseline.constants.get("TEMURIN"), false);
        const skipped = stringsOf(baseline.constants.get("SKIPPED_DIRECTORIES"));
        const nested = stringsOf((await constantsOf(ctx, GATE_CLI)).get("NESTED_CHECKOUT_DIRECTORIES"));
        if (!temurin || !skipped || !nested) {
          return missingConstant(ctx, `TEMURIN, SKIPPED_DIRECTORIES (${GATE}) ou NESTED_CHECKOUT_DIRECTORIES (${GATE_CLI})`);
        }
        const files = (await ctx.glob("**/Dockerfile*")).filter((file) => {
          const parts = file.split("/");
          return (
            parts[parts.length - 1].startsWith("Dockerfile") &&
            !parts.slice(0, -1).some((part) => skipped.includes(part)) &&
            !nested.some((prefix) => file.startsWith(`${prefix}/`))
          );
        });
        for (const file of files.sort()) {
          const lines = (await ctx.readFile(file)).split(/\r?\n/);
          lines.forEach((line, index) => {
            const image = temurin.exec(line);
            if (image && image.groups?.major !== baseline.version) {
              ctx.report.violation({
                message: `Cette image part d'eclipse-temurin ${image.groups?.major}, alors que le POM compile pour ${baseline.version}.`,
                file,
                line: index + 1,
                fix: `Partir d'eclipse-temurin:${baseline.version} (tag et digest).`,
              });
            }
          });
        }
      },
    },

    "documents-state-java-release": {
      description:
        "TOOLCHAIN_POLICY.md énonce et date N, AGENTS.md et openspec/config.yaml énoncent N (second exécutant de check-toolchain-baseline.py, règle 7)",
      severity: "error",
      async check(ctx) {
        const baseline = await baselineOf(ctx);
        if (!baseline) return;
        const c = baseline.constants;
        const version = baseline.version;
        const expectedRange = `[${version},${Number(version) + 1})`;
        const policy = stringOf(c.get("POLICY"));
        const agents = stringOf(c.get("AGENTS"));
        const config = stringOf(c.get("OPENSPEC_CONFIG"));
        const policyJava = regexOf(c.get("POLICY_JAVA"), true);
        const policySupport = regexOf(c.get("POLICY_SUPPORT"), true);
        const agentsJava = regexOf(c.get("AGENTS_JAVA"), true);
        const configJava = regexOf(c.get("CONFIG_JAVA"), true);
        if (!policy || !agents || !config || !policyJava || !policySupport || !agentsJava || !configJava) {
          return missingConstant(ctx, "POLICY, AGENTS, OPENSPEC_CONFIG, POLICY_JAVA, POLICY_SUPPORT, AGENTS_JAVA ou CONFIG_JAVA");
        }
        const policyText = await ctx.readFile(policy);
        const stated = [...policyText.matchAll(policyJava)];
        if (stated.length === 0) {
          ctx.report.violation({
            message: `${policy} n'énonce pas la baseline Java du produit (attendu « Java: **${version}**, enforced as \`${expectedRange}\` »).`,
            file: policy,
          });
        }
        for (const match of stated) {
          if (match.groups?.version !== version || match.groups?.range !== expectedRange) {
            ctx.report.violation({
              message: `${policy} énonce Java ${match.groups?.version} imposé par ${match.groups?.range}, alors que le POM compile pour ${version}.`,
              file: policy,
              line: lineOf(policyText, match.index ?? 0),
              fix: `Écrire Java **${version}**, enforced as \`${expectedRange}\`.`,
            });
          }
        }
        const supported = [...policyText.matchAll(policySupport)];
        if (supported.length === 0) {
          ctx.report.violation({
            message: `${policy} ne date pas la fin de support de la baseline Java (attendu « Java support: ... **${version}** ... until [at least] **YYYY-MM** »).`,
            file: policy,
          });
        }
        for (const match of supported) {
          if (match.groups?.version !== version) {
            ctx.report.violation({
              message: `${policy} date le support de Java ${match.groups?.version}, alors que le POM compile pour ${version}.`,
              file: policy,
              line: lineOf(policyText, match.index ?? 0),
            });
          }
        }
        for (const [file, pattern] of [
          [agents, agentsJava],
          [config, configJava],
        ] as const) {
          const text = await ctx.readFile(file);
          const matches = [...text.matchAll(pattern)];
          if (matches.length === 0) {
            ctx.report.violation({ message: `${file} n'énonce pas la baseline Java du produit.`, file });
          }
          for (const match of matches) {
            if ((match.groups?.version ?? "").trim() !== version) {
              ctx.report.violation({
                message: `${file} énonce Java ${match.groups?.version} pour le produit, alors que le POM compile pour ${version}.`,
                file,
                line: lineOf(text, match.index ?? 0),
                fix: `Écrire ${version}, ou changer maven.compiler.release dans la même PR.`,
              });
            }
          }
        }
      },
    },

    "no-server-framework-in-poms": {
      description:
        "Aucun POM du reactor ne déclare Spring, Quarkus ni Micronaut (décision § 7 ; contrôle propre à Archgate, aucun gate Python ne le fait)",
      severity: "error",
      async check(ctx) {
        const nested = stringsOf((await constantsOf(ctx, GATE_CLI)).get("NESTED_CHECKOUT_DIRECTORIES"));
        if (!nested) return missingConstant(ctx, `NESTED_CHECKOUT_DIRECTORIES (${GATE_CLI})`);
        const poms = (await ctx.glob("**/pom.xml")).filter(
          (file) => !file.split("/").includes("target") && !nested.some((prefix) => file.startsWith(`${prefix}/`)),
        );
        if (poms.length === 0) {
          ctx.report.violation({ message: "Aucun pom.xml trouvé : la règle ne peut rien vérifier.", file: "pom.xml" });
          return;
        }
        for (const file of poms.sort()) {
          const text = (await ctx.readFile(file)).replace(/<!--[\s\S]*?-->/g, (comment) => comment.replace(/[^\n]/g, " "));
          for (const match of text.matchAll(/<groupId>\s*([^<\s]+)\s*<\/groupId>/g)) {
            const groupId = match[1];
            for (const [framework, pattern] of Object.entries(FRAMEWORK_GROUPS)) {
              if (pattern.test(groupId)) {
                ctx.report.violation({
                  message: `${file} déclare ${groupId} (${framework}) : l'ADR 0067 exclut tout framework serveur du cœur.`,
                  file,
                  line: lineOf(text, match.index ?? 0),
                  fix: "Retirer cette dépendance ; une exposition réseau future choisit son framework hors du domaine, par un nouvel ADR.",
                });
              }
            }
          }
        }
      },
    },
  },
} satisfies RuleSet;
