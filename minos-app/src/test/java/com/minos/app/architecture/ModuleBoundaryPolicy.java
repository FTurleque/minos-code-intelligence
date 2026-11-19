package com.minos.app.architecture;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The module boundary policy shared with {@code scripts/architecture/check-module-boundaries.py}, read from its single
 * source {@code scripts/architecture/module-boundaries.json} (AUD-ARC-04, ADR 0058). The read is strict, like the
 * guard's: a missing or unparsable file, an unknown, duplicated or missing key, an unknown family, an unknown module or
 * a debt entry without reason fails, naming the file relatively to the repository. No value has a default.
 */
final class ModuleBoundaryPolicy {

    /** The policy file, relative to the reactor root (the working directory of the minos-app tests). */
    static final Path FILE = Path.of("scripts", "architecture", "module-boundaries.json");

    private static final List<String> SECTIONS = List.of("version", "modules", "allowedDependencies",
            "testOnlyDependencies", "engineTypesNamedBySurface", "engineTypesUsedBySurfaceBytecode",
            "embeddedAdapterClasses", "infrastructureApiUsers", "assemblyClasses");
    private static final Set<String> FAMILIES =
            Set.of("core", "adapter", "application", "composition", "contract", "surface", "assembly");
    private static final String SHOWN = "scripts/architecture/module-boundaries.json";

    private final List<String> modules;
    private final Map<String, String> families;
    private final Map<String, Set<String>> allowed;
    private final Map<String, Map<String, Integer>> engineTypesUsedBySurfaceBytecode;

    private ModuleBoundaryPolicy(List<String> modules, Map<String, String> families, Map<String, Set<String>> allowed,
                                 Map<String, Map<String, Integer>> engineTypesUsedBySurfaceBytecode) {
        this.modules = List.copyOf(modules);
        this.families = Collections.unmodifiableMap(families);
        this.allowed = Collections.unmodifiableMap(allowed);
        this.engineTypesUsedBySurfaceBytecode = Collections.unmodifiableMap(engineTypesUsedBySurfaceBytecode);
    }

    /** Reads the policy of the repository whose root is {@code root}. */
    static ModuleBoundaryPolicy load(Path root) {
        JsonNode policyTree = parse(root.resolve(FILE));
        exactKeys(policyTree, SECTIONS, "the policy");
        if (!policyTree.get("version").isInt() || policyTree.get("version").asInt() != 1) {
            throw refused("unsupported version " + policyTree.get("version"));
        }
        Map<String, String> families = families(policyTree.get("modules"));
        List<String> modules = List.copyOf(families.keySet());
        Map<String, Set<String>> allowed = allowedDependencies(policyTree.get("allowedDependencies"), families);
        checkTestOnlyDependencies(policyTree.get("testOnlyDependencies"), families);
        engineDebt(policyTree, "engineTypesNamedBySurface", "files", families);
        Map<String, Map<String, Integer>> bytecode =
                engineDebt(policyTree, "engineTypesUsedBySurfaceBytecode", "classes", families);
        return new ModuleBoundaryPolicy(modules, families, allowed, bytecode);
    }

    private static JsonNode parse(Path file) {
        if (!Files.isRegularFile(file)) {
            throw refused("missing; the guard and ModuleArchitectureTest read the shared policy from this file");
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            return mapper.readTree(Files.readString(file));
        } catch (JsonProcessingException invalid) {
            String cause = invalid.getOriginalMessage();
            throw refused(cause != null && cause.startsWith("Duplicate field")
                    ? "duplicated key (" + cause + ")" : "not valid JSON");
        } catch (IOException unreadable) {
            throw refused("unreadable (" + unreadable.getClass().getSimpleName() + ")");
        }
    }

    /** Module name to family, in the order of the policy. */
    private static Map<String, String> families(JsonNode moduleList) {
        if (!moduleList.isArray() || moduleList.isEmpty()) {
            throw refused("modules is not a non-empty list");
        }
        Map<String, String> families = new LinkedHashMap<>();
        for (int index = 0; index < moduleList.size(); index++) {
            JsonNode entry = moduleList.get(index);
            exactKeys(entry, List.of("name", "family"), "modules[" + index + "]");
            String name = text(entry.get("name"), "modules[" + index + "].name");
            String family = text(entry.get("family"), "modules[" + index + "].family");
            if (!FAMILIES.contains(family)) {
                throw refused("unknown family '" + family + "' for " + name);
            }
            if (families.put(name, family) != null) {
                throw refused("module " + name + " is listed twice");
            }
        }
        return families;
    }

