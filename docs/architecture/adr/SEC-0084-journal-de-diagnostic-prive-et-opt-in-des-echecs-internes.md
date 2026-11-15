---
id: SEC-0084
title: "Journal de diagnostic privé et opt-in des échecs internes"
domain: security
rules: false
---

# Journal de diagnostic privé et opt-in des échecs internes

- **Statut** : Accepted
- **Date** : 2026-11-15
- **Origine** : audit du 15 novembre 2026, AUD-QUA-02 — changement OpenSpec `tracer-les-echecs-internes-des-surfaces`
- **Remplace** : —
- **Remplacé par** : —

## Context

Un échec interne du serveur MCP, de la CLI, de l'API Java ou du worker WSL est rendu à l'appelant sous une forme
opaque (`.claude/rules/securite.md`, règle 7) : l'appelant ne reçoit ni message d'exception, ni chemin, ni cause. Sans trace
dédiée, le diagnostic de l'opérateur se réduit à la classe de l'exception et à celle de sa cause racine sur stderr :
pour un `NullPointerException`, ni classe d'origine, ni ligne, ni pile.

Contraintes :

- le MCP est un serveur STDIO en **lecture seule** (ARCH-0017) : stdout est réservé au protocole, et une surface de
  lecture n'écrit rien dans les données qu'elle consulte ;
- un message d'exception peut porter un chemin absolu, une chaîne de connexion ou un jeton (SEC règles 2 et 7) ;
- aucune primitive d'E/S privée n'ouvre un fichier en ajout, et en ajouter une demanderait des appels bruts interdits
  hors des quatre primitives du garde `check-private-io.py` ;
- le cliquet A11 de `check-module-boundaries.py` interdit à une surface de nommer un type de `minos-engine` hors de
  deux tables qui ne font que décroître (ARCH-0058, ARCH-0068) ;
- les plans de requête Docker montent `MINOS_HOME` en lecture seule ; le jeu de clés de la requête du worker WSL est
  fermé, et `MINOS_*` est exclu de l'environnement des processus externes.

## Decision

La trace d'un échec interne a **deux niveaux**.

1. **Ligne de diagnostic sur stderr, toujours** (MCP, API Java, installation Windows de la voie WSL). Elle nomme l'opération, la classe de l'exception, la classe de la
   cause racine, la première trame de code MINOS de la cause racine (`Classe.méthode:ligne`, ou `unknown`) et une
   **référence** de huit chiffres hexadécimaux tirée au hasard. Elle ne contient jamais de message d'exception, de valeur
   d'argument ni de chemin. La référence est reprise dans l'erreur opaque du MCP (`(ref <référence>)`). La CLI n'écrit que, journal
   actif, une ligne `diagnostic reference: <référence>` après sa ligne `error:` ; journal éteint, sa sortie est
   identique à l'octet.
2. **Journal privé, désactivé par défaut.** `MINOS_DIAGNOSTIC_LOG=enabled` (ou la propriété `minos.diagnostic.log`, qui
   prime) fait écrire à chaque échec interne **un fichier** `MINOS_HOME/logs/internal-failures/<instant>-<référence>.txt`
   qui porte la chaîne de causes et leurs trames. Toute autre valeur laisse le journal éteint. Le journal est :
   - **privé** : dossier et fichiers créés par `PrivateLocalStorage` (ACL sous Windows, `0700`/`0600` sous Linux) ;
   - **sans chemin ni secret** : un message d'exception n'y figure que si `PublicErrorMessages.looksSensitive` ne le juge
     pas sensible, sinon un texte fixe le remplace ; aucune valeur d'argument n'est écrite ;
   - **borné** : 200 entrées (les plus anciennes sont supprimées), 8 causes, 64 trames par cause, 300 caractères par
     message ;
   - **écrit par les primitives existantes seulement** (`ensurePrivateDirectory`, `writePrivateFile`,
     `DurableAtomicFile.deleteIfExists`) : un fichier entier par échec, donc ni ajout, ni verrou, ni renommage, et la
     liste nominative du garde d'E/S privées est inchangée ;
   - **sans effet sur la réponse** : s'il ne peut être ouvert ou écrit, l'entrée est abandonnée, l'indisponibilité est
     dite une fois par journal par la classe de l'erreur, et la surface répond comme sans journal.

**Rapport à la lecture seule (ARCH-0017).** Le journal n'est pas un état de la consultation : il n'est écrit que sur
échec interne et réglage actif, dans un dossier qui lui est propre, hors des données que la surface lit. Les exigences
qui comparent l'arborescence de `MINOS_HOME` avant et après une consultation s'entendent **réglage absent** ; réglage
actif, elles restent vraies tant qu'aucun échec interne ne survient, et sur échec interne seul
`logs/internal-failures/` change.

**Façade.** Les surfaces ne nomment aucun type du moteur : elles passent par `InternalFailureReporter`
(`minos-application`, `com.minos.application.diagnostics`) et par le rendu pur `InternalFailureTrace`
(`minos-domain`). Le journal lui-même (`InternalFailureJournal`) vit dans `minos-engine`, `com.minos.io`. Les tables A11
et le pom de chaque surface sont inchangés.

**Worker WSL.** Le journal n'est pas écrit dans la distribution (son stderr est jeté, le réglage n'y entre pas). Le
worker émet, avant sa ligne de statut, une ligne `DIAGNOSTIC` du protocole `minos-wsl-worker-v1` (référence, classes,
origine, quelques trames ; ni message ni chemin) que l'installation Windows n'accepte que si elle suit une grammaire
fermée de longueur bornée, puis journalise de son côté. Le protocole de requête est inchangé.

