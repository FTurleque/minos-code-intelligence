package com.minos.cli;

import com.minos.application.dynamic.RuntimeIntelligenceService;
import com.minos.application.dynamic.RuntimeIntelligenceService.HotPath;
import com.minos.application.dynamic.RuntimeIntelligenceService.RuntimeReport;
import com.minos.application.dynamic.RuntimeIntelligenceService.SessionView;
import com.minos.application.dynamic.RuntimeIntelligenceService.SymbolRuntimeReport;
import com.minos.application.dynamic.RuntimeObservationEnvelopeCodec;
import com.minos.output.RuntimeIntelligenceRenderer;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** CLI import and read surface for explicitly partial M26 runtime observations. */
public final class RuntimeCommand {

    public static final String NAME = "runtime";
    private static final String USAGE = """
            Usage:
              minos runtime import <project> --file <path> [--format <text|json>]
              minos runtime sessions <project> [--limit <1..128>] [--format <text|json>]
              minos runtime report <project> [--session <id>] [--limit <1..1000>] [--format <text|json>]
              minos runtime symbol <project> --symbol <id> [--session <id>]
                    [--limit <1..1000>] [--format <text|json>]

            Import format:
              minos-runtime-observation-v1, strict UTF-8 TSV, completeness PARTIAL only.

            Semantics:
              Every result is OBSERVED_PARTIAL. Absence never proves non-execution and
              runtime observations never mutate static snapshots or provider capabilities.
            """.stripTrailing();

    private static final CliOptions.Spec IMPORT_OPTIONS = CliOptions.spec().text("--file", "--format");
    private static final CliOptions.Spec SESSIONS_OPTIONS = CliOptions.spec().text("--format").integer("--limit", 1, 128);
    private static final CliOptions.Spec REPORT_OPTIONS = CliOptions.spec()
            .text("--session", "--format").integer("--limit", 1, 1_000);
    private static final CliOptions.Spec SYMBOL_OPTIONS = CliOptions.spec()
            .text("--symbol", "--session", "--format").integer("--limit", 1, 1_000);

    private final Supplier<RuntimeIntelligenceService> service;
    private final RuntimeObservationEnvelopeCodec codec;

    public RuntimeCommand(RuntimeIntelligenceService service) {
        this(service, new RuntimeObservationEnvelopeCodec());
    }

    RuntimeCommand(RuntimeIntelligenceService service, RuntimeObservationEnvelopeCodec codec) {
        this(supplying(service), codec);
    }

    /** Service construit à son premier appel, c'est-à-dire après l'analyse des arguments. */
    RuntimeCommand(Supplier<RuntimeIntelligenceService> service) {
        this(service, new RuntimeObservationEnvelopeCodec());
    }

    private RuntimeCommand(Supplier<RuntimeIntelligenceService> service, RuntimeObservationEnvelopeCodec codec) {
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
    }

