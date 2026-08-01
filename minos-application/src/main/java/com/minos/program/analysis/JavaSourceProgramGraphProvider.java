package com.minos.program.analysis;

import com.minos.program.ProgramGraph;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;

import java.io.IOException;
import java.util.Objects;

/**
 * Conservative M22 reference provider for Java advanced-program facts derived from the public JDK compiler AST.
 *
 * <p>The public provider is intentionally a thin stable facade. Source discovery/confinement, parsing,
 * control flow, def-use, interprocedural resolution, configured taint analysis and graph assembly are
 * implemented by focused package components. The behavior and claims remain fail-closed: every Java source
 * represented by the active snapshot must be confined, present, bounded and syntactically parseable; no
 * guessed-classpath attribution is performed; local def-use remains name-based; and interprocedural flow is
 * emitted only for a unique project-local simple-name and arity match.</p>
 */
public final class JavaSourceProgramGraphProvider implements ProgramGraphProvider {

    public static final String PROVIDER_ID = "minos-java-source-v1";
    public static final String SECURITY_CONFIG = ".minos/java-advanced-provider.properties";

    private final JavaProgramGraphEngine engine;

    public JavaSourceProgramGraphProvider() {
        this(new JavaProgramGraphEngine());
    }

    JavaSourceProgramGraphProvider(JavaProgramGraphEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String cacheKey(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        return engine.cacheKey(project, snapshot);
    }

    @Override
    public ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException {
        return engine.analyze(project, snapshot);
    }
}
