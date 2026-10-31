package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the per-step progress rows of the Windows installer's post-install configuration: which rows exist,
 * how the progress file is read while {@code Exec} waits, how the final state follows the exit code, and that the
 * shipped scripts only emit the closed protocol vocabulary. The emitters themselves are exercised by
 * {@code scripts/install/test_setup_progress.py}.
 */
class InstallerSetupProgressContractTest {

    private static final String[] EMITTER_SCRIPTS = {
            "scripts/install/switch-mcp-backend.ps1",
            "scripts/install/configure-mcp-clients-setup.ps1",
            "docker/scripts/configure-docker-mcp.ps1",
            "docker/scripts/configure-m30-docker-services.ps1",
    };

    private static final Pattern ROW = Pattern.compile("AddSetupStepRow\\('([a-z0-9-]+)', '([a-z]+)'");
    private static final Pattern EMIT = Pattern.compile("Write-SetupProgress -Step (\\S+)");
    private static final Pattern LITERAL_STEP = Pattern.compile("'([a-z][a-z0-9-]*)'");

    @Test
    void rowsAreCreatedOnlyForSelectedStepsAndNeverInSmokeMode() throws Exception {
        String installer = installer();
        String build = body(installer, "procedure BuildSetupStepRows;");

        assertTrue(build.startsWith("begin\n  if IsSmokeBuild() then exit;"),
                "rows must not be created in smoke mode");
        assertOrdered(build,
                "AddSetupStepRow('runtime', 'runtime'",
                "if McpBackendSelected() then",
                "if DockerMcpSelected() then",
                "AddSetupStepRow('docker-image', 'backend'",
                "if PostgreSqlSelected() then AddSetupStepRow('postgres', 'backend'",
                "if OllamaSelected() then AddSetupStepRow('ollama', 'backend'",
                "if OllamaSelected() and OllamaProvisionCheck.Checked then AddSetupStepRow('ollama-model', 'backend'",
                "AddSetupStepRow('handshake', 'backend'",
                "if McpBackendSelected() and McpClientSelected() then",
                "if McpCopilotJetBrains.Enabled and McpCopilotJetBrains.Checked then AddSetupStepRow('client-copilot-jetbrains', 'clients'",
                "if McpCopilotCli.Enabled and McpCopilotCli.Checked then AddSetupStepRow('client-copilot-cli', 'clients'",
                "if McpClaudeCode.Enabled and McpClaudeCode.Checked then AddSetupStepRow('client-claude-code', 'clients'",
                "if McpClaudeDesktop.Enabled and McpClaudeDesktop.Checked then AddSetupStepRow('client-claude-desktop', 'clients'",
                "AddSetupStepRow('client-codex', 'clients'",
                "LayoutSetupStepRows;");
        assertOrdered(body(installer, "procedure CurStepChanged(CurStep: TSetupStep);"),
                "BuildSetupStepRows;",
                "ConfigureRuntimeSettings()",
                "ConfigureSelectedMcpBackend()",
                "MarkSetupStepsNotRun;");
    }

    @Test
    void progressIsReadByATimerWithoutRedirectingTheScripts() throws Exception {
        String installer = installer();

        assertTrue(installer.contains("external 'SetTimer@user32.dll stdcall'"));
        assertTrue(installer.contains("external 'KillTimer@user32.dll stdcall'"));
        assertTrue(installer.contains("SetTimer(0, 0, 250, CreateCallback(@SetupProgressTick))"));
        assertFalse(installer.contains("ExecAndLogOutput"),
                "ExecAndLogOutput pipes stdout and stderr of docker build through the installer");

        String backend = body(installer, "function ConfigureSelectedMcpBackend(): Boolean;");
        String clients = body(installer, "procedure ConfigureMcpClients;");
        for (String step : new String[]{backend, clients}) {
            assertOrdered(step,
                    "' -ProgressPath \"' + SetupProgressPath + '\"'",
                    "StartSetupProgress;",
                    "Exec(PowerShell, Parameters, '', SW_HIDE, ewWaitUntilTerminated, ResultCode)");
        }

        // Only complete lines that match the grammar exactly and name an existing row are applied.
        String parse = body(installer,
                "function ParseSetupProgressLine(Line: String; var Id, State: String; var Percent: Integer): Boolean;");
        assertTrue(parse.contains("'MINOS-PROGRESS step='"));
        assertTrue(parse.contains("' state='"));
        assertTrue(parse.contains("' percent='"));
        assertTrue(parse.contains("(State = 'running') or (State = 'done') or (State = 'skipped')"));
        assertTrue(parse.contains("SetupStepRowIndex(Id) >= 0"));
        String apply = body(installer, "procedure ApplySetupProgressFile;");
        assertTrue(apply.contains("Text[Length(Text)] <> #10"), "an unterminated last line must not be applied");
    }