    private static Supplier<RuntimeIntelligenceService> supplying(RuntimeIntelligenceService service) {
        java.util.Objects.requireNonNull(service, "service");
        return () -> service;
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse,
                (options, exception) -> "runtime " + options.action().token + " failed: "
                        + CliCommandSupport.failureMessage(CliCommandSupport.unwrapRuntime(exception)),
                options -> {
                    String rendered = switch (options.action()) {
                        case IMPORT -> renderImport(options);
                        case SESSIONS -> renderSessions(options);
                        case REPORT -> renderReport(options);
                        case SYMBOL -> renderSymbol(options);
                    };
                    output.append(rendered).append('\n');
                    return FindSymbolCommand.SUCCESS;
                });
    }

    public static String usage() { return USAGE; }

    private String renderImport(Options options) throws IOException {
        var envelope = codec.read(options.file());
        var result = service.get().importSession(options.project(), envelope);
        if (options.format() == SymbolOutputFormat.JSON) return RuntimeIntelligenceRenderer.renderImport(result);
        return String.join("\n",
                "nature: " + result.nature(),
                "exhaustive: " + result.exhaustive(),
                "projectId: " + result.projectId(),
                "snapshotId: " + result.snapshotId(),
                "sessionId: " + result.sessionId(),
                "sourceSha256: " + result.sourceSha256(),
                "observations: " + result.observationCount(),
                "resolvedReferences: " + result.resolvedReferences(),
                "unresolvedReferences: " + result.unresolvedReferences(),
                "ambiguousReferences: " + result.ambiguousReferences(),
                "alreadyPresent: " + result.alreadyPresent(),
                "note: absence of an observation never proves non-execution");
    }

    private String renderSessions(Options options) throws IOException {
        List<SessionView> sessions = service.get().listSessions(options.project(), options.limit());
        if (options.format() == SymbolOutputFormat.JSON) return RuntimeIntelligenceRenderer.renderSessions(sessions);
        List<String> lines = new ArrayList<>();
        lines.add("nature: OBSERVED_PARTIAL");
        lines.add("exhaustive: false");
        lines.add("sessions: " + sessions.size());
        for (SessionView value : sessions) {
            lines.add("session\t" + value.sessionId() + "\t" + value.snapshotId() + "\t"
                    + value.startedAt() + "\t" + value.endedAt() + "\t" + value.observationCount()
                    + "\taligned=" + value.activeSnapshotAligned());
        }
        lines.add("note: absence of a session or observation never proves non-execution");
        return String.join("\n", lines);
    }

    private String renderReport(Options options) throws IOException {
        RuntimeReport report = service.get().report(options.project(), options.sessionId(), options.limit());
        if (options.format() == SymbolOutputFormat.JSON) return RuntimeIntelligenceRenderer.renderReport(report);
        List<String> lines = new ArrayList<>();
        lines.add("nature: " + report.nature());
        lines.add("exhaustive: " + report.exhaustive());
        lines.add("snapshotId: " + report.snapshotId());
        lines.add("sessions: " + report.sessions().size());
        lines.add("staticSymbols: " + report.staticSymbolCount());
        lines.add("observedSymbols: " + report.observedSymbolCount());
        lines.add("observedSymbolRatio: " + report.observedSymbolRatio());
        lines.add("coveredLines: " + report.coveredLineCount());
        lines.add("totalHits: " + report.totalHits());
        lines.add("totalDurationNanos: " + report.totalDurationNanos());
        for (HotPath hot : report.hotPaths()) {
            lines.add("hot\t" + hot.type() + "\t" + hot.key() + "\t" + hot.hits() + "\t" + hot.totalDurationNanos());
        }
        lines.add("note: observedSymbolRatio is not exhaustive code coverage; absence never proves non-execution");
        return String.join("\n", lines);
    }

    private String renderSymbol(Options options) throws IOException {
        SymbolRuntimeReport report = service.get().symbolReport(
                options.project(), options.symbolId(), options.sessionId(), options.limit());
        if (options.format() == SymbolOutputFormat.JSON) return RuntimeIntelligenceRenderer.renderSymbol(report);
        return String.join("\n",
                "nature: " + report.nature(),
                "exhaustive: " + report.exhaustive(),
                "snapshotId: " + report.snapshotId(),
                "symbolId: " + report.symbolId(),
                "symbolKey: " + report.symbolKey(),
                "observedInSelectedSessions: " + report.observedInSelectedSessions(),
                "executionHits: " + report.executionHits(),
                "coveredLineHits: " + report.coveredLineHits(),
                "incomingCalls: " + report.incomingCalls().size(),
                "outgoingCalls: " + report.outgoingCalls().size(),
                "absenceMeaning: NOT_OBSERVED_IN_SELECTED_PARTIAL_SESSIONS");
    }

    private enum Action {
        IMPORT("import"), SESSIONS("sessions"), REPORT("report"), SYMBOL("symbol");
        private final String token;
        Action(String token) { this.token = token; }
    }

    private record Options(
            Action action,
            String project,
            Path file,
            String sessionId,
            String symbolId,
            int limit,
            SymbolOutputFormat format
    ) {
        private static Options parse(String[] arguments) {
            if (arguments.length < 2) throw new IllegalArgumentException("expected <import|sessions|report|symbol> <project>");
            Action action = switch (arguments[0]) {
                case "import" -> Action.IMPORT;
                case "sessions" -> Action.SESSIONS;
                case "report" -> Action.REPORT;
                case "symbol" -> Action.SYMBOL;
                default -> throw new IllegalArgumentException("unknown runtime action: " + arguments[0]);
            };
            String project = CliCommandSupport.operand(arguments[1], "project");
            CliOptions.Spec spec = switch (action) {
                case IMPORT -> IMPORT_OPTIONS;
                case SESSIONS -> SESSIONS_OPTIONS;
                case REPORT -> REPORT_OPTIONS;
                case SYMBOL -> SYMBOL_OPTIONS;
            };
            CliOptions options = spec.parse(arguments, 2);
            String file = action == Action.IMPORT ? options.text("--file") : null;
            String symbol = action == Action.SYMBOL ? options.text("--symbol") : null;
            if (action == Action.IMPORT && file == null) throw new IllegalArgumentException("--file is required for runtime import");
            if (action == Action.SYMBOL && (symbol == null || symbol.isBlank())) {
                throw new IllegalArgumentException("--symbol is required for runtime symbol");
            }
            // Every action defaults to 20; only the accepted ceiling is action-specific (see the specs).
            int limit = action == Action.IMPORT ? 20 : options.integer("--limit", 20);
            String session = action == Action.REPORT || action == Action.SYMBOL ? options.text("--session") : null;
            return new Options(action, project, file == null ? null : Path.of(file), session, symbol, limit,
                    options.format());
        }
    }
}
