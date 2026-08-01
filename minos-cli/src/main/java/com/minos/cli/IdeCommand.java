package com.minos.cli;

import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned discovery handshake for external IDE clients. */
public final class IdeCommand {

    public static final String NAME = "ide";
    public static final String PROTOCOL_ID = "minos-ide";
    public static final String PROTOCOL_VERSION = "1";

    private static final List<String> CAPABILITIES = List.of(
            "project-status",
            "symbol-navigation",
            "architecture",
            "impact",
            "related-tests",
            "index-lifecycle",
            "doctor",
            "git-activity",
            "program-graph",
            "impact-v2",
            "security-paths",
            "semantic-index-status",
            "semantic-index-sync",
            "semantic-search",
            "hybrid-search",
            "hybrid-context"
    );

    private static final String USAGE = """
            Usage: minos ide handshake [--format <text|json>]
            """.stripTrailing();

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length == 1 && isHelp(arguments[0])) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (arguments.length == 0 || !"handshake".equals(arguments[0])) {
            error.append("error: expected `ide handshake`\n").append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }

        SymbolOutputFormat format;
        try {
            format = parseFormat(arguments);
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n').append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }

        if (format == SymbolOutputFormat.JSON) {
            Map<String, Object> handshake = new LinkedHashMap<>();
            handshake.put("protocol", PROTOCOL_ID);
            handshake.put("protocolVersion", PROTOCOL_VERSION);
            handshake.put("minCompatibleVersion", PROTOCOL_VERSION);
            handshake.put("maxCompatibleVersion", PROTOCOL_VERSION);
            handshake.put("transport", "cli-json-process");
            handshake.put("capabilities", CAPABILITIES);
            output.append(CliJson.render(handshake)).append('\n');
        } else {
            output.append("protocol: ").append(PROTOCOL_ID).append('\n')
                    .append("protocolVersion: ").append(PROTOCOL_VERSION).append('\n')
                    .append("transport: cli-json-process\n")
                    .append("capabilities: ").append(String.join(",", CAPABILITIES)).append('\n');
        }
        return FindSymbolCommand.SUCCESS;
    }

    public static String usage() {
        return USAGE;
    }

    private static SymbolOutputFormat parseFormat(String[] arguments) {
        if (arguments.length == 1) {
            return SymbolOutputFormat.JSON;
        }
        if (arguments.length != 3 || !"--format".equals(arguments[1])) {
            throw new IllegalArgumentException("only --format is supported after handshake");
        }
        return SymbolOutputFormat.parse(arguments[2]);
    }

    private static boolean isHelp(String value) {
        return "--help".equals(value) || "-h".equals(value);
    }
}
