package com.minos.intellij;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Action identifiers are what users bind shortcuts and menu customisations to: renaming the class behind an action
 * must leave them untouched, and every {@code class} attribute must still name a class of the plugin.
 */
class PluginDescriptorActionsTest {

    private static final Path DESCRIPTOR = Path.of("src", "main", "resources", "META-INF", "plugin.xml");
    private static final Set<String> EXPECTED_ACTION_IDS = Set.of(
            "Minos.OpenDefinition", "Minos.FindUsages", "Minos.Dependents", "Minos.Implementations",
            "Minos.RelatedTests", "Minos.Impact", "Minos.Architecture",
            "Minos.ProgramGraph", "Minos.ImpactV2", "Minos.SecurityPaths",
            "Minos.SemanticIndexStatus", "Minos.SemanticIndexSync", "Minos.SemanticSearch", "Minos.HybridSearch",
            "Minos.HybridContext", "Minos.CopyIdentity");

    @Test
    void theDescriptorKeepsEveryActionIdAndNamesExistingClasses() throws Exception {
        assertEquals(List.of(), problems(descriptor()));
    }

    @Test
    void aChangedActionIdIsRefusedByName() throws Exception {
        String original = descriptor();
        String mutated = original.replace("id=\"Minos.HybridSearch\"", "id=\"Minos.HybridSearchRenamed\"");

        assertNotEquals(original, mutated);
        List<String> found = problems(mutated);
        assertTrue(found.stream().anyMatch(problem -> problem.contains("missing action id Minos.HybridSearch")),
                found.toString());
    }

    @Test
    void aClassAttributeNamingAnAbsentClassIsRefusedByName() throws Exception {
        String original = descriptor();
        String mutated = original.replace("MinosEditorActions$OpenDefinition", "MinosM21Actions$OpenDefinition");

        assertNotEquals(original, mutated);
        List<String> found = problems(mutated);
        assertTrue(found.stream().anyMatch(problem -> problem.contains("com.minos.intellij.actions.MinosM21Actions")),
                found.toString());
    }

    /** One message per missing or unexpected action id and per class attribute that names no compiled class. */
    static List<String> problems(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList actions = document.getElementsByTagName("action");
        Set<String> ids = new TreeSet<>();
        List<String> found = new ArrayList<>();
        for (int index = 0; index < actions.getLength(); index++) {
            Element action = (Element) actions.item(index);
            ids.add(action.getAttribute("id"));
            String className = action.getAttribute("class");
            if (!isCompiledClass(className)) {
                found.add("action " + action.getAttribute("id") + " names " + className + ", which is not a class "
                        + "of the plugin");
            }
        }
        for (String expected : new TreeSet<>(EXPECTED_ACTION_IDS)) {
            if (!ids.contains(expected)) {
                found.add("missing action id " + expected);
            }
        }
        for (String id : ids) {
            if (!EXPECTED_ACTION_IDS.contains(id)) {
                found.add("unexpected action id " + id);
            }
        }
        return found;
    }

    private static boolean isCompiledClass(String className) {
        if (className.isBlank()) {
            return false;
        }
        String resource = className.replace('.', '/') + ".class";
        return PluginDescriptorActionsTest.class.getClassLoader().getResource(resource) != null;
    }

    private static String descriptor() throws IOException {
        return Files.readString(DESCRIPTOR, StandardCharsets.UTF_8);
    }
}
