package com.minos.program.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses the confined Java source set through the public JDK compiler AST API. */
final class JavaAstParser {

    ParseResult parse(JavaSourceWorkspace.Discovery discovery) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return ParseResult.failed("JAVA_COMPILER_API_UNAVAILABLE");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaParsedUnit> parsed = new ArrayList<>();
        SourcePositions positions;
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> javaFiles = fileManager.getJavaFileObjectsFromPaths(
                    discovery.sources().stream().map(JavaSourceWorkspace.SourceFile::path).toList());
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("-proc:none", "-Xlint:none"),
                    null,
                    javaFiles);
            Iterable<? extends CompilationUnitTree> units = task.parse();
            Map<Path, String> fileIdsByPath = new HashMap<>();
            for (JavaSourceWorkspace.SourceFile source : discovery.sources()) {
                fileIdsByPath.put(source.path().toRealPath(), source.fileId());
            }
            for (CompilationUnitTree unit : units) {
                URI uri = unit.getSourceFile().toUri();
                Path real = Path.of(uri).toRealPath();
                String fileId = fileIdsByPath.get(real);
                if (fileId == null) {
                    return ParseResult.failed("JAVA_ADVANCED_PROVIDER_SOURCE_MAPPING_FAILED");
                }
                parsed.add(new JavaParsedUnit(unit, fileId));
            }
            positions = Trees.instance(task).getSourcePositions();
        }

        boolean syntaxError = diagnostics.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR);
        if (syntaxError || parsed.size() != discovery.sources().size()) {
            return ParseResult.failed("JAVA_ADVANCED_PROVIDER_PARSE_FAILED");
        }
        return ParseResult.success(List.copyOf(parsed), positions);
    }

    record ParseResult(
            boolean successful,
            List<JavaParsedUnit> units,
            SourcePositions positions,
            String limitation
    ) {
        static ParseResult success(List<JavaParsedUnit> units, SourcePositions positions) {
            return new ParseResult(true, units, positions, null);
        }

        static ParseResult failed(String limitation) {
            return new ParseResult(false, List.of(), null, limitation);
        }
    }
}

record JavaParsedUnit(CompilationUnitTree tree, String fileId) {
}
