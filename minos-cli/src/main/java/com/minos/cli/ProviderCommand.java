package com.minos.cli;

import com.minos.application.ProviderPlatformService;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only M17 provider capability/limitation diagnostics. */
public final class ProviderCommand {
    public static final String NAME = "providers";
    private static final String USAGE = "Usage: minos providers [provider-id] [--format <text|json>]";

    private final ProviderPlatformService service;

    public ProviderCommand(ProviderPlatformService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length == 1 && isHelp(arguments[0])) {
            output.append(USAGE).append('\n');
            return FindSymbolCommand.SUCCESS;
        }
        try {
            Options options = Options.parse(arguments);
            if (options.providerId() == null) {
                List<ProviderPlatformService.ProviderView> providers = service.listProviders();
                output.append(renderList(providers, options.format())).append('\n');
            } else {
                output.append(render(service.inspect(options.providerId()), options.format())).append('\n');
            }
            return FindSymbolCommand.SUCCESS;
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(exception.getMessage()).append('\n').append(USAGE).append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        } catch (RuntimeException exception) {
            error.append("error: providers failed: ").append(message(exception)).append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
    }

    public static String usage() { return USAGE; }

    private static String renderList(List<ProviderPlatformService.ProviderView> providers, SymbolOutputFormat format) {
        if (format == SymbolOutputFormat.JSON) {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("count", providers.size());
            root.put("providers", providers.stream().map(ProviderCommand::map).toList());
            return CliJson.render(root);
        }
        List<String> lines = new ArrayList<>();
        for (ProviderPlatformService.ProviderView provider : providers) {
            lines.add(provider.id() + "\t" + provider.version() + "\t" + provider.runtimeState()
                    + "\tscore=" + provider.conformanceScorePercent());
        }
        return String.join("\n", lines);
    }

    private static String render(ProviderPlatformService.ProviderView provider, SymbolOutputFormat format) {
        if (format == SymbolOutputFormat.JSON) return CliJson.render(map(provider));
        return String.join("\n",
                "id: " + provider.id(),
                "version: " + provider.version(),
                "languages: " + provider.languages(),
                "buildSystems: " + provider.buildSystems(),
                "conformanceScore: " + provider.conformanceScorePercent(),
                "runtimeState: " + provider.runtimeState(),
                "capabilities: " + provider.capabilities(),
                "limitations: " + provider.limitations(),
                "runtimeDiagnostics: " + provider.runtimeDiagnostics());
    }

    private static Map<String, Object> map(ProviderPlatformService.ProviderView provider) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", provider.id());
        value.put("version", provider.version());
        value.put("languages", provider.languages());
        value.put("buildSystems", provider.buildSystems());
        value.put("capabilities", provider.capabilities());
        value.put("conformanceScorePercent", provider.conformanceScorePercent());
        value.put("limitations", provider.limitations());
        value.put("runtimeState", provider.runtimeState());
        value.put("runtimeDiagnostics", provider.runtimeDiagnostics());
        return value;
    }

    private static boolean isHelp(String argument) { return "--help".equals(argument) || "-h".equals(argument); }
    private static String message(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }

    private record Options(String providerId, SymbolOutputFormat format) {
        private static Options parse(String[] arguments) {
            String providerId = null;
            SymbolOutputFormat format = SymbolOutputFormat.TEXT;
            for (int i = 0; i < arguments.length; i++) {
                String argument = arguments[i];
                if ("--format".equals(argument)) {
                    if (++i >= arguments.length) throw new IllegalArgumentException("--format requires a value");
                    format = switch (arguments[i].toLowerCase()) {
                        case "text" -> SymbolOutputFormat.TEXT;
                        case "json" -> SymbolOutputFormat.JSON;
                        default -> throw new IllegalArgumentException("unsupported format: " + arguments[i]);
                    };
                } else if (argument.startsWith("--")) {
                    throw new IllegalArgumentException("unknown option: " + argument);
                } else if (providerId == null) {
                    providerId = argument;
                } else {
                    throw new IllegalArgumentException("unexpected argument: " + argument);
                }
            }
            return new Options(providerId, format);
        }
    }
}