**Hors périmètre, déclaré non supporté** : le journal privé en **Docker** (données en lecture seule : la ligne stderr
enrichie reste lisible par `docker compose logs`) et **dans la distribution WSL**. Un volume d'écriture dédié en Docker
est une décision séparée de `distribution-docker`.

## Do's and Don'ts

### Do

- Faire passer tout échec interne opaque d'une surface par `InternalFailureReporter` : ligne stderr et référence, puis
  entrée de journal quand le réglage est actif.
- Écrire dans le journal privé uniquement par `PrivateLocalStorage` et `DurableAtomicFile`, un fichier entier par échec.
- Tenir le journal éteint sans réglage `enabled`, et ne jamais recopier la valeur du réglage.
- Rendre la même réponse avec et sans journal, y compris quand il est indisponible.
- Valider une ligne de diagnostic du worker contre sa grammaire fermée avant de la journaliser ou de la rendre.

### Don't

- Ne pas écrire de message d'exception jugé sensible, de valeur d'argument, d'environnement ni de chemin absolu dans la
  ligne stderr ni dans une entrée.
- Ne pas faire nommer `InternalFailureJournal` (ni aucun type de `com.minos.io`) par le code de production d'une
  surface ; ne pas allonger les tables A11 pour cela.
- Ne pas tracer une erreur d'usage ni un refus attendu (projet inconnu, registre illisible, refus de mémoire).
- Ne pas ouvrir un fichier en ajout, ni verrouiller ni renommer dans le journal ; ne pas ajouter d'entrée à
  `private-io-allowlist.json` pour lui.
- Ne pas transmettre le réglage au worker ni dans l'environnement d'un processus externe.

## Consequences

### Positives

- L'opérateur relie la réponse opaque d'un client à sa ligne stderr puis, s'il l'a activé, à la pile, sans que la réponse
  révèle plus qu'avant.
- Un seul composant décrit un échec interne pour toutes les surfaces.

### Négatives

- L'erreur générique du MCP porte un suffixe de référence : un client la compare par son préfixe, non à l'égalité.
- Journal actif, un échec interne écrit un fichier dans `MINOS_HOME` depuis un processus de lecture seule.

### Risques

- Un message jugé non sensible par la politique publique porte quand même une donnée de projet : le journal est privé,
  opt-in et borné ; ne plus conserver aucun message est possible sans changer le format.
- Une rafale d'échecs remplit la borne et chasse une entrée utile : la ligne stderr reste conservée.

## Compliance and Enforcement

- Tests : `InternalFailureTraceTest` (`minos-domain`), `InternalFailureJournalTest` (`minos-engine`, dont deux JVM
  filles), `InternalFailureReporterTest` (`minos-application`), `MinosMcpInternalFailureTraceTest`,
  `CliInternalFailureTraceTest`, `MinosApiInternalFailureTraceTest`, `WslLinuxWorkerDiagnosticTest`,
  `WslIndexWorkerDiagnosticTest`, `WorkerDiagnosticTest`.
- `scripts/architecture/check-private-io.py` : aucun appel brut nouveau et liste nominative inchangée ;
  `scripts/architecture/check-module-boundaries.py` et `ModuleArchitectureTest` : tables A11 inchangées.
- Revue : toute surface nouvelle ou modifiée qui rend une erreur opaque appelle la façade ; aucune ligne de diagnostic ne
  rend un message ou un argument.

## Mise en œuvre

Les surfaces CLI et API Java tiennent chacune un reporter par processus (un état statique, lié par le lanceur de la CLI
et par l'ouverture d'une façade de l'API par chemin) : « une fois » s'entend par journal. Une façade de l'API construite
sur une application déjà ouverte utilise le répertoire de données que le processus résout.

Journal : `minos-engine/src/main/java/com/minos/io/InternalFailureJournal.java`. Rendu : `InternalFailureTrace`,
`WorkerDiagnostic` (`minos-domain`, `com.minos.diagnostics`). Façade :
`minos-application/src/main/java/com/minos/application/diagnostics/InternalFailureReporter.java`. Branchements : outils
MCP (`MinosMcpTools`), cadre commun de la CLI (`CliCommandSupport`) et lanceur (`MinosLauncher`), `MinosApiSupport`,
worker WSL (`WslLinuxWorker`, `WslIndexWorker`).

Les tests exercent les deux côtés du worker séparément ; aucune preuve de bout en bout sur une vraie distribution WSL
n'existe.

## References

- [ARCH-0017](ARCH-0017-mcp-stdio-read-only.md) (sa règle `mcp-no-process-or-file-write` interdit toute écriture dans
  `minos-mcp/src/main` : le journal est écrit derrière la façade de `minos-application`), [ARCH-0058](ARCH-0058-declarer-les-usages-de-modules.md),
  [ARCH-0068](ARCH-0068-modules-dependances-et-propriete-des-packages.md),
  [SEC-0061](SEC-0061-deux-niveaux-de-confidentialite-pour-l-ecriture-de-fichiers-prives.md),
  [SEC-0079](SEC-0079-indexation-java-depuis-windows-par-wsl2.md).
- Changement OpenSpec `tracer-les-echecs-internes-des-surfaces` ; audit du 15 novembre 2026, AUD-QUA-02.
- `.claude/rules/securite.md`, règles 2, 6 et 7.
- [`docs/user/diagnostic-d-un-echec-interne.md`](../../user/diagnostic-d-un-echec-interne.md).