    @Test
    void finalStateFollowsTheExitCode() throws Exception {
        String installer = installer();

        String finish = body(installer, "procedure FinishSetupStepGroup(Group: String; Succeeded: Boolean);");
        assertTrue(finish.contains("if Succeeded then"));
        assertTrue(finish.contains("'unreported'"), "a row never reported must not turn green");
        assertTrue(finish.contains("'failed'"));
        assertTrue(finish.contains("if LastRow >= 0 then SetSetupStepState(SetupStepRows[LastRow].Id, 'failed', -1);"),
                "a failure after every reported step is done must still show red");

        assertOrdered(body(installer, "function ConfigureRuntimeSettings(): Boolean;"),
                "SetSetupStepState('runtime', 'running', -1);",
                "FinishSetupStepGroup('runtime', False);",
                "MsgBox(",
                "FinishSetupStepGroup('runtime', True);");
        assertOrdered(body(installer, "function ConfigureSelectedMcpBackend(): Boolean;"),
                "StopSetupProgress('backend', False);",
                "MsgBox(",
                "StopSetupProgress('backend', True);");
        assertOrdered(body(installer, "procedure ConfigureMcpClients;"),
                "StopSetupProgress('clients', False);",
                "MsgBox(",
                "StopSetupProgress('clients', True);");
        // The status label stays the textual summary of the current step.
        assertTrue(installer.contains("WizardForm.StatusLabel.Caption :="));
    }

    @Test
    void scriptsEmitOnlyStepsTheInstallerDisplays() throws Exception {
        Path root = repoRoot();
        Set<String> rows = new LinkedHashSet<>();
        Matcher row = ROW.matcher(installer());
        while (row.find()) {
            rows.add(row.group(1));
        }

        Set<String> emitted = new LinkedHashSet<>();
        for (String script : EMITTER_SCRIPTS) {
            String text = text(root.resolve(script));
            assertTrue(text.contains("[string] $ProgressPath = ''"), script + " lacks -ProgressPath");
            assertEquals(1, count(text, "function Write-SetupProgress("), script);
            // No free-text parameter: a line can only carry a step id, a state and a number.
            assertTrue(text.contains("function Write-SetupProgress([string] $Step, [string] $State, [int] $Percent = -1) {"),
                    script + " has a different emitter signature");
            Matcher call = EMIT.matcher(text);
            while (call.find()) {
                String argument = call.group(1);
                Matcher literal = LITERAL_STEP.matcher(argument);
                if (literal.matches()) {
                    emitted.add(literal.group(1));
                } else {
                    assertEquals("$ClientSteps[$Client]", argument, script + " emits a computed step id");
                }
            }
        }
        Matcher clientStep = Pattern.compile("'[a-z-]+' = '(client-[a-z-]+)'")
                .matcher(text(root.resolve("scripts/install/configure-mcp-clients-setup.ps1")));
        while (clientStep.find()) {
            emitted.add(clientStep.group(1));
        }
        emitted.remove("$Step");

        assertFalse(emitted.isEmpty());
        for (String id : emitted) {
            assertTrue(rows.contains(id), "script step without installer row: " + id);
        }
        for (String id : rows) {
            assertTrue(id.equals("runtime") || emitted.contains(id), "installer row no script reports: " + id);
        }

        assertTrue(text(root.resolve("scripts/install/switch-mcp-backend.ps1"))
                .contains("$DockerParameters['ProgressPath'] = $ProgressPath"));
        assertTrue(text(root.resolve("docker/scripts/configure-docker-mcp.ps1"))
                .contains("$ServiceParameters['ProgressPath'] = $ProgressPath"));
    }

    private static String installer() throws IOException {
        return text(repoRoot().resolve("packaging/windows/minos-installer.iss.template"));
    }

    /** Text of a Pascal routine from the line after its header to its closing {@code end;} at column 0. */
    private static String body(String text, String header) {
        int start = text.indexOf(header + "\n");
        assertTrue(start >= 0, "missing routine: " + header);
        int from = text.indexOf("\nbegin\n", start);
        int localVar = text.indexOf("\nvar\n", start);
        int bodyStart = localVar >= 0 && localVar < from ? text.indexOf("\nbegin\n", localVar) : from;
        int end = text.indexOf("\nend;\n", bodyStart);
        assertTrue(bodyStart >= 0 && end > bodyStart, "unterminated routine: " + header);
        return text.substring(bodyStart + 1, end + 5);
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    private static void assertOrdered(String text, String... tokens) {
        int cursor = -1;
        for (String token : tokens) {
            int index = text.indexOf(token, cursor + 1);
            assertTrue(index > cursor, "missing/out-of-order token: " + token);
            cursor = index;
        }
    }

    private static String text(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Path repoRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isRegularFile(candidate.resolve("docker/compose-mcp.prod.yaml"))) {
                return candidate;
            }
        }
        throw new IOException("repository root not found from " + Path.of("").toAbsolutePath());
    }
}