    private static Map<String, Set<String>> allowedDependencies(JsonNode section, Map<String, String> families) {
        List<String> modules = List.copyOf(families.keySet());
        exactKeys(section, modules, "allowedDependencies");
        Map<String, Set<String>> allowed = new LinkedHashMap<>();
        for (String module : modules) {
            JsonNode targets = section.get(module);
            if (!targets.isArray()) {
                throw refused("allowedDependencies." + module + " is not a list");
            }
            Set<String> edges = new LinkedHashSet<>();
            for (JsonNode target : targets) {
                if (!edges.add(known(target.asText(), families, "allowedDependencies." + module))) {
                    throw refused("allowedDependencies." + module + " lists a module twice");
                }
            }
            allowed.put(module, Set.copyOf(edges));
        }
        return allowed;
    }

    private static void checkTestOnlyDependencies(JsonNode section, Map<String, String> families) {
        if (!section.isArray()) {
            throw refused("testOnlyDependencies is not a list");
        }
        for (int index = 0; index < section.size(); index++) {
            String where = "testOnlyDependencies[" + index + "]";
            JsonNode entry = section.get(index);
            exactKeys(entry, List.of("module", "dependency", "reason"), where);
            known(entry.get("module").asText(), families, where);
            known(entry.get("dependency").asText(), families, where);
            text(entry.get("reason"), where + ".reason");
        }
    }

    /** The modules of the reactor, in the order of the policy. */
    List<String> modules() {
        return modules;
    }

    /** The modules of one family ({@code adapter}, {@code surface}, {@code contract}...). */
    Set<String> family(String family) {
        Set<String> members = new LinkedHashSet<>();
        families.forEach((module, value) -> {
            if (value.equals(family)) {
                members.add(module);
            }
        });
        return Set.copyOf(members);
    }

    /** The allowed internal dependencies of each module (all Maven scopes). */
    Map<String, Set<String>> allowed() {
        return allowed;
    }

    /** The bytecode view of the A11 debt: per surface or contract module, engine type to number of classes. */
    Map<String, Map<String, Integer>> engineTypesUsedBySurfaceBytecode() {
        return engineTypesUsedBySurfaceBytecode;
    }

    private static Map<String, Map<String, Integer>> engineDebt(JsonNode policyTree, String section, String counter,
                                                                Map<String, String> families) {
        JsonNode node = policyTree.get(section);
        if (!node.isObject()) {
            throw refused(section + " is not an object");
        }
        Map<String, Map<String, Integer>> debt = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> module = it.next();
            known(module.getKey(), families, section);
            String family = families.get(module.getKey());
            if (!family.equals("surface") && !family.equals("contract")) {
                throw refused(section + "." + module.getKey() + ": " + module.getKey()
                        + " is neither a surface nor a contract module");
            }
            if (!module.getValue().isObject()) {
                throw refused(section + "." + module.getKey() + " is not an object");
            }
            Map<String, Integer> types = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> entries = module.getValue().fields(); entries.hasNext(); ) {
                Map.Entry<String, JsonNode> type = entries.next();
                String where = section + "." + module.getKey() + "." + type.getKey();
                exactKeys(type.getValue(), List.of(counter, "reason"), where);
                text(type.getValue().get("reason"), where + ".reason");
                JsonNode count = type.getValue().get(counter);
                if (!count.isInt() || count.asInt() < 1) {
                    throw refused(where + "." + counter + " is not a positive count");
                }
                types.put(type.getKey(), count.asInt());
            }
            debt.put(module.getKey(), Collections.unmodifiableMap(types));
        }
        return debt;
    }

    private static void exactKeys(JsonNode node, List<String> expected, String where) {
        if (node == null || !node.isObject()) {
            throw refused(where + " is not an object");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!expected.contains(name)) {
                throw refused("unknown key '" + name + "' in " + where);
            }
        }
        for (String name : expected) {
            if (!node.has(name)) {
                throw refused("missing section '" + name + "' in " + where);
            }
        }
    }

    private static String text(JsonNode node, String where) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw refused(where + " needs a non-blank text (reason)");
        }
        return node.asText();
    }

    private static String known(String module, Map<String, String> families, String where) {
        if (!families.containsKey(module)) {
            throw refused(where + " names the unknown module '" + module + "'");
        }
        return module;
    }

    private static IllegalStateException refused(String cause) {
        return new IllegalStateException("boundary policy " + SHOWN + ": " + cause);
    }
}
