package com.minos.cli;

import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Administration des providers gérés par MINOS. */
public final class ToolsCommand {

    public static final String NAME = "tools";
    private static final String USAGE = """
            Usage: minos tools <list|install|verify> [provider] [--all] [--format <text|json>]

              list                  List managed providers and runtime state
              verify                Verify baseline-required provider runtimes
              verify --all          Verify every provider runtime advertised by MINOS
              install <provider>    Install or bootstrap a managed provider
            """.stripTrailing();

    private final AutonomousIndexOperations operations;

    public ToolsCommand(AutonomousIndexOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length == 1 && ("--help".equals(arguments[0]) || "-h".equals(arguments[0]))) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        if (arguments.length == 0) {
            error.append("error: tools action is required\n").append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }
        try {
            Parsed parsed = Parsed.parse(arguments);
            if ("install".equals(parsed.action())) {
                AutonomousIndexOperations.ProviderView installed = operations.installProvider(parsed.provider());
                output.append(render(List.of(installed), parsed.format())).append('\n');
                return FindSymbolCommand.SUCCESS;
            }
            List<AutonomousIndexOperations.ProviderView> providers = operations.providers();
            output.append(render(providers, parsed.format())).append('\n');
            if ("verify".equals(parsed.action())) {
                boolean notReady = providers.stream()
                        .filter(provider -> parsed.all() || provider.requiredByDefault())
                        .anyMatch(provider -> !"READY".equals(provider.state()));
                if (notReady) {
                    return FindSymbolCommand.EXECUTION_ERROR;
                }
            }
            return FindSymbolCommand.SUCCESS;
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n').append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        } catch (Exception exception) {
            String message = exception.getMessage();
            error.append("error: tools failed: ")
                    .append(message == null || message.isBlank() ? exception.getClass().getSimpleName() : message)
                    .append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    static String render(List<AutonomousIndexOperations.ProviderView> providers, SymbolOutputFormat format) {
        if (format == SymbolOutputFormat.JSON) {
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
            return CliJson.render(Map.of("providers", values));
        }
        List<String> lines = new ArrayList<>();
        for (var provider : providers) {
            lines.add(provider.id() + " " + provider.version() + " — " + provider.state()
                    + (provider.requiredByDefault() ? " [required]" : " [optional]"));
            if (provider.executable() != null) {
                lines.add("  executable: " + provider.executable());
            }
            provider.diagnostics().forEach(diagnostic -> lines.add("  diagnostic: " + diagnostic));
        }
        return String.join("\n", lines);
    }

    private record Parsed(String action, String provider, boolean all, SymbolOutputFormat format) {
        private static Parsed parse(String[] arguments) {
            String action = arguments[0];
            if (!List.of("list", "verify", "install").contains(action)) {
                throw new IllegalArgumentException("unknown tools action: " + action);
            }
            String provider = null;
            boolean all = false;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            int index = 1;
            if ("install".equals(action)) {
                if (index >= arguments.length || arguments[index].startsWith("-")) {
                    throw new IllegalArgumentException("tools install requires <provider>");
                }
                provider = arguments[index++];
            }
            while (index < arguments.length) {
                String option = arguments[index];
                if ("--all".equals(option)) {
                    if (!"verify".equals(action)) {
                        throw new IllegalArgumentException("--all is only valid with tools verify");
                    }
                    if (all) {
                        throw new IllegalArgumentException("--all may only be specified once");
                    }
                    all = true;
                    index++;
                    continue;
                }
                if (!"--format".equals(option) || index + 1 >= arguments.length) {
                    throw new IllegalArgumentException("unexpected tools option: " + option);
                }
                format = SymbolOutputFormat.parse(arguments[index + 1]);
                index += 2;
            }
            return new Parsed(action, provider, all, format);
        }
    }
}
