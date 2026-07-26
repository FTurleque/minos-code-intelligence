package com.minos.cli;

import com.minos.output.SymbolOutputFormat;
import com.minos.runtime.CommandLocator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Installation and provider-runtime diagnostics. */
public final class DoctorCommand {
    public static final String NAME = "doctor";
    private final Path home;
    private final AutonomousIndexOperations operations;

    public DoctorCommand(Path home, AutonomousIndexOperations operations) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        SymbolOutputFormat format;
        try {
            format = parse(arguments);
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n')
                    .append("Usage: minos doctor [--format <text|json>]\n");
            return FindSymbolCommand.USAGE_ERROR;
        }
        List<AutonomousIndexOperations.ProviderView> providers = operations.providers();
        Map<String, String> commands = new LinkedHashMap<>();
        for (String command : List.of("java", "javac", "mvn", "node", "npm", "python", "docker")) {
            commands.put(command, CommandLocator.find(command).map(Path::toString).orElse(null));
        }
        boolean ready = providers.stream()
                .filter(AutonomousIndexOperations.ProviderView::requiredByDefault)
                .allMatch(provider -> "READY".equals(provider.state()));
        if (format == SymbolOutputFormat.JSON) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("minosHome", home.toString());
            map.put("javaRuntime", System.getProperty("java.runtime.version"));
            map.put("javaHome", System.getProperty("java.home"));
            map.put("commands", commands);
            List<Map<String, Object>> values = new ArrayList<>();
            for (var provider : providers) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", provider.id());
                value.put("version", provider.version());
                value.put("state", provider.state());
                value.put("requiredByDefault", provider.requiredByDefault());
                value.put("executable", provider.executable());
                value.put("diagnostics", provider.diagnostics());
                values.add(value);
            }
            map.put("providers", values);
            map.put("ready", ready);
            output.append(CliJson.render(map)).append('\n');
        } else {
            output.append("MINOS_HOME: ").append(home.toString()).append('\n');
            output.append("Java runtime: ").append(System.getProperty("java.runtime.version", "unknown")).append('\n');
            output.append("Java home: ").append(System.getProperty("java.home", "unknown")).append('\n');
            for (Map.Entry<String, String> entry : commands.entrySet()) {
                output.append("command[").append(entry.getKey()).append("]: ")
                        .append(entry.getValue() == null ? "NOT_FOUND" : entry.getValue()).append('\n');
            }
            output.append(ToolsCommand.render(providers, SymbolOutputFormat.TEXT)).append('\n');
            output.append("verdict: ").append(ready ? "READY" : "ACTION_REQUIRED").append('\n');
        }
        return ready ? FindSymbolCommand.SUCCESS : FindSymbolCommand.EXECUTION_ERROR;
    }

    private static SymbolOutputFormat parse(String[] arguments) {
        if (arguments.length == 0) return SymbolOutputFormat.TEXT;
        if (arguments.length == 1 && ("--help".equals(arguments[0]) || "-h".equals(arguments[0]))) return SymbolOutputFormat.TEXT;
        if (arguments.length == 2 && "--format".equals(arguments[0])) return SymbolOutputFormat.parse(arguments[1]);
        throw new IllegalArgumentException("unexpected doctor arguments");
    }
}
