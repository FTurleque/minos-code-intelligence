package com.minos.cli.command;

import com.minos.application.semantic.SemanticCorpusQueryService;
import com.minos.application.semantic.SemanticProjectionService;
import com.minos.cli.support.CliCommandSupport;
import com.minos.cli.support.CliExitCodes;
import com.minos.cli.support.CliJson;
import com.minos.cli.support.CliOptions;
import com.minos.cli.support.LazyApplication;
import com.minos.output.SymbolOutputFormat;
import com.minos.semantic.SemanticDocumentKind;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@code minos semantic projection prepare|status}: the only way a projection is computed, and the reading of
 * its state. The preparation reports its progress on the error stream and its result on the output; the
 * arguments are analysed before the service is asked for.
 */
public final class SemanticProjectionCommand {

    public static final String NAME = "projection";
    public static final String USAGE = """
            Usage: minos semantic projection prepare <project> [--max-points <n>] [--seed <n>] \
            [--kinds <CHUNK[,SYMBOL,FILE]>] [--max-memory-mb <n>] [--format <text|json>]
                   minos semantic projection status <project> [--format <text|json>]

            prepare computes the 2D projection (PCA) of a READY semantic index and publishes it; nothing else \
            ever computes one.
            status reads the state of the published projection: ABSENTE, PRETE, PERIMEE or ECHOUEE.
            """.stripTrailing();

    private static final String PREPARE = "prepare";
    private static final String STATUS = "status";
    private static final int MAX_MEMORY_MEGABYTES = 65_536;
    private static final long MEGABYTE = 1024L * 1024L;
    private static final CliOptions.Spec PREPARE_OPTIONS = CliOptions.spec()
            .integer("--max-points", 1, SemanticProjectionService.MAX_POINTS)
            .integer("--seed")
            .text("--kinds")
            .integer("--max-memory-mb", 1, MAX_MEMORY_MEGABYTES)
            .text("--format").operands(1);
    private static final CliOptions.Spec STATUS_OPTIONS = CliOptions.spec().text("--format").operands(1);

    private final Supplier<SemanticProjectionService> service;

    public SemanticProjectionCommand(Supplier<SemanticProjectionService> service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");
        if (arguments.length >= 1 && CliCommandSupport.isHelp(arguments[arguments.length - 1])) {
            output.append(USAGE).append('\n');
            return CliExitCodes.SUCCESS;
        }
        Invocation invocation;
        try {
            invocation = Invocation.parse(arguments);
        } catch (IllegalArgumentException usage) {
            error.append("error: ").append(usage.getMessage()).append('\n').append(USAGE).append('\n');
            return CliExitCodes.USAGE_ERROR;
        }
        try {
            Map<String, Object> result = invocation.prepare()
                    ? prepare(invocation, error)
                    : status(service.get().status(invocation.project()));
            output.append(render(result, invocation.format())).append('\n');
            return CliExitCodes.SUCCESS;
        } catch (LazyApplication.OpenFailure openFailure) {
            throw openFailure;
        } catch (IOException | RuntimeException failure) {
            error.append("error: semantic projection ").append(invocation.prepare() ? PREPARE : STATUS)
                    .append(" failed: ").append(CliCommandSupport.failureMessage(failure)).append('\n');
            return CliExitCodes.EXECUTION_ERROR;
        }
    }

    private Map<String, Object> prepare(Invocation invocation, Appendable error) throws IOException {
        PassReporter reporter = new PassReporter(error);
        SemanticProjectionService.PreparationReport report =
                service.get().prepare(invocation.project(), invocation.request(), reporter);
        reporter.end();
        Runtime runtime = Runtime.getRuntime();
        error.append("projection: published in ").append(Long.toString(report.durationMillis()))
                .append(" ms, working memory ").append(Long.toString(report.peakWorkingBytes()))
                .append(" bytes, heap in use ").append(Long.toString(runtime.totalMemory() - runtime.freeMemory()))
                .append(" bytes\n");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("projectId", report.projectId());
        value.put("state", SemanticProjectionService.State.PRETE.name());
        value.put("method", SemanticProjectionService.METHOD);
        value.put("seed", invocation.request().seed());
        value.put("pointCount", report.pointCount());
        value.put("candidateCount", report.candidateCount());
        value.put("documentCount", report.documentCount());
        value.put("sampled", report.sampled());
        value.put("explainedVariance", report.explainedVariance());
        value.put("passes", report.passes());
        value.put("durationMillis", report.durationMillis());
        value.put("peakWorkingBytes", report.peakWorkingBytes());
        return value;
    }

