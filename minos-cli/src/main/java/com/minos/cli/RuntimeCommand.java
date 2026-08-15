package com.minos.cli;

import com.minos.dynamic.RuntimeIntelligenceService;
import com.minos.dynamic.RuntimeIntelligenceService.HotPath;
import com.minos.dynamic.RuntimeIntelligenceService.RuntimeReport;
import com.minos.dynamic.RuntimeIntelligenceService.SessionView;
import com.minos.dynamic.RuntimeIntelligenceService.SymbolRuntimeReport;
import com.minos.dynamic.RuntimeObservationEnvelopeCodec;
import com.minos.output.RuntimeIntelligenceRenderer;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    private final RuntimeIntelligenceService service;
    private final RuntimeObservationEnvelopeCodec codec;

    public RuntimeCommand(RuntimeIntelligenceService service) {
        this(service, new RuntimeObservationEnvelopeCodec());
    }

    RuntimeCommand(RuntimeIntelligenceService service, RuntimeObservationEnvelopeCodec codec) {
        this.service = java.util.Objects.requireNonNull(service, "service");
        this.codec = java.util.Objects.requireNonNull(codec, "codec");
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
        var result = service.importSession(options.project(), codec.read(options.file()));
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
        List<SessionView> sessions = service.listSessions(options.project(), options.limit());
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
        RuntimeReport report = service.report(options.project(), options.sessionId(), options.limit());
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
        SymbolRuntimeReport report = service.symbolReport(
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
            Path file = null;
            String session = null;
            String symbol = null;
            // Every action defaults to 20; only the accepted ceiling is action-specific (see --limit).
            int limit = 20;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            Set<String> seen = new HashSet<>();
            Set<String> allowed = switch (action) {
                case IMPORT -> Set.of("--file", "--format");
                case SESSIONS -> Set.of("--limit", "--format");
                case REPORT -> Set.of("--session", "--limit", "--format");
                case SYMBOL -> Set.of("--symbol", "--session", "--limit", "--format");
            };
            for (int index = 2; index < arguments.length; index++) {
                String option = arguments[index];
                if (!allowed.contains(option)) throw new IllegalArgumentException("unknown option: " + option);
                if (!seen.add(option)) throw new IllegalArgumentException("duplicate option: " + option);
                if (++index >= arguments.length || arguments[index] == null || arguments[index].isBlank()
                        || arguments[index].startsWith("--")) throw new IllegalArgumentException("missing value for " + option);
                String value = arguments[index];
                switch (option) {
                    case "--file" -> file = Path.of(value);
                    case "--session" -> session = value;
                    case "--symbol" -> symbol = value;
                    case "--limit" -> limit = boundedInt(value, action == Action.SESSIONS ? 128 : 1_000);
                    case "--format" -> format = SymbolOutputFormat.parse(value);
                    default -> throw new IllegalStateException("unhandled runtime option: " + option);
                }
            }
            if (action == Action.IMPORT && file == null) throw new IllegalArgumentException("--file is required for runtime import");
            if (action == Action.SYMBOL && (symbol == null || symbol.isBlank())) {
                throw new IllegalArgumentException("--symbol is required for runtime symbol");
            }
            return new Options(action, project, file, session, symbol, limit, format);
        }


        private static int boundedInt(String value, int maximum) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed < 1 || parsed > maximum) throw new IllegalArgumentException("--limit must be between 1 and " + maximum);
                return parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--limit must be an integer");
            }
        }
    }
}
