package com.minos.characterization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minos.output.DeterministicJson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A2 — normalisation explicite des sorties de caractérisation.
 *
 * <p>Seules les valeurs qui varient <em>légitimement</em> d'une exécution à l'autre sont
 * remplacées ; tout le reste de la sortie est comparé octet pour octet au fichier de référence.
 * Les règles, appliquées dans cet ordre, sont exhaustives :</p>
 * <ol>
 *   <li><b>Maps sans ordre contractuel</b> : voir {@link #canonicalizeUnorderedMaps(String)}.</li>
 *   <li><b>Fin de ligne de la plateforme</b> : {@code \r\n} devient {@code \n}.</li>
 *   <li><b>Répertoires d'exécution</b> : le répertoire temporaire du test devient {@code <TEMP>}
 *       et la racine absolue du dépôt (emplacement de l'extraction) devient {@code <REPO>} — chacun
 *       tel que fourni, absolu normalisé et réel ({@code toRealPath}, nom long d'un chemin 8.3),
 *       sous trois graphies (native, JSON échappée, barres obliques). Les séparateurs du chemin
 *       qui suit immédiatement l'un de ces jetons sont ramenés à {@code /}, parce que le
 *       séparateur natif dépend de l'OS et non du code.</li>
 *   <li><b>Identifiants aléatoires</b> : chaque UUID (identifiant de projet, de run, de snapshot)
 *       devient {@code <uuid-N>}, N étant son rang de première apparition ; deux occurrences du
 *       même UUID gardent donc le même jeton, et les relations d'identité restent vérifiées.</li>
 *   <li><b>Empreintes dérivées</b> : voir {@link #numberDerivedHashes(String)}.</li>
 *   <li><b>Horodatages</b> : tout instant ISO-8601 devient {@code <instant>}.</li>
 *   <li><b>Durées mesurées à l'horloge</b> : la valeur des seuls champs de
 *       {@link #MEASURED_DURATION_FIELDS} (JSON, {@code clé=valeur}, {@code clé: valeur}) devient
 *       {@code <duration>}. Une durée issue des données (par exemple {@code totalDurationNanos},
 *       somme des durées d'une enveloppe d'observations importée) reste comparée.</li>
 *   <li><b>Runtimes de providers de l'hôte</b> : voir {@link #normalizeProviderRuntimeHostFacts(String)}.</li>
 * </ol>
 * <p>L'identifiant de projet, que le registre tire au hasard et dont dérivent les identifiants de
 * symbole, n'est pas normalisé ici : le test le fige sur disque juste après l'enregistrement.</p>
 * <p>Pour {@code doctor} uniquement, {@link #normalizeHostFacts(String)} remplace en plus les faits
 * de l'hôte que la commande a pour rôle d'afficher (voir sa documentation).</p>
 */
final class CharacterizationNormalizer {

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Clés JSON dont la valeur (ou chaque élément, pour un tableau) est une map sans ordre contractuel. */
    private static final Set<String> UNORDERED_MAP_KEYS = Set.of("capabilities", "providerProfiles");
    private static final String ROOT = "<root>";
    private static final Set<String> RUNTIME_SESSIONS_ROOT_KEYS = Set.of("nature", "exhaustive", "sessions", "limitations");
    /** Idem, dans la seule sortie git-activity ({@code "nature":"FACTUAL_ACTIVITY"}) : requête, fichiers et zones (Map.of). */
    private static final Set<String> GIT_ACTIVITY_UNORDERED_MAP_KEYS = Set.of("query", "files", "zones");
    private static final Pattern TEXT_CAPABILITIES = Pattern.compile("(?m)^(\\s*capabilities: \\{)([^}\\n]*)\\}$");
    private static final Pattern DERIVED_HASH = Pattern.compile(
            "((?:\"(?:repositoryId|previousHash|hash)\":\"|\\b(?:repositoryId|previousHash|hash)[=:] ?))([0-9a-f]{64})\\b");
    private static final Pattern INSTANT = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})");
    /**
     * V41 — durées mesurées à l'horloge pendant l'exécution, et elles seules : {@code latencyMillis}
     * (durée d'une recherche, SemanticAnalysisResultRenderer / SemanticCodeIntelligenceApi).
     */
    static final Set<String> MEASURED_DURATION_FIELDS = Set.of("latencyMillis");
    private static final String MEASURED = "(?:" + String.join("|", MEASURED_DURATION_FIELDS) + ")";
    private static final Pattern JSON_DURATION = Pattern.compile(
            "(\"" + MEASURED + "\"\\s*:\\s*)\\d+");
    private static final Pattern RECORD_DURATION = Pattern.compile(
            "(\\b" + MEASURED + "(?:=|: ))\\d+");
    private static final Pattern TOKEN_PATH = Pattern.compile(
            "(<TEMP>|<REPO>)((?:(?:\\\\\\\\|\\\\|/)[A-Za-z0-9._@+-]+)*)");

    private final Map<String, String> directories = new LinkedHashMap<>();
    /** Numérotation partagée par toute la transcription : un même identifiant garde le même jeton d'une sortie à l'autre. */
    private final Map<String, String> uuidTokens = new LinkedHashMap<>();
    private final Map<String, String> derivedHashTokens = new LinkedHashMap<>();

    CharacterizationNormalizer(Path temp, Path repository) {
        this(spellings(Objects.requireNonNull(temp, "temp")), spellings(Objects.requireNonNull(repository, "repository")));
    }

    /**
     * Graphies équivalentes des deux répertoires, fournies explicitement (test du normalisateur : nom
     * court 8.3 et nom long d'un même répertoire Windows).
     */
    CharacterizationNormalizer(List<Path> tempSpellings, List<Path> repositorySpellings) {
        Map<String, String> collected = new LinkedHashMap<>();
        tempSpellings.forEach(path -> register(collected, path, "<TEMP>"));
        repositorySpellings.forEach(path -> register(collected, path, "<REPO>"));
        // Le plus long d'abord : une graphie ne masque jamais une autre plus longue qui la contient
        // (forme JSON échappée avant forme native, nom long avant nom court).
        collected.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
                .forEach(entry -> directories.put(entry.getKey(), entry.getValue()));
    }

    /**
     * Le répertoire tel que fourni, sa forme absolue normalisée et sa forme réelle ({@code toRealPath} :
     * nom long d'un chemin 8.3 Windows, cible d'un lien symbolique) — le même répertoire, rien d'autre.
     */
    private static List<Path> spellings(Path directory) {
        List<Path> result = new java.util.ArrayList<>(List.of(directory, directory.toAbsolutePath().normalize()));
        try {
            result.add(directory.toRealPath());
        } catch (java.io.IOException notYetCreated) {
            // Répertoire absent : il n'a pas d'autre graphie réelle.
        }
        return result;
    }

    private static void register(Map<String, String> collected, Path directory, String token) {
        String absolute = directory.toString();
        collected.put(absolute.replace("\\", "\\\\"), token);
        collected.put(absolute, token);
        collected.put(absolute.replace('\\', '/'), token);
    }

    String normalize(String raw) {
        String value = canonicalizeUnorderedMaps(raw.replace("\r\n", "\n"));
        // Graphies triées de la plus longue à la plus courte (voir le constructeur).
        for (Map.Entry<String, String> entry : directories.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue());
        }
        value = unifySeparators(value);
        value = number(value, UUID, uuidTokens, "uuid");
        value = numberDerivedHashes(value);
        value = INSTANT.matcher(value).replaceAll("<instant>");
        value = JSON_DURATION.matcher(value).replaceAll("$1<duration>");
        value = RECORD_DURATION.matcher(value).replaceAll("$1<duration>");
        value = normalizeProviderRuntimeHostFacts(value);
        return value;
    }

    /**
     * Faits de l'hôte que {@code doctor} a pour rôle d'afficher et qui ne proviennent pas du code
     * déplacé par A2 : version et emplacement du JDK, exécutables trouvés dans le {@code PATH},
     * état et diagnostics des runtimes de providers installés sur la machine (leur nombre de lignes
     * compris), exécutable résolu, permissions du stockage privé (POSIX ou ACL selon l'OS) et
     * valeurs de la section {@code workerSandbox} (backend de l'OS courant), sauf l'indisponibilité de
     * l'indexation distante, vraie sur tout hôte. Leur <em>présence</em>,
     * leur libellé, leur ordre, l'identifiant, la version et le caractère requis de chaque provider,
     * {@code MINOS_HOME}, le verdict et le code de sortie restent comparés ; seule leur valeur devient
     * {@code <host>}.
     */
    static String normalizeHostFacts(String value) {
        String result = value;
        // Rendu texte.
        result = result.replaceAll("(?m)^(Java runtime|Java home): .*$", "$1: <host>");
        result = result.replaceAll("(?m)^(command\\[[a-z]+\\]): .*$", "$1: <host>");
        result = result.replaceAll("(?m)^(\\S+ \\S+ — )[A-Z_]+( \\[(?:required|optional)\\])$", "$1<host>$2");
        // Lignes de détail d'un provider (ToolsCommand.render : « executable: » si un exécutable est
        // résolu sur l'hôte, puis une « diagnostic: » par diagnostic) : leur nombre et leur contenu
        // dépendent des outils installés. Tout le bloc devient exactement une ligne sous chaque provider.
        result = result.replaceAll("(?m)^  (?:executable|diagnostic): .*\\n", "");
        result = result.replaceAll("(?m)^(\\S+ \\S+ — <host> \\[(?:required|optional)\\])$", "$1\n  diagnostic: <host>");
        result = result.replaceAll("(?m)^(privateStorage\\[[^\\]]+\\]): .*$", "$1: <host>");
        // L'indisponibilité de l'indexation distante vaut sur tout hôte (ADR 0041) : elle reste comparée,
        // seule sa cause (décision ou prérequis absent) dépend de l'hôte.
        result = result.replaceAll("(?m)^(workerSandbox\\[remoteIndexing\\]: [A-Z]+).*$", "$1<host>");
        result = result.replaceAll("(?m)^(workerSandbox\\[(?!remoteIndexing\\])[^\\]]+\\]): .*$", "$1: <host>");
        // Rendu JSON.
        result = result.replaceAll("(\"(?:javaRuntime|javaHome)\":)\"[^\"]*\"", "$1\"<host>\"");
        result = result.replaceAll("(\"commands\":)\\{[^}]*\\}", "$1<host>");
        result = result.replaceAll("(\"state\":)\"[A-Z_]+\"", "$1\"<host>\"");
        result = result.replaceAll("(\"executable\":)(?:null|\"[^\"]*\")", "$1\"<host>\"");
        result = result.replaceAll("(\"diagnostics\":)\\[[^\\]]*\\]", "$1<host>");
        result = result.replaceAll("(\"privateStoragePermissions\":)\\{[^}]*\\}", "$1<host>");
        // Section workerSandbox : remoteIndexing et untrustedCode.available restent comparés.
        result = result.replaceAll("(\"managedLocalProvider\":)\\{[^{}]*\\}", "$1<host>");
        result = result.replaceAll("(\"untrustedCode\":\\{\"backend\":)\"[^\"]*\"", "$1\"<host>\"");
        result = result.replaceAll("(\"(?:cause|rejectedBackend|decision)\":)(?:null|\"[^\"]*\")", "$1\"<host>\"");
        result = result.replaceAll("(\"reasons\":)\\[[^\\]]*\\]", "$1<host>");
        result = result.replaceAll("(\"reason\":)\"(?:[^\"\\\\]|\\\\.)*\"", "$1\"<host>\"");
        return result;
    }

    /**
     * État des runtimes de providers sur l'hôte ({@code runtimeState}, {@code runtimeDiagnostics}) :
     * il dépend de l'OS et des chaînes d'outils installées (Rust, Go, binaire scip-clang absent sous
     * Windows...), pas du code déplacé par A2. Le reste de chaque vue provider (identifiant, version,
     * qualification, langages, capacités, limitations, profil opérationnel) reste comparé.
     */
    static String normalizeProviderRuntimeHostFacts(String value) {
        String result = value;
        result = result.replaceAll("(\"runtimeState\":)\"[^\"]*\"", "$1\"<host>\"");
        result = result.replaceAll("(\"runtimeDiagnostics\":)\\[(?:[^\\[\\]\"]|\"(?:[^\"\\\\]|\\\\.)*\")*\\]", "$1<host>");
        result = result.replaceAll("(?m)^(runtimeState|runtimeDiagnostics): .*$", "$1: <host>");
        // Table texte de `minos providers` (ProviderCommand.renderList) :
        // id TAB version TAB qualification TAB état-du-runtime TAB score=N — seule la 4e colonne est masquée.
        result = result.replaceAll("(?m)^([a-z0-9][a-z0-9-]*\\t[^\\t\\n]+\\t[A-Z_]+\\t)[A-Z_]+(\\tscore=\\d+)$", "$1<host>$2");
        result = result.replaceAll("(\\bruntimeState=)[A-Z_]+", "$1<host>");
        result = result.replaceAll("(\\bruntimeDiagnostics=)\\[[^\\]]*\\]", "$1<host>");
        return result;
    }

    /**
     * Ordre des clés des maps sans ordre contractuel. {@code ProviderView.capabilities} est un
     * {@code Map.copyOf}, chaque profil {@code providerProfiles} du MCP est un {@code Map.copyOf}, les
     * objets {@code query}, {@code files}, {@code zones} de git-activity et la racine de
     * {@code RuntimeIntelligenceRenderer.renderSessions} ({@code Map.of}) aussi :
     * leur ordre d'itération change d'une JVM à l'autre (non-déterminisme préexistant, hors A2). Ces
     * maps-là, et elles seules, sont réémises triées par clé avec le moteur JSON du produit, après
     * vérification que la sortie brute se relit et se réémet à l'octet près (sinon le test échoue au
     * lieu de masquer une différence de rendu). Rendu texte : la ligne {@code capabilities: {..}} est
     * triée de la même façon.
     */
    static String canonicalizeUnorderedMaps(String value) {
        String trimmed = value.strip();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return TEXT_CAPABILITIES.matcher(value).replaceAll(match -> Matcher.quoteReplacement(
                    match.group(1) + String.join(", ", Stream.of(match.group(2).split(", ")).sorted().toList()) + "}"));
        }
        Object tree;
        try {
            tree = JSON.readValue(trimmed, Object.class);
        } catch (IOException notJson) {
            return value;
        }
        Set<String> unordered = unorderedKeys(tree);
        if (!containsUnorderedMap(tree, ROOT, unordered)) return value;
        String reEmitted = DeterministicJson.render(tree);
        if (!reEmitted.equals(trimmed)) {
            throw new AssertionError("JSON output does not re-emit byte for byte; refusing to canonicalize it:\n" + trimmed);
        }
        return value.replace(trimmed, DeterministicJson.render(canonical(tree, ROOT, unordered)));
    }

    private static Set<String> unorderedKeys(Object tree) {
        if (tree instanceof Map<?, ?> map && "FACTUAL_ACTIVITY".equals(map.get("nature"))) {
            return Stream.concat(UNORDERED_MAP_KEYS.stream(), GIT_ACTIVITY_UNORDERED_MAP_KEYS.stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        // RuntimeIntelligenceRenderer.renderSessions émet un Map.of : seul son niveau racine est concerné.
        if (tree instanceof Map<?, ?> map && map.keySet().equals(RUNTIME_SESSIONS_ROOT_KEYS)) {
            return Stream.concat(UNORDERED_MAP_KEYS.stream(), Stream.of(ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        return UNORDERED_MAP_KEYS;
    }

    private static boolean containsUnorderedMap(Object node, String key, Set<String> unordered) {
        if (node instanceof Map<?, ?> map) {
            if (key != null && unordered.contains(key)) return true;
            return map.entrySet().stream()
                    .anyMatch(entry -> containsUnorderedMap(entry.getValue(), String.valueOf(entry.getKey()), unordered));
        }
        if (node instanceof List<?> list) {
            return list.stream().anyMatch(element -> containsUnorderedMap(element, key, unordered));
        }
        return false;
    }

    private static Object canonical(Object node, String key, Set<String> unordered) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> result = key != null && unordered.contains(key) ? new TreeMap<>() : new LinkedHashMap<>();
            map.forEach((childKey, child) ->
                    result.put(String.valueOf(childKey), canonical(child, String.valueOf(childKey), unordered)));
            return result;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(element -> canonical(element, key, unordered)).toList();
        }
        return node;
    }

    /**
     * Empreintes dérivées d'une valeur aléatoire ou d'un chemin temporaire : identifiant de dépôt Git
     * (haché depuis le chemin du dépôt) et chaîne d'audit hébergée (qui hache des UUID aléatoires).
     * Numérotées comme les UUID, ce qui garde vérifiable le chaînage {@code hash} -> {@code previousHash}.
     */
    private String numberDerivedHashes(String value) {
        Matcher matcher = DERIVED_HASH.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(2);
            String token = derivedHashTokens.computeIfAbsent(key, ignored -> "<hash-" + (derivedHashTokens.size() + 1) + ">");
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1) + token));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String unifySeparators(String value) {
        Matcher matcher = TOKEN_PATH.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String tail = matcher.group(2).replace("\\\\", "/").replace('\\', '/');
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1) + tail));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String number(String value, Pattern pattern, Map<String, String> tokens, String kind) {
        Matcher matcher = pattern.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group().toLowerCase(java.util.Locale.ROOT);
            String token = tokens.computeIfAbsent(key, ignored -> "<" + kind + "-" + (tokens.size() + 1) + ">");
            matcher.appendReplacement(result, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
