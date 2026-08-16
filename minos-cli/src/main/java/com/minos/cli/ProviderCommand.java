package com.minos.cli;

import com.minos.application.ProviderPlatformService;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only provider capability, qualification and runtime diagnostics. */
public final class ProviderCommand {
    public static final String NAME = "providers";
    private static final String USAGE = "Usage: minos providers [provider-id] [--format <text|json>]";

    private final ProviderPlatformService service;

    public ProviderCommand(ProviderPlatformService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse, NAME, options -> {
            if (options.providerId() == null) {
                List<ProviderPlatformService.ProviderView> providers = service.listProviders();
                output.append(renderList(providers, options.format())).append('\n');
            } else {
                output.append(render(service.inspect(options.providerId()), options.format())).append('\n');
            }
            return FindSymbolCommand.SUCCESS;
        });
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
            lines.add(provider.id() + "\t" + provider.version() + "\t" + provider.qualification()
                    + "\t" + provider.runtimeState() + "\tscore=" + provider.conformanceScorePercent());
        }
        return String.join("\n", lines);
    }

    private static String render(ProviderPlatformService.ProviderView provider, SymbolOutputFormat format) {
        if (format == SymbolOutputFormat.JSON) return CliJson.render(map(provider));
        return String.join("\n",
                "id: " + provider.id(),
                "version: " + provider.version(),
                "qualification: " + provider.qualification(),
                "languages: " + provider.languages(),
                "buildSystems: " + provider.buildSystems(),
                "conformanceScore: " + provider.conformanceScorePercent(),
                "capabilities: " + provider.capabilities(),
                "limitations: " + provider.limitations(),
                "operationalProfileExplicit: " + provider.operationalProfileExplicit(),
                "qualificationPlatforms: " + provider.qualificationPlatforms(),
                "runtimeRequirements: " + provider.runtimeRequirements(),
                "readinessBehavior: " + provider.readinessBehavior(),
                "installationBehavior: " + provider.installationBehavior(),
                "stableIdentityBehavior: " + provider.stableIdentityBehavior(),
                "provenanceBehavior: " + provider.provenanceBehavior(),
                "runtimeState: " + provider.runtimeState(),
                "runtimeDiagnostics: " + provider.runtimeDiagnostics());
    }

    private static Map<String, Object> map(ProviderPlatformService.ProviderView provider) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", provider.id());
        value.put("version", provider.version());
        value.put("qualification", provider.qualification());
        value.put("languages", provider.languages());
        value.put("buildSystems", provider.buildSystems());
        value.put("capabilities", provider.capabilities());
        value.put("conformanceScorePercent", provider.conformanceScorePercent());
        value.put("limitations", provider.limitations());
        value.put("operationalProfileExplicit", provider.operationalProfileExplicit());
        value.put("qualificationPlatforms", provider.qualificationPlatforms());
        value.put("runtimeRequirements", provider.runtimeRequirements());
        value.put("readinessBehavior", provider.readinessBehavior());
        value.put("installationBehavior", provider.installationBehavior());
        value.put("stableIdentityBehavior", provider.stableIdentityBehavior());
        value.put("provenanceBehavior", provider.provenanceBehavior());
        value.put("runtimeState", provider.runtimeState());
        value.put("runtimeDiagnostics", provider.runtimeDiagnostics());
        return value;
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