    private static Map<String, Object> status(SemanticProjectionService.Status status) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("projectId", status.projectId());
        value.put("state", status.state().name());
        value.put("cause", status.cause());
        value.put("indexDocumentCount", status.indexDocumentCount());
        value.put("prepareCommand", status.prepareCommand());
        if (status.projection().isPresent()) {
            SemanticProjectionService.Description projection = status.projection().orElseThrow();
            value.put("snapshotId", projection.index().snapshotId());
            value.put("providerId", projection.index().providerId());
            value.put("modelId", projection.index().modelId());
            value.put("dimensions", projection.index().dimensions());
            value.put("documentsFingerprint", projection.index().documentsFingerprint());
            value.put("method", projection.method());
            value.put("methodVersion", projection.methodVersion());
            value.put("parameters", projection.parameters());
            value.put("seed", projection.seed());
            value.put("kinds", projection.kinds());
            value.put("maxPoints", projection.maxPoints());
            value.put("pointCount", projection.pointCount());
            value.put("candidateCount", projection.candidateCount());
            value.put("documentCount", projection.documentCount());
            value.put("sampled", projection.sampled());
            value.put("explainedVariance", projection.explainedVariance());
        }
        return value;
    }

    private static String render(Map<String, Object> value, SymbolOutputFormat format) {
        if (format == SymbolOutputFormat.JSON) return CliJson.render(value);
        List<String> lines = new ArrayList<>();
        // A value read back from a projection file is data: it is made printable before it reaches a terminal.
        value.forEach((key, item) -> lines.add(key + ": " + CliCommandSupport.publicDiagnostic(String.valueOf(item))));
        return String.join("\n", lines);
    }

    /** One line on the error stream for each pass over the index, written when the pass ends. */
    private static final class PassReporter implements SemanticProjectionService.Progress {
        private final Appendable error;
        private String phase;
        private int pass;
        private long vectorsRead;

        PassReporter(Appendable error) {
            this.error = error;
        }

        @Override
        public void advanced(String currentPhase, int currentPass, long currentVectorsRead) {
            if (currentPass != pass) end();
            if (SemanticProjectionService.PUBLISH_PHASE.equals(currentPhase)) return;
            phase = currentPhase;
            pass = currentPass;
            vectorsRead = currentVectorsRead;
        }

        void end() {
            if (phase == null) return;
            try {
                error.append("projection: pass ").append(Integer.toString(pass)).append(" (").append(phase)
                        .append("), ").append(Long.toString(vectorsRead)).append(" vectors read\n");
            } catch (IOException unwritable) {
                // The error stream is where a failure would be reported: without it the preparation stops.
                throw new java.io.UncheckedIOException(unwritable);
            }
            phase = null;
        }
    }

    private record Invocation(boolean prepare, String project, SymbolOutputFormat format,
                              SemanticProjectionService.PreparationRequest request) {

        static Invocation parse(String[] arguments) {
            if (arguments.length == 0 || !(PREPARE.equals(arguments[0]) || STATUS.equals(arguments[0]))) {
                throw new IllegalArgumentException("prepare or status, then a project reference, are required");
            }
            boolean prepare = PREPARE.equals(arguments[0]);
            CliOptions options = (prepare ? PREPARE_OPTIONS : STATUS_OPTIONS).parse(arguments, 1);
            if (options.operands().isEmpty()) throw new IllegalArgumentException("project reference is required");
            String project = options.operands().getFirst();
            if (!prepare) return new Invocation(false, project, options.format(), null);
            return new Invocation(true, project, options.format(), new SemanticProjectionService.PreparationRequest(
                    options.integer("--max-points", SemanticProjectionService.DEFAULT_MAX_POINTS),
                    options.integer("--seed", (int) SemanticProjectionService.DEFAULT_SEED),
                    kinds(options.text("--kinds")),
                    options.integer("--max-memory-mb", SemanticProjectionService.DEFAULT_MAX_MEMORY_MEGABYTES)
                            * MEGABYTE));
        }

        private static Set<SemanticDocumentKind> kinds(String value) {
            if (value == null) return EnumSet.of(SemanticDocumentKind.CHUNK);
            Set<SemanticDocumentKind> kinds = EnumSet.noneOf(SemanticDocumentKind.class);
            for (String name : value.split(",", -1)) {
                kinds.add(SemanticCorpusQueryService.parseKind(name.trim()));
            }
            return kinds;
        }
    }
}
