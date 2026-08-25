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
        return CliCommandSupport.run(arguments, output, error, USAGE, Parsed::parse, NAME, parsed -> {
            if ("install".equals(parsed.action())) {
                AutonomousIndexOperations.ProviderView installed = operations.installProvider(parsed.provider());
                output.append(render(List.of(installed), parsed.format())).append('\n');
                return FindSymbolCommand.SUCCESS;
            }
            List<AutonomousIndexOperations.ProviderView> providers = operations.providers();
            output.append(render(providers, parsed.format())).append('\n');
            if ("verify".equals(parsed.action())) {
                // UNSUPPORTED_BY_BACKEND means the currently selected backend (e.g. the Docker MCP
                // admin/indexing plane) never claims the stronger sandbox tier this provider would
                // otherwise need -- not that the provider itself is broken. It must never silently
                // pass as READY, but it must also never block a verification/installation that does
                // not actually depend on that tier. Every other non-READY state still blocks.
                boolean notReady = providers.stream()
                        .filter(provider -> parsed.all() || provider.requiredByDefault())
                        .filter(provider -> !"UNSUPPORTED_BY_BACKEND".equals(provider.state()))
                        .anyMatch(provider -> !"READY".equals(provider.state()));
                if (notReady) {
                    return FindSymbolCommand.EXECUTION_ERROR;
                }
            }
            return FindSymbolCommand.SUCCESS;
        });
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
                value.put("diagnostics", CliCommandSupport.publicDiagnostics(provider.diagnostics()));
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
            CliCommandSupport.publicDiagnostics(provider.diagnostics())
                    .forEach(diagnostic -> lines.add("  diagnostic: " + diagnostic));
        }
        return String.join("\n", lines);
    }

    private record Parsed(String action, String provider, boolean all, SymbolOutputFormat format) {
        private static Parsed parse(String[] arguments) {
            if (arguments.length == 0) {
                throw new IllegalArgumentException("tools action is required");
            }
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
