package com.minos.program.analysis;

import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolLocation;
import com.minos.program.ProgramEdgeKind;
import com.minos.program.ProgramGraph;
import com.minos.program.ProgramGraphCapability;
import com.minos.program.ProgramGraphEdge;
import com.minos.program.ProgramGraphNode;
import com.minos.program.ProgramNodeKind;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Conservative M22 reference provider for Java advanced-program facts derived from the public JDK compiler AST.
 *
 * <p>The provider is fail-closed at project/snapshot level: every Java source represented by the active
 * snapshot must be confined to the registered project root, present, bounded and syntactically parseable.
 * M22 v1 deliberately does not perform guessed-classpath type attribution. Def-use is name-based inside one
 * method and interprocedural argument/return flow is emitted only when simple name + arity resolves uniquely
 * among parsed project methods. Every restriction is exposed as a limitation instead of being hidden.</p>
 */
public final class JavaSourceProgramGraphProvider implements ProgramGraphProvider {

    public static final String PROVIDER_ID = "minos-java-source-v1";
    public static final String SECURITY_CONFIG = ".minos/java-advanced-provider.properties";

    private static final String PROVIDER_VERSION = "1";
    private static final int MAX_SOURCE_FILES = 2_000;
    private static final long MAX_SOURCE_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_TOTAL_SOURCE_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_SECURITY_CONFIG_BYTES = 1024L * 1024L;

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String cacheKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(snapshot, "snapshot");
        Discovery discovery = discover(project, snapshot);
        return PROVIDER_ID + ":" + stateFingerprint(project, snapshot, discovery);
    }

    @Override
    public ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(snapshot, "snapshot");
        String projectId = project.id().toString();
        Discovery discovery = discover(project, snapshot);
        if (!discovery.usable()) {
            return empty(projectId, snapshot.snapshotId(), discovery.limitation());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return empty(projectId, snapshot.snapshotId(), "JAVA_COMPILER_API_UNAVAILABLE");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<ParsedUnit> parsed = new ArrayList<>();
        SourcePositions positions;
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> javaFiles = fileManager.getJavaFileObjectsFromPaths(
                    discovery.sources().stream().map(SourceFile::path).toList());
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("-proc:none", "-Xlint:none"),
                    null,
                    javaFiles);
            Iterable<? extends CompilationUnitTree> units = task.parse();
            Map<Path, String> fileIdsByPath = new HashMap<>();
            for (SourceFile source : discovery.sources()) {
                fileIdsByPath.put(source.path().toRealPath(), source.fileId());
            }
            for (CompilationUnitTree unit : units) {
                URI uri = unit.getSourceFile().toUri();
                Path real = Path.of(uri).toRealPath();
                String fileId = fileIdsByPath.get(real);
                if (fileId == null) {
                    return empty(projectId, snapshot.snapshotId(), "JAVA_ADVANCED_PROVIDER_SOURCE_MAPPING_FAILED");
                }
                parsed.add(new ParsedUnit(unit, fileId));
            }
            positions = Trees.instance(task).getSourcePositions();
        }

        boolean syntaxError = diagnostics.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR);
        if (syntaxError || parsed.size() != discovery.sources().size()) {
            return empty(projectId, snapshot.snapshotId(), "JAVA_ADVANCED_PROVIDER_PARSE_FAILED");
        }

        String state = stateFingerprint(project, snapshot, discovery);
        String runId = snapshot.snapshotId() + ":" + state.substring(0, 16);
        SecurityRules rules = SecurityRules.load(project.rootPath());
        return new Analyzer(projectId, snapshot.snapshotId(), positions, parsed, runId, rules).analyze();
    }

    private static Discovery discover(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        Path root = project.rootPath().toRealPath();
        Set<String> requested = new LinkedHashSet<>();
        for (Symbol symbol : snapshot.symbols()) {
            if (!"java".equalsIgnoreCase(symbol.language())) continue;
            String fileId = symbol.fileId();
            if ((fileId == null || fileId.isBlank()) && symbol.location() != null) {
                fileId = symbol.location().fileId();
            }
            if (fileId != null && fileId.toLowerCase(Locale.ROOT).endsWith(".java")) {
                requested.add(fileId.replace('\\', '/'));
            }
        }
        List<String> requestedFileIds = requested.stream().sorted().toList();
        if (requestedFileIds.isEmpty()) {
            return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_NOT_APPLICABLE");
        }
        if (requestedFileIds.size() > MAX_SOURCE_FILES) {
            return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_SOURCE_FILE_LIMIT_EXCEEDED");
        }

        List<SourceFile> sources = new ArrayList<>();
        long total = 0L;
        for (String fileId : requestedFileIds) {
            Path relative;
            try {
                relative = Path.of(fileId.replace('/', java.io.File.separatorChar)).normalize();
            } catch (InvalidPathException exception) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_INVALID_FILE_ID");
            }
            if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_INVALID_FILE_ID");
            }
            Path candidate = root.resolve(relative).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate)) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_SOURCE_MISSING");
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_SOURCE_ESCAPE");
            }
            long bytes = Files.size(real);
            if (bytes > MAX_SOURCE_BYTES) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_SOURCE_TOO_LARGE");
            }
            total += bytes;
            if (total > MAX_TOTAL_SOURCE_BYTES) {
                return Discovery.failed(requestedFileIds, "JAVA_ADVANCED_PROVIDER_TOTAL_SOURCE_LIMIT_EXCEEDED");
            }
            sources.add(new SourceFile(fileId, real));
        }
        return Discovery.usable(requestedFileIds, List.copyOf(sources));
    }

    private static String stateFingerprint(
            RegisteredProject project,
            CodeKnowledgeSnapshot snapshot,
            Discovery discovery
    ) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, snapshot.snapshotId());
            update(digest, discovery.limitation() == null ? "USABLE" : discovery.limitation());
            for (String fileId : discovery.requestedFileIds()) update(digest, fileId);
            for (SourceFile source : discovery.sources()) {
                update(digest, source.fileId());
                digest.update(Files.readAllBytes(source.path()));
            }
            Optional<Path> config = securityConfig(project.rootPath());
            if (config.isPresent()) {
                update(digest, SECURITY_CONFIG);
                digest.update(Files.readAllBytes(config.orElseThrow()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Optional<Path> securityConfig(Path projectRoot) throws IOException {
        Path root = projectRoot.toRealPath();
        Path candidate = root.resolve(SECURITY_CONFIG).normalize();
        if (!candidate.startsWith(root) || !Files.exists(candidate)) return Optional.empty();
        if (!Files.isRegularFile(candidate)) {
            throw new IOException("Java advanced provider security config is not a regular file");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("Java advanced provider security config escapes project root");
        }
        if (Files.size(real) > MAX_SECURITY_CONFIG_BYTES) {
            throw new IOException("Java advanced provider security config exceeds 1 MiB");
        }
        return Optional.of(real);
    }

    private static void update(MessageDigest digest, String value) {
        digest.update((byte) 0);
        digest.update(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ProgramGraph empty(String projectId, String snapshotId, String limitation) {
        return new ProgramGraph(projectId, snapshotId, Set.of(), List.of(), List.of(), List.of(limitation));
    }

    private record Discovery(
            boolean usable,
            List<String> requestedFileIds,
            List<SourceFile> sources,
            String limitation
    ) {
        static Discovery usable(List<String> requestedFileIds, List<SourceFile> sources) {
            return new Discovery(true, requestedFileIds, sources, null);
        }

        static Discovery failed(List<String> requestedFileIds, String limitation) {
            return new Discovery(false, requestedFileIds, List.of(), limitation);
        }
    }

    private record SourceFile(String fileId, Path path) {
    }

    private record ParsedUnit(CompilationUnitTree tree, String fileId) {
    }

    private record SecurityRules(boolean configured, Set<String> sources, Set<String> sinks, Set<String> sanitizers) {
        static SecurityRules load(Path root) throws IOException {
            Optional<Path> config = securityConfig(root);
            if (config.isEmpty()) {
                return new SecurityRules(false, Set.of(), Set.of(), Set.of());
            }
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(config.orElseThrow(), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return new SecurityRules(
                    true,
                    tokens(properties.getProperty("sources", "")),
                    tokens(properties.getProperty("sinks", "")),
                    tokens(properties.getProperty("sanitizers", "")));
        }

        private static Set<String> tokens(String raw) {
            Set<String> values = new LinkedHashSet<>();
            for (String token : raw.split(",")) {
                String value = token.trim();
                if (!value.isEmpty()) values.add(value);
            }
            return Set.copyOf(values);
        }

        boolean source(MethodInvocationTree tree) {
            return matches(sources, tree);
        }

        boolean sink(MethodInvocationTree tree) {
            return matches(sinks, tree);
        }

        boolean sanitizer(MethodInvocationTree tree) {
            return matches(sanitizers, tree);
        }

        private static boolean matches(Set<String> rules, MethodInvocationTree tree) {
            return rules.contains(tree.getMethodSelect().toString()) || rules.contains(invocationName(tree));
        }
    }

    private static final class Analyzer {
        private static final double CFG_CONFIDENCE = 1.0;
        private static final double DEF_USE_CONFIDENCE = 0.90;
        private static final double INTERPROCEDURAL_CONFIDENCE = 0.85;
        private static final double SECURITY_CONFIDENCE = 0.90;

        private final String projectId;
        private final String snapshotId;
        private final SourcePositions positions;
        private final List<ParsedUnit> units;
        private final SecurityRules securityRules;
        private final Origin astOrigin;
        private final Origin derivedOrigin;
        private final Map<String, ProgramGraphNode> nodes = new LinkedHashMap<>();
        private final Map<String, ProgramGraphEdge> edges = new LinkedHashMap<>();
        private final Set<String> limitations = new LinkedHashSet<>();
        private final List<MethodInfo> methods = new ArrayList<>();
        private final Map<MethodKey, List<MethodInfo>> methodIndex = new LinkedHashMap<>();
        private final List<InvocationInfo> invocations = new ArrayList<>();

        private Analyzer(
                String projectId,
                String snapshotId,
                SourcePositions positions,
                List<ParsedUnit> units,
                String runId,
                SecurityRules securityRules
        ) {
            this.projectId = projectId;
            this.snapshotId = snapshotId;
            this.positions = positions;
            this.units = units;
            this.securityRules = securityRules;
            this.astOrigin = new Origin(PROVIDER_ID, "JAVA_COMPILER_AST", PROVIDER_VERSION, runId, OriginType.AST);
            this.derivedOrigin = new Origin(
                    PROVIDER_ID, "JAVA_COMPILER_AST", PROVIDER_VERSION, runId, OriginType.DERIVED_BY_MINOS);
        }

        ProgramGraph analyze() {
            limitations.add("JAVA_ADVANCED_PROVIDER_V1");
            limitations.add("JAVA_AST_PARSE_ONLY_TYPE_ATTRIBUTION_NOT_PROVEN");
            collectMethods();
            for (MethodInfo method : methods) {
                if (method.tree().getBody() != null) analyzeMethod(method);
            }
            resolveInterproceduralFlows();
            analyzeSecurity();

            Set<ProgramGraphCapability> capabilities = EnumSet.noneOf(ProgramGraphCapability.class);
            Set<ProgramEdgeKind> kinds = EnumSet.noneOf(ProgramEdgeKind.class);
            edges.values().forEach(edge -> kinds.add(edge.kind()));
            if (kinds.contains(ProgramEdgeKind.CONTROL_FLOW)) {
                capabilities.add(ProgramGraphCapability.CONTROL_FLOW);
            }
            if (kinds.contains(ProgramEdgeKind.DEF_USE) || kinds.contains(ProgramEdgeKind.DATA_FLOW)) {
                capabilities.add(ProgramGraphCapability.LOCAL_DATA_FLOW);
                limitations.add("JAVA_LOCAL_DATA_FLOW_NAME_BASED_WITHIN_METHOD");
            }
            if (kinds.contains(ProgramEdgeKind.ARGUMENT_FLOW) || kinds.contains(ProgramEdgeKind.RETURN_FLOW)) {
                capabilities.add(ProgramGraphCapability.INTERPROCEDURAL_DATA_FLOW);
                limitations.add("JAVA_INTERPROCEDURAL_UNIQUE_NAME_ARITY_ONLY");
            }
            boolean source = nodes.values().stream().anyMatch(node -> node.kind() == ProgramNodeKind.SOURCE);
            boolean sink = nodes.values().stream().anyMatch(node -> node.kind() == ProgramNodeKind.SINK);
            if (kinds.contains(ProgramEdgeKind.TAINT_FLOW) && source && sink) {
                capabilities.add(ProgramGraphCapability.SECURITY_TAINT);
                limitations.add("JAVA_SECURITY_FLOW_INTRAPROCEDURAL_CONFIGURED_RULES_ONLY");
            }
            if (!securityRules.configured()) limitations.add("JAVA_SECURITY_RULES_NOT_CONFIGURED");

            return new ProgramGraph(
                    projectId,
                    snapshotId,
                    Set.copyOf(capabilities),
                    nodes.values().stream().sorted(Comparator.comparing(ProgramGraphNode::id)).toList(),
                    edges.values().stream().sorted(Comparator.comparing(ProgramGraphEdge::id)).toList(),
                    limitations.stream().sorted().toList());
        }

        private void collectMethods() {
            for (ParsedUnit unit : units) {
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree tree, Void unused) {
                        MethodInfo method = new MethodInfo(unit, tree, new ArrayList<>(), new ArrayList<>());
                        methods.add(method);
                        String name = tree.getName().toString();
                        if (!"<init>".equals(name)) {
                            methodIndex.computeIfAbsent(
                                    new MethodKey(name, tree.getParameters().size()), ignored -> new ArrayList<>()).add(method);
                        }
                        return super.visitMethod(tree, unused);
                    }
                }.scan(unit.tree(), null);
            }
        }

        private void analyzeMethod(MethodInfo method) {
            Map<String, Set<String>> definitions = new LinkedHashMap<>();
            int index = 0;
            for (VariableTree parameter : method.tree().getParameters()) {
                String name = parameter.getName().toString();
                String id = id("param", method.unit(), parameter, method.tree().getName() + ":" + index + ":" + name);
                addNode(factualNode(
                        id,
                        ProgramNodeKind.PARAMETER,
                        label("parameter " + method.tree().getName() + "[" + index + "] " + name, method.unit(), parameter),
                        location(method.unit(), parameter)));
                method.parameterNodeIds().add(id);
                definitions.put(name, Set.of(id));
                index++;
            }
            new DefUseScanner(method, definitions).scan(method.tree().getBody(), null);
            new CfgBuilder(method.unit()).build(method.tree().getBody());
        }

        private void resolveInterproceduralFlows() {
            boolean unresolved = false;
            for (InvocationInfo invocation : invocations) {
                List<MethodInfo> candidates = methodIndex.getOrDefault(
                        new MethodKey(invocation.name(), invocation.argumentNodeIds().size()), List.of());
                if (candidates.size() != 1) {
                    unresolved = true;
                    continue;
                }
                MethodInfo target = candidates.getFirst();
                for (int index = 0; index < invocation.argumentNodeIds().size(); index++) {
                    if (index >= target.parameterNodeIds().size()) break;
                    addDerivedEdge(
                            "argument-flow",
                            invocation.argumentNodeIds().get(index),
                            target.parameterNodeIds().get(index),
                            ProgramEdgeKind.ARGUMENT_FLOW,
                            INTERPROCEDURAL_CONFIDENCE,
                            "argument mapped to the unique project method with matching simple name and arity",
                            invocation.location());
                }
                if (!target.returnNodeIds().isEmpty()) {
                    String resultId = id("call-result", invocation.unit(), invocation.tree(), invocation.name());
                    addNode(factualNode(
                            resultId,
                            ProgramNodeKind.RETURN_VALUE,
                            label("call-result " + invocation.name(), invocation.unit(), invocation.tree()),
                            invocation.location()));
                    for (String returnNode : target.returnNodeIds()) {
                        addDerivedEdge(
                                "return-flow",
                                returnNode,
                                resultId,
                                ProgramEdgeKind.RETURN_FLOW,
                                INTERPROCEDURAL_CONFIDENCE,
                                "return mapped from the unique project method with matching simple name and arity",
                                invocation.location());
                    }
                }
            }
            if (unresolved) limitations.add("JAVA_INTERPROCEDURAL_EXTERNAL_OR_AMBIGUOUS_CALLS_SKIPPED");
        }

        private void analyzeSecurity() {
            if (!securityRules.configured()) return;
            for (MethodInfo method : methods) {
                if (method.tree().getBody() != null) new SecurityScanner(method.unit()).scan(method.tree().getBody(), null);
            }
        }

        private final class DefUseScanner extends TreeScanner<Void, Void> {
            private final MethodInfo method;
            private final Map<String, Set<String>> definitions;

            private DefUseScanner(MethodInfo method, Map<String, Set<String>> definitions) {
                this.method = method;
                this.definitions = definitions;
            }

            @Override
            public Void visitVariable(VariableTree tree, Void unused) {
                if (tree.getInitializer() != null) scan(tree.getInitializer(), null);
                String name = tree.getName().toString();
                definitions.put(name, Set.of(definitionNode(method.unit(), tree, name)));
                return null;
            }

            @Override
            public Void visitAssignment(AssignmentTree tree, Void unused) {
                scan(tree.getExpression(), null);
                if (tree.getVariable() instanceof IdentifierTree identifier) {
                    String name = identifier.getName().toString();
                    definitions.put(name, Set.of(definitionNode(method.unit(), tree.getVariable(), name)));
                } else {
                    limitations.add("JAVA_LOCAL_DATA_FLOW_FIELDS_NOT_MODELED");
                    scan(tree.getVariable(), null);
                }
                return null;
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree tree, Void unused) {
                scan(tree.getVariable(), null);
                scan(tree.getExpression(), null);
                if (tree.getVariable() instanceof IdentifierTree identifier) {
                    String name = identifier.getName().toString();
                    definitions.put(name, Set.of(definitionNode(method.unit(), tree.getVariable(), name)));
                } else {
                    limitations.add("JAVA_LOCAL_DATA_FLOW_FIELDS_NOT_MODELED");
                }
                return null;
            }

            @Override
            public Void visitUnary(UnaryTree tree, Void unused) {
                scan(tree.getExpression(), null);
                switch (tree.getKind()) {
                    case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> {
                        if (tree.getExpression() instanceof IdentifierTree identifier) {
                            String name = identifier.getName().toString();
                            definitions.put(name, Set.of(definitionNode(method.unit(), tree.getExpression(), name)));
                        }
                    }
                    default -> {
                    }
                }
                return null;
            }

            @Override
            public Void visitIdentifier(IdentifierTree tree, Void unused) {
                String name = tree.getName().toString();
                Set<String> sources = definitions.get(name);
                if (sources == null || sources.isEmpty()) return null;
                String useId = id("use", method.unit(), tree, name);
                addNode(factualNode(
                        useId,
                        ProgramNodeKind.VARIABLE,
                        label("use " + name, method.unit(), tree),
                        location(method.unit(), tree)));
                for (String source : sources) {
                    addDerivedEdge(
                            "def-use",
                            source,
                            useId,
                            ProgramEdgeKind.DEF_USE,
                            DEF_USE_CONFIDENCE,
                            "lexical Java definition reaches identifier use inside the same method",
                            location(method.unit(), tree));
                }
                return null;
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                String name = invocationName(tree);
                List<String> argumentIds = new ArrayList<>();
                int index = 0;
                for (ExpressionTree argument : tree.getArguments()) {
                    String argumentId = id("arg", method.unit(), argument, name + ":" + index);
                    addNode(factualNode(
                            argumentId,
                            ProgramNodeKind.PARAMETER,
                            label("argument " + name + "[" + index + "]", method.unit(), argument),
                            location(method.unit(), argument)));
                    argumentIds.add(argumentId);
                    scan(argument, null);
                    index++;
                }
                if (tree.getMethodSelect() instanceof MemberSelectTree memberSelect) {
                    scan(memberSelect.getExpression(), null);
                }
                invocations.add(new InvocationInfo(
                        method.unit(), tree, name, List.copyOf(argumentIds), location(method.unit(), tree)));
                return null;
            }

            @Override
            public Void visitReturn(ReturnTree tree, Void unused) {
                if (tree.getExpression() != null) scan(tree.getExpression(), null);
                String id = id("return", method.unit(), tree, method.tree().getName().toString());
                addNode(factualNode(
                        id,
                        ProgramNodeKind.RETURN_VALUE,
                        label("return " + method.tree().getName(), method.unit(), tree),
                        location(method.unit(), tree)));
                method.returnNodeIds().add(id);
                return null;
            }

            @Override
            public Void visitIf(IfTree tree, Void unused) {
                scan(tree.getCondition(), null);
                Map<String, Set<String>> before = copyState(definitions);
                definitions.clear();
                definitions.putAll(copyState(before));
                scan(tree.getThenStatement(), null);
                Map<String, Set<String>> thenState = copyState(definitions);
                definitions.clear();
                definitions.putAll(copyState(before));
                if (tree.getElseStatement() != null) scan(tree.getElseStatement(), null);
                Map<String, Set<String>> elseState = copyState(definitions);
                definitions.clear();
                definitions.putAll(mergeStates(thenState, elseState));
                return null;
            }

            @Override
            public Void visitWhileLoop(WhileLoopTree tree, Void unused) {
                scan(tree.getCondition(), null);
                Map<String, Set<String>> before = copyState(definitions);
                scan(tree.getStatement(), null);
                Map<String, Set<String>> afterBody = copyState(definitions);
                definitions.clear();
                definitions.putAll(mergeStates(before, afterBody));
                limitations.add("JAVA_LOCAL_DATA_FLOW_LOOP_FIXPOINT_CONSERVATIVE");
                return null;
            }

            @Override
            public Void visitForLoop(ForLoopTree tree, Void unused) {
                for (StatementTree initializer : tree.getInitializer()) scan(initializer, null);
                if (tree.getCondition() != null) scan(tree.getCondition(), null);
                Map<String, Set<String>> before = copyState(definitions);
                scan(tree.getStatement(), null);
                for (ExpressionStatementTree update : tree.getUpdate()) scan(update, null);
                Map<String, Set<String>> afterBody = copyState(definitions);
                definitions.clear();
                definitions.putAll(mergeStates(before, afterBody));
                limitations.add("JAVA_LOCAL_DATA_FLOW_LOOP_FIXPOINT_CONSERVATIVE");
                return null;
            }

            @Override
            public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
                scan(tree.getExpression(), null);
                Map<String, Set<String>> before = copyState(definitions);
                String name = tree.getVariable().getName().toString();
                definitions.put(name, Set.of(definitionNode(method.unit(), tree.getVariable(), name)));
                scan(tree.getStatement(), null);
                Map<String, Set<String>> afterBody = copyState(definitions);
                definitions.clear();
                definitions.putAll(mergeStates(before, afterBody));
                limitations.add("JAVA_LOCAL_DATA_FLOW_ENHANCED_FOR_ELEMENT_BINDING_NOT_PROVEN");
                return null;
            }

            @Override
            public Void visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
                Map<String, Set<String>> before = copyState(definitions);
                scan(tree.getStatement(), null);
                scan(tree.getCondition(), null);
                Map<String, Set<String>> afterBody = copyState(definitions);
                definitions.clear();
                definitions.putAll(mergeStates(before, afterBody));
                limitations.add("JAVA_LOCAL_DATA_FLOW_LOOP_FIXPOINT_CONSERVATIVE");
                return null;
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
                limitations.add("JAVA_LOCAL_DATA_FLOW_NESTED_LAMBDA_NOT_MODELED");
                return null;
            }

            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                limitations.add("JAVA_LOCAL_DATA_FLOW_LOCAL_CLASS_NOT_MODELED");
                return null;
            }
        }

        private final class CfgBuilder {
            private final ParsedUnit unit;

            private CfgBuilder(ParsedUnit unit) {
                this.unit = unit;
            }

            FlowFragment build(StatementTree statement) {
                if (statement == null) return FlowFragment.empty();
                if (statement instanceof BlockTree block) return sequence(block.getStatements());
                if (statement instanceof IfTree tree) return ifFlow(tree);
                if (statement instanceof WhileLoopTree tree) return whileFlow(tree);
                if (statement instanceof ForLoopTree tree) return forFlow(tree);
                if (statement instanceof EnhancedForLoopTree tree) return enhancedForFlow(tree);
                if (statement instanceof DoWhileLoopTree tree) return doWhileFlow(tree);
                if (statement instanceof TryTree tree) return tryFlow(tree);
                if (statement instanceof SynchronizedTree tree) return synchronizedFlow(tree);
                if (statement instanceof LabeledStatementTree tree) return build(tree.getStatement());
                String node = basicBlock(statement);
                if (statement instanceof ReturnTree || statement instanceof ThrowTree) {
                    return new FlowFragment(node, Set.of());
                }
                switch (statement.getKind()) {
                    case BREAK, CONTINUE, SWITCH, YIELD -> limitations.add("JAVA_CFG_UNMODELED_CONTROL_TRANSFER_PRESENT");
                    default -> {
                    }
                }
                return new FlowFragment(node, Set.of(node));
            }

            private FlowFragment sequence(List<? extends StatementTree> statements) {
                String entry = null;
                Set<String> exits = Set.of();
                for (StatementTree statement : statements) {
                    FlowFragment next = build(statement);
                    if (next.entry() == null) continue;
                    if (entry == null) {
                        entry = next.entry();
                    } else {
                        for (String exit : exits) cfgEdge(exit, next.entry(), statement);
                    }
                    exits = next.exits();
                    if (exits.isEmpty()) break;
                }
                return new FlowFragment(entry, exits);
            }

            private FlowFragment ifFlow(IfTree tree) {
                String decision = basicBlock(tree);
                FlowFragment thenFlow = build(tree.getThenStatement());
                if (thenFlow.entry() != null) cfgEdge(decision, thenFlow.entry(), tree.getThenStatement());
                FlowFragment elseFlow = tree.getElseStatement() == null ? FlowFragment.empty() : build(tree.getElseStatement());
                if (elseFlow.entry() != null) cfgEdge(decision, elseFlow.entry(), tree.getElseStatement());
                Set<String> exits = new LinkedHashSet<>(thenFlow.exits());
                if (tree.getElseStatement() == null) exits.add(decision);
                else exits.addAll(elseFlow.exits());
                return new FlowFragment(decision, Set.copyOf(exits));
            }

            private FlowFragment whileFlow(WhileLoopTree tree) {
                String condition = basicBlock(tree);
                FlowFragment body = build(tree.getStatement());
                if (body.entry() != null) cfgEdge(condition, body.entry(), tree.getStatement());
                for (String exit : body.exits()) cfgEdge(exit, condition, tree);
                return new FlowFragment(condition, Set.of(condition));
            }

            private FlowFragment forFlow(ForLoopTree tree) {
                FlowFragment initializer = sequence(tree.getInitializer());
                String condition = basicBlock(tree);
                if (initializer.entry() != null) {
                    for (String exit : initializer.exits()) cfgEdge(exit, condition, tree);
                }
                FlowFragment body = build(tree.getStatement());
                FlowFragment update = sequence(tree.getUpdate());
                if (body.entry() != null) cfgEdge(condition, body.entry(), tree.getStatement());
                if (update.entry() != null) {
                    for (String exit : body.exits()) cfgEdge(exit, update.entry(), tree);
                    for (String exit : update.exits()) cfgEdge(exit, condition, tree);
                } else {
                    for (String exit : body.exits()) cfgEdge(exit, condition, tree);
                }
                return new FlowFragment(initializer.entry() == null ? condition : initializer.entry(), Set.of(condition));
            }

            private FlowFragment enhancedForFlow(EnhancedForLoopTree tree) {
                String condition = basicBlock(tree);
                FlowFragment body = build(tree.getStatement());
                if (body.entry() != null) cfgEdge(condition, body.entry(), tree.getStatement());
                for (String exit : body.exits()) cfgEdge(exit, condition, tree);
                return new FlowFragment(condition, Set.of(condition));
            }

            private FlowFragment doWhileFlow(DoWhileLoopTree tree) {
                FlowFragment body = build(tree.getStatement());
                String condition = basicBlock(tree);
                for (String exit : body.exits()) cfgEdge(exit, condition, tree);
                if (body.entry() != null) cfgEdge(condition, body.entry(), tree.getStatement());
                return new FlowFragment(body.entry() == null ? condition : body.entry(), Set.of(condition));
            }

            private FlowFragment tryFlow(TryTree tree) {
                String header = basicBlock(tree);
                FlowFragment body = build(tree.getBlock());
                if (body.entry() != null) cfgEdge(header, body.entry(), tree.getBlock());
                Set<String> exits = new LinkedHashSet<>(body.exits());
                for (CatchTree catchTree : tree.getCatches()) {
                    FlowFragment catchFlow = build(catchTree.getBlock());
                    if (catchFlow.entry() != null) cfgEdge(header, catchFlow.entry(), catchTree.getBlock());
                    exits.addAll(catchFlow.exits());
                }
                limitations.add("JAVA_CFG_EXCEPTION_EDGES_CONSERVATIVE");
                if (tree.getFinallyBlock() != null) {
                    FlowFragment finallyFlow = build(tree.getFinallyBlock());
                    if (finallyFlow.entry() != null) {
                        for (String exit : exits) cfgEdge(exit, finallyFlow.entry(), tree.getFinallyBlock());
                        exits = new LinkedHashSet<>(finallyFlow.exits());
                    }
                }
                if (exits.isEmpty()) exits.add(header);
                return new FlowFragment(header, Set.copyOf(exits));
            }

            private FlowFragment synchronizedFlow(SynchronizedTree tree) {
                String header = basicBlock(tree);
                FlowFragment body = build(tree.getBlock());
                if (body.entry() != null) cfgEdge(header, body.entry(), tree.getBlock());
                return new FlowFragment(header, body.exits().isEmpty() ? Set.of(header) : body.exits());
            }

            private String basicBlock(Tree tree) {
                String id = id("bb", unit, tree, tree.getKind().name());
                addNode(factualNode(
                        id,
                        ProgramNodeKind.BASIC_BLOCK,
                        label("cfg " + tree.getKind().name(), unit, tree),
                        location(unit, tree)));
                return id;
            }

            private void cfgEdge(String source, String target, Tree tree) {
                addDerivedEdge(
                        "cfg",
                        source,
                        target,
                        ProgramEdgeKind.CONTROL_FLOW,
                        CFG_CONFIDENCE,
                        "control-flow edge derived from Java statement semantics",
                        location(unit, tree));
            }
        }

        private final class SecurityScanner extends TreeScanner<Set<String>, Void> {
            private final ParsedUnit unit;
            private final Map<String, Set<String>> taint = new LinkedHashMap<>();

            private SecurityScanner(ParsedUnit unit) {
                this.unit = unit;
            }

            @Override
            public Set<String> visitVariable(VariableTree tree, Void unused) {
                Set<String> value = tree.getInitializer() == null ? Set.of() : safe(scan(tree.getInitializer(), null));
                taint.put(tree.getName().toString(), value);
                return Set.of();
            }

            @Override
            public Set<String> visitAssignment(AssignmentTree tree, Void unused) {
                Set<String> value = safe(scan(tree.getExpression(), null));
                if (tree.getVariable() instanceof IdentifierTree identifier) {
                    taint.put(identifier.getName().toString(), value);
                }
                return value;
            }

            @Override
            public Set<String> visitIdentifier(IdentifierTree tree, Void unused) {
                return taint.getOrDefault(tree.getName().toString(), Set.of());
            }

            @Override
            public Set<String> visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                Set<String> incoming = new LinkedHashSet<>();
                for (ExpressionTree argument : tree.getArguments()) incoming.addAll(safe(scan(argument, null)));
                String name = invocationName(tree);
                if (securityRules.source(tree)) {
                    return Set.of(securityNode("source", ProgramNodeKind.SOURCE, name, tree));
                }
                if (securityRules.sanitizer(tree)) {
                    String sanitizer = securityNode("sanitizer", ProgramNodeKind.SANITIZER, name, tree);
                    for (String source : incoming) {
                        securityEdge(source, sanitizer, tree, "configured sanitizer receives an observed tainted value");
                    }
                    return incoming.isEmpty() ? Set.of() : Set.of(sanitizer);
                }
                if (securityRules.sink(tree)) {
                    String sink = securityNode("sink", ProgramNodeKind.SINK, name, tree);
                    for (String source : incoming) {
                        securityEdge(source, sink, tree, "configured sink receives an observed tainted value");
                    }
                    return Set.of();
                }
                if (!incoming.isEmpty()) limitations.add("JAVA_SECURITY_UNKNOWN_CALL_STOPS_FLOW");
                return Set.of();
            }

            @Override
            public Set<String> visitParenthesized(ParenthesizedTree tree, Void unused) {
                return safe(scan(tree.getExpression(), null));
            }

            @Override
            public Set<String> visitTypeCast(TypeCastTree tree, Void unused) {
                return safe(scan(tree.getExpression(), null));
            }

            @Override
            public Set<String> visitConditionalExpression(ConditionalExpressionTree tree, Void unused) {
                return union(scan(tree.getTrueExpression(), null), scan(tree.getFalseExpression(), null));
            }

            @Override
            public Set<String> visitBinary(BinaryTree tree, Void unused) {
                return union(scan(tree.getLeftOperand(), null), scan(tree.getRightOperand(), null));
            }

            @Override
            public Set<String> visitIf(IfTree tree, Void unused) {
                scan(tree.getCondition(), null);
                Map<String, Set<String>> before = copyState(taint);
                taint.clear();
                taint.putAll(copyState(before));
                scan(tree.getThenStatement(), null);
                Map<String, Set<String>> thenState = copyState(taint);
                taint.clear();
                taint.putAll(copyState(before));
                if (tree.getElseStatement() != null) scan(tree.getElseStatement(), null);
                Map<String, Set<String>> elseState = copyState(taint);
                taint.clear();
                taint.putAll(mergeStates(thenState, elseState));
                return Set.of();
            }

            @Override
            public Set<String> visitWhileLoop(WhileLoopTree tree, Void unused) {
                scan(tree.getCondition(), null);
                Map<String, Set<String>> before = copyState(taint);
                scan(tree.getStatement(), null);
                Map<String, Set<String>> after = copyState(taint);
                taint.clear();
                taint.putAll(mergeStates(before, after));
                limitations.add("JAVA_SECURITY_LOOP_FLOW_CONSERVATIVE");
                return Set.of();
            }

            @Override
            public Set<String> visitForLoop(ForLoopTree tree, Void unused) {
                for (StatementTree initializer : tree.getInitializer()) scan(initializer, null);
                if (tree.getCondition() != null) scan(tree.getCondition(), null);
                Map<String, Set<String>> before = copyState(taint);
                scan(tree.getStatement(), null);
                for (ExpressionStatementTree update : tree.getUpdate()) scan(update, null);
                Map<String, Set<String>> after = copyState(taint);
                taint.clear();
                taint.putAll(mergeStates(before, after));
                limitations.add("JAVA_SECURITY_LOOP_FLOW_CONSERVATIVE");
                return Set.of();
            }

            @Override
            public Set<String> visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
                scan(tree.getExpression(), null);
                Map<String, Set<String>> before = copyState(taint);
                scan(tree.getStatement(), null);
                Map<String, Set<String>> after = copyState(taint);
                taint.clear();
                taint.putAll(mergeStates(before, after));
                limitations.add("JAVA_SECURITY_LOOP_FLOW_CONSERVATIVE");
                return Set.of();
            }

            @Override
            public Set<String> visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
                Map<String, Set<String>> before = copyState(taint);
                scan(tree.getStatement(), null);
                scan(tree.getCondition(), null);
                Map<String, Set<String>> after = copyState(taint);
                taint.clear();
                taint.putAll(mergeStates(before, after));
                limitations.add("JAVA_SECURITY_LOOP_FLOW_CONSERVATIVE");
                return Set.of();
            }

            @Override
            public Set<String> visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
                limitations.add("JAVA_SECURITY_LAMBDA_FLOW_NOT_MODELED");
                return Set.of();
            }

            @Override
            public Set<String> visitClass(ClassTree tree, Void unused) {
                limitations.add("JAVA_SECURITY_LOCAL_CLASS_FLOW_NOT_MODELED");
                return Set.of();
            }

            @Override
            public Set<String> reduce(Set<String> left, Set<String> right) {
                return union(left, right);
            }

            private String securityNode(String prefix, ProgramNodeKind kind, String name, Tree tree) {
                String id = id(prefix, unit, tree, name);
                SymbolLocation location = location(unit, tree);
                Evidence evidence = new Evidence(
                        EvidenceType.PROVIDER_FACT,
                        "Java invocation matched explicit M22 security rule: " + name,
                        null,
                        null,
                        location,
                        1.0);
                addNode(new ProgramGraphNode(
                        id,
                        projectId,
                        null,
                        kind,
                        label(prefix + " " + name, unit, tree),
                        location,
                        InformationNature.DERIVED,
                        1.0,
                        derivedOrigin,
                        List.of(evidence)));
                return id;
            }

            private void securityEdge(String source, String target, Tree tree, String description) {
                addDerivedEdge(
                        "taint",
                        source,
                        target,
                        ProgramEdgeKind.TAINT_FLOW,
                        SECURITY_CONFIDENCE,
                        description,
                        location(unit, tree));
            }
        }

        private ProgramGraphNode factualNode(
                String id,
                ProgramNodeKind kind,
                String label,
                SymbolLocation location
        ) {
            return new ProgramGraphNode(
                    id, projectId, null, kind, label, location, InformationNature.FACTUAL, null, astOrigin, List.of());
        }

        private String definitionNode(ParsedUnit unit, Tree tree, String name) {
            String id = id("def", unit, tree, name);
            addNode(factualNode(
                    id,
                    ProgramNodeKind.VARIABLE,
                    label("definition " + name, unit, tree),
                    location(unit, tree)));
            return id;
        }

        private void addNode(ProgramGraphNode node) {
            ProgramGraphNode existing = nodes.putIfAbsent(node.id(), node);
            if (existing != null && !existing.equals(node)) {
                throw new IllegalStateException("conflicting Java provider node id: " + node.id());
            }
        }

        private void addDerivedEdge(
                String prefix,
                String source,
                String target,
                ProgramEdgeKind kind,
                double confidence,
                String description,
                SymbolLocation location
        ) {
            String edgeId = "java:" + prefix + ":" + source + "->" + target;
            Evidence evidence = new Evidence(
                    EvidenceType.DERIVATION_PATH, description, null, null, location, confidence);
            ProgramGraphEdge edge = new ProgramGraphEdge(
                    edgeId,
                    projectId,
                    source,
                    target,
                    kind,
                    InformationNature.DERIVED,
                    confidence,
                    derivedOrigin,
                    List.of(evidence));
            ProgramGraphEdge existing = edges.putIfAbsent(edge.id(), edge);
            if (existing != null && !existing.equals(edge)) {
                throw new IllegalStateException("conflicting Java provider edge id: " + edge.id());
            }
        }

        private String id(String prefix, ParsedUnit unit, Tree tree, String suffix) {
            long start = positions.getStartPosition(unit.tree(), tree);
            long end = positions.getEndPosition(unit.tree(), tree);
            return "java:" + prefix + ":" + unit.fileId() + ":" + Math.max(0L, start) + ":" + Math.max(0L, end)
                    + ":" + compact(suffix);
        }

        private String label(String value, ParsedUnit unit, Tree tree) {
            SymbolLocation location = location(unit, tree);
            return value + " @ " + unit.fileId() + ":" + location.startLine() + ":" + location.startColumn();
        }

        private SymbolLocation location(ParsedUnit unit, Tree tree) {
            long start = positions.getStartPosition(unit.tree(), tree);
            long end = positions.getEndPosition(unit.tree(), tree);
            if (start < 0L) start = 0L;
            if (end <= start) end = start + 1L;
            long last = Math.max(start, end - 1L);
            long startLine = unit.tree().getLineMap().getLineNumber(start);
            long startColumn = unit.tree().getLineMap().getColumnNumber(start) - 1L;
            long endLine = unit.tree().getLineMap().getLineNumber(last);
            long endColumn = unit.tree().getLineMap().getColumnNumber(last);
            return new SymbolLocation(
                    unit.fileId(),
                    safeInt(startLine, 1),
                    safeInt(startColumn, 0),
                    safeInt(endLine, safeInt(startLine, 1)),
                    safeInt(endColumn, 0),
                    PositionEncoding.UTF16_CODE_UNITS);
        }

        private static int safeInt(long value, int fallback) {
            if (value < 0L || value > Integer.MAX_VALUE) return fallback;
            return (int) value;
        }

        private static String compact(String value) {
            return value.replaceAll("[^A-Za-z0-9_.-]", "_");
        }

        private static Map<String, Set<String>> copyState(Map<String, Set<String>> source) {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            source.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
            return copy;
        }

        private static Map<String, Set<String>> mergeStates(
                Map<String, Set<String>> left,
                Map<String, Set<String>> right
        ) {
            Map<String, Set<String>> result = copyState(left);
            right.forEach((name, values) -> {
                Set<String> merged = new LinkedHashSet<>(result.getOrDefault(name, Set.of()));
                merged.addAll(values);
                result.put(name, Set.copyOf(merged));
            });
            return result;
        }

        private static Set<String> safe(Set<String> values) {
            return values == null ? Set.of() : values;
        }

        private static Set<String> union(Set<String> left, Set<String> right) {
            Set<String> result = new LinkedHashSet<>(safe(left));
            result.addAll(safe(right));
            return Set.copyOf(result);
        }
    }

    private static String invocationName(MethodInvocationTree tree) {
        ExpressionTree select = tree.getMethodSelect();
        if (select instanceof IdentifierTree identifier) return identifier.getName().toString();
        if (select instanceof MemberSelectTree memberSelect) return memberSelect.getIdentifier().toString();
        return select.toString();
    }

    private record MethodKey(String name, int arity) {
    }

    private record MethodInfo(
            ParsedUnit unit,
            MethodTree tree,
            List<String> parameterNodeIds,
            List<String> returnNodeIds
    ) {
    }

    private record InvocationInfo(
            ParsedUnit unit,
            MethodInvocationTree tree,
            String name,
            List<String> argumentNodeIds,
            SymbolLocation location
    ) {
    }

    private record FlowFragment(String entry, Set<String> exits) {
        static FlowFragment empty() {
            return new FlowFragment(null, Set.of());
        }
    }
}
