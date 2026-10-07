package com.minos.cli;

import com.minos.application.ProviderPlatformService;
import com.minos.output.SymbolOutputFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Read-only provider capability, qualification and runtime diagnostics. */
public final class ProviderCommand {
    public static final String NAME = "providers";
    private static final String USAGE = "Usage: minos providers [provider-id] [--format <text|json>]";

    private static final CliOptions.Spec OPTIONS = CliOptions.spec().text("--format").operands(1);

    private final Supplier<ProviderPlatformService> service;

    public ProviderCommand(ProviderPlatformService service) {
        this.service = CliCommandSupport.constant(service, "service");
    }

    /** The service is built on its first call, that is after the arguments have been analysed. */
    ProviderCommand(Supplier<ProviderPlatformService> service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public int run(String[] arguments, Appendable output, Appendable error) throws IOException {
        return CliCommandSupport.run(arguments, output, error, USAGE, Options::parse, NAME, options -> {
            if (options.providerId() == null) {
                List<ProviderPlatformService.ProviderView> providers = service.get().listProviders();
                output.append(renderList(providers, options.format())).append('\n');
            } else {
                output.append(render(service.get().inspect(options.providerId()), options.format())).append('\n');
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
                "runtimeDiagnostics: " + CliCommandSupport.publicDiagnostics(provider.runtimeDiagnostics()));
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
        value.put("runtimeDiagnostics", CliCommandSupport.publicDiagnostics(provider.runtimeDiagnostics()));
        return value;
    }

    private record Options(String providerId, SymbolOutputFormat format) {
        private static Options parse(String[] arguments) {
            CliOptions options = OPTIONS.parse(arguments, 0);
            return new Options(options.operands().isEmpty() ? null : options.operands().getFirst(), options.format());
        }
    }
}
