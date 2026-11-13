#!/usr/bin/env python3
"""Tests of the optional pgAdmin service of the MINOS Docker runtime.

pgAdmin is a service of docker/compose-mcp.connected.yaml under the ``pgadmin`` profile, published on the loopback
interface only, and driven by three actions of docker/scripts/mcp-lifecycle.ps1 (``EnablePgAdmin``, ``StopPgAdmin``,
``RemovePgAdmin``). Three kinds of cases, none of which re-implements the script:

* the *compose files* of the repository, read as text (and resolved by ``docker compose config`` when Compose is
  installed): no pgAdmin and no published port without the profile, one loopback port with it, secrets from files;
* the *real* function definitions of the lifecycle script, extracted with the PowerShell parser;
* the *whole script*, run with a ``docker`` executable that records its arguments and the standard input of
  ``docker exec -i``: what is sent to PostgreSQL is read from that record, and no secret is expected in an argument.

Every PowerShell case runs under each PowerShell present (see test_mcp_lifecycle_query_image.py). The secret files
are restricted with Windows access control lists, as the managed PostgreSQL configurator does: the enabling action
targets Windows hosts and is refused elsewhere, which is asserted, not skipped.
"""
from __future__ import annotations

import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_mcp_lifecycle_query_image as lifecycle  # noqa: E402 - shared harness, imported as a module on purpose

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "docker" / "scripts" / "mcp-lifecycle.ps1"
CONNECTED = ROOT / "docker" / "compose-mcp.connected.yaml"
BASE = ROOT / "docker" / "compose-mcp.prod.yaml"
SERVICE = "minos-pgadmin"
SECRET_VARIABLES = ("MINOS_PGADMIN_PASSWORD_FILE", "MINOS_PGADMIN_PGPASS_FILE", "MINOS_PGADMIN_SERVERS_FILE")
POSTGRES_VOLUME = "minos-test-postgres-data"
QUERY_ENVIRONMENT = lifecycle.BASE_ENVIRONMENT + "MINOS_QUERY_IMAGE=minos-code-intelligence:test-query\n"
WINDOWS = os.name == "nt"
PROJECT = "minos-test"  # MINOS_COMPOSE_PROJECT of BASE_ENVIRONMENT
PGADMIN_VOLUME = f"{PROJECT}_minos-pgadmin-data"


def service_blocks(text: str) -> dict[str, list[str]]:
    """{service: its lines} of the ``services:`` section, without a YAML dependency."""
    blocks: dict[str, list[str]] = {}
    name, inside = None, False
    for line in text.splitlines():
        if line and not line[0].isspace() and not line.startswith("#"):
            inside, name = line.rstrip() == "services:", None
            continue
        if not inside:
            continue
        header = re.match(r"^  ([a-z][a-z0-9-]*):\s*$", line)
        if header:
            name = header.group(1)
            blocks[name] = []
        elif name is not None:
            blocks[name].append(line)
    return blocks


def scalar(block: list[str], key: str) -> str | None:
    """The inline value of a direct key of a service, None when the key is absent."""
    for line in block:
        match = re.match(rf"^    {re.escape(key)}:\s*(.*?)\s*$", line)
        if match:
            return match.group(1)
    return None


def items(block: list[str], key: str) -> list[str]:
    """The ``- item`` entries directly under a key of a service."""
    found: list[str] = []
    inside = False
    for line in block:
        if re.match(rf"^    {re.escape(key)}:\s*$", line):
            inside = True
        elif inside and line.startswith("      - "):
            found.append(line[len("      - "):].strip().strip('"'))
        elif inside and line.strip() and not line.startswith("      "):
            inside = False
    return found


class ComposeShapeTests(unittest.TestCase):
    """What the compose files declare, read from the files of the repository."""

    @classmethod
    def setUpClass(cls):
        cls.connected_text = CONNECTED.read_text(encoding="utf-8")
        cls.connected = service_blocks(cls.connected_text)
        cls.base = service_blocks(BASE.read_text(encoding="utf-8"))

    def test_the_base_runtime_has_no_pgadmin_and_publishes_no_port(self):
        self.assertNotIn(SERVICE, self.base)
        for name, block in self.base.items():
            self.assertIsNone(scalar(block, "ports"), f"{name} publishes a port in the base runtime")

    def test_pgadmin_exists_only_under_its_profile(self):
        self.assertIn(SERVICE, self.connected, "compose-mcp.connected.yaml has no minos-pgadmin service")
        self.assertEqual("[pgadmin]", scalar(self.connected[SERVICE], "profiles"))

    def test_without_the_profile_no_service_publishes_a_port(self):
        self.assertIn(SERVICE, self.connected)
        for name, block in self.connected.items():
            if name != SERVICE:
                self.assertIsNone(scalar(block, "ports"), f"{name} publishes a port")

    def test_pgadmin_publishes_one_port_on_the_loopback_interface(self):
        self.assertIn(SERVICE, self.connected)
        ports = items(self.connected[SERVICE], "ports")
        self.assertEqual(1, len(ports), ports)
        self.assertTrue(ports[0].startswith("127.0.0.1:"), ports[0])

    def test_postgresql_stays_unpublished_on_the_internal_network(self):
        self.assertIsNone(scalar(self.connected["minos-postgres"], "ports"))
        self.assertEqual("[minos-runtime]", scalar(self.connected["minos-postgres"], "networks"))
        self.assertRegex(self.connected_text, r"(?m)^  minos-runtime:\n    internal: true$")

    def test_pgadmin_reaches_postgresql_and_enters_by_a_dedicated_network(self):
        self.assertIn(SERVICE, self.connected)
        self.assertEqual("[minos-runtime, minos-inspect-ingress]", scalar(self.connected[SERVICE], "networks"))
        ingress = re.search(r"(?m)^  minos-inspect-ingress:\n((?:    .*\n)*)", self.connected_text)
        self.assertIsNotNone(ingress, "the dedicated ingress network is not declared")
        self.assertNotIn("internal: true", ingress.group(1), "an internal network cannot publish a port")

    def test_pgadmin_is_hardened_like_the_other_services(self):
        self.assertIn(SERVICE, self.connected)
        block = self.connected[SERVICE]
        self.assertEqual("[ALL]", scalar(block, "cap_drop"))
        self.assertIsNone(scalar(block, "cap_add"))
        self.assertEqual("[no-new-privileges:true]", scalar(block, "security_opt"))
        self.assertEqual("true", scalar(block, "read_only"))
        self.assertIn("UPGRADE_CHECK_ENABLED = False", "\n".join(block), "the pgAdmin update check must be disabled")

    def test_secrets_come_from_read_only_files_and_never_from_the_environment(self):
        self.assertIn(SERVICE, self.connected)
        text = "\n".join(self.connected[SERVICE])
        self.assertIn("PGADMIN_DEFAULT_PASSWORD_FILE:", text)
        self.assertNotRegex(text, r"PGADMIN_DEFAULT_PASSWORD:", "the pgAdmin password must not be an environment value")
        for variable in SECRET_VARIABLES:
            # A missing variable resolves to a path that cannot exist and that names the variable: the bind mount is
            # refused by Docker. `${VAR:?}` is not used: Compose interpolates the services of inactive profiles too,
            # so it would break every runtime that never enabled pgAdmin.
            source = f'source: "${{{variable}:-/{variable}-is-required}}"'
            self.assertIn(source, text, variable)
            mount = text.split(source, 1)[1].split("- type:", 1)[0]
            self.assertIn("read_only: true", mount, variable)
            self.assertRegex(mount, r"create_host_path: false", variable)
        self.assertNotRegex(self.connected_text, r"\$\{MINOS_PGADMIN_[A-Z_]+:\?",
                            "a required pgAdmin variable would break the runtimes without pgAdmin")

    def compose_config(self, directory: Path, profiles: tuple[str, ...], extra_environment: str = "") -> dict:
        (directory / "data").mkdir(exist_ok=True)
        secret = directory / "postgres.password"
        secret.write_text("fixture\n", encoding="ascii")
        environment = directory / ".env"
        environment.write_text(
            QUERY_ENVIRONMENT + f"MINOS_DATA_DIR={(directory / 'data').as_posix()}\n"
            + f"MINOS_POSTGRES_VOLUME={POSTGRES_VOLUME}\nMINOS_OLLAMA_VOLUME=minos-test-ollama-models\n"
            + f"MINOS_POSTGRES_PASSWORD_FILE={secret.as_posix()}\n" + extra_environment, encoding="ascii")
        arguments = ["docker", "compose", "--project-directory", str(directory), "--env-file", str(environment),
                     "-f", str(CONNECTED)]
        for profile in profiles:
            arguments += ["--profile", profile]
        scrubbed = {name: value for name, value in os.environ.items() if not name.startswith("MINOS_")}
        completed = subprocess.run(arguments + ["config", "--format", "json"], capture_output=True, check=False,
                                   env=scrubbed, **lifecycle.DECODING)
        self.assertEqual(0, completed.returncode, completed.stderr)
        return json.loads(completed.stdout)

    @unittest.skipUnless(lifecycle.compose_available(), "docker compose is not installed")
    def test_compose_resolves_no_pgadmin_and_no_port_without_the_profile(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = self.compose_config(Path(tmp), ("postgresql",))
        self.assertNotIn(SERVICE, config["services"])
        self.assertIn("minos-postgres", config["services"])
        for name, service in config["services"].items():
            self.assertFalse(service.get("ports"), f"{name} publishes a port")

    @unittest.skipUnless(lifecycle.compose_available(), "docker compose is not installed")
    def test_compose_resolves_one_loopback_port_with_the_profile(self):
        with tempfile.TemporaryDirectory() as tmp:
            config = self.compose_config(Path(tmp), ("postgresql", "pgadmin"), "MINOS_PGADMIN_PORT=5051\n")
        published = {name: service["ports"] for name, service in config["services"].items() if service.get("ports")}
        self.assertEqual([SERVICE], sorted(published), published)
        self.assertEqual(1, len(published[SERVICE]), published)
        self.assertEqual("127.0.0.1", published[SERVICE][0]["host_ip"])
        self.assertEqual("5051", str(published[SERVICE][0]["published"]))
        self.assertTrue(config["services"][SERVICE]["read_only"])
        self.assertEqual(["ALL"], config["services"][SERVICE]["cap_drop"])


SECRET_FUNCTIONS = ("Read-EnvironmentValue", "Assert-PgAdminSecretsConfigured")


class SecretGuardTests(lifecycle.PowerShellCase):
    """The fail-closed guard that precedes the start of pgAdmin: real function, runtime .env with or without secrets."""

    def guard(self, executable: str, lines: dict[str, str] | None, existing: tuple[str, ...]) -> tuple[dict, str]:
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            values = {variable: (directory / f"{variable.lower()}.txt") for variable in SECRET_VARIABLES}
            for variable in existing:
                values[variable].write_text("fixture\n", encoding="ascii")
            environment = directory / ".env"
            text = QUERY_ENVIRONMENT
            for variable, path in values.items():
                if lines is None or variable in lines:
                    value = path.as_posix() if lines is None else lines[variable].replace("%FILE%", path.as_posix())
                    text += f'{variable}="{value}"\n'
            environment.write_text(text, encoding="ascii")
            result = lifecycle.run_named_functions(executable, directory, SECRET_FUNCTIONS,
                                                   "Assert-PgAdminSecretsConfigured", environment=environment)
            return result, tmp

    def assert_refusal(self, result: dict, directory: str, variable: str, context):
        self.assertEqual("THREW", result["outcome"], (context, result))
        self.assertIn(variable, result["message"], (context, "the message must name the missing variable"))
        self.assertIn("EnablePgAdmin", result["message"], (context, "the message must name the repair action"))
        self.assertNotIn(directory, result["message"], (context, "no absolute path in a public message"))
        self.assertNotIn(os.path.basename(directory), result["message"], context)
        self.assertEqual(0, result["dockerCalls"], (context, "the refusal comes before any call to Docker"))

    def test_a_missing_password_file_variable_is_refused_by_name(self):
        for label, executable in self.runtimes:
            for case, lines in (("absent", {"MINOS_PGADMIN_PGPASS_FILE": "%FILE%", "MINOS_PGADMIN_SERVERS_FILE": "%FILE%"}),
                                ("empty", {"MINOS_PGADMIN_PASSWORD_FILE": "", "MINOS_PGADMIN_PGPASS_FILE": "%FILE%",
                                           "MINOS_PGADMIN_SERVERS_FILE": "%FILE%"})):
                result, directory = self.guard(executable, lines, SECRET_VARIABLES)
                self.assert_refusal(result, directory, "MINOS_PGADMIN_PASSWORD_FILE", (label, case))

    def test_a_variable_that_names_a_missing_file_is_refused_by_name(self):
        for label, executable in self.runtimes:
            for variable in SECRET_VARIABLES:
                existing = tuple(name for name in SECRET_VARIABLES if name != variable)
                result, directory = self.guard(executable, None, existing)
                self.assert_refusal(result, directory, variable, (label, variable))

    def test_three_existing_files_are_accepted(self):
        for label, executable in self.runtimes:
            result, _ = self.guard(executable, None, SECRET_VARIABLES)
            self.assertEqual("RETURNED", result["outcome"], (label, result))


FAKE_DOCKER = r'''
"""A docker that records its arguments (one JSON list per line) and the standard input of `docker exec -i`."""
import json
import os
import sys

arguments = sys.argv[1:]
joined = " ".join(arguments)
with open(os.environ["FAKE_DOCKER_LOG"], "a", encoding="utf-8") as log:
    log.write(json.dumps(arguments) + "\n")
if arguments[:1] == ["exec"] and "-i" in arguments:
    with open(os.environ["FAKE_DOCKER_STDIN"], "a", encoding="utf-8") as record:
        record.write("-- docker " + joined + "\n" + sys.stdin.read() + "\n")
if arguments[:2] == ["image", "inspect"]:
    print(open(os.environ["FAKE_DOCKER_INSPECT"], encoding="ascii").read())
if "list-project-mounts" in arguments:
    print(open(os.environ["FAKE_DOCKER_TABLE"], encoding="ascii").read())
running = os.environ.get("FAKE_DOCKER_RUNNING", "").split(",")
if "ps" in arguments and "-q" in arguments and arguments[-1] in running:
    print("fake-" + arguments[-1])
failing = os.environ.get("FAKE_DOCKER_FAIL", "")
if failing and failing in joined:
    sys.stderr.write("fake docker failure\n")
    sys.exit(1)
if arguments[:2] == ["volume", "inspect"] and arguments[-1] not in os.environ.get("FAKE_DOCKER_VOLUMES", "").split(","):
    sys.exit(1)
'''

WRAPPER = r"""
param([string] $Script, [string] $Action, [string] $Root)
$ErrorActionPreference = 'Stop'
# Only the message of a refusal is printed: the position PowerShell adds to an error names this temporary wrapper.
try { & $Script -Action $Action -InstallRoot (Join-Path $Root 'install') -DataRoot (Join-Path $Root 'data') }
catch { Write-Output "REFUSED: $($_.Exception.Message)"; exit 1 }
"""

MANAGED_POSTGRESQL = ("# MINOS runtime configuration managed by the Windows installer\n"
                      "minos.storage.backend=postgresql\nminos.semantic.provider=disabled\n"
                      "minos.postgres.managed=true\nminos.postgres.user=minos\nminos.postgres.schema=minos\n")


def set_folder_acl(folder: Path, inheritable: bool) -> None:
    """Gives a folder the access list of a real data folder, or one that no file can inherit from (Windows only).

    A file created in a folder with at least one inheritable entry only receives inherited entries, which the
    restriction of a secret file removes. In a folder with none (what ``tempfile`` creates on some hosts), Windows
    gives the file the default access list of the process as explicit entries (SYSTEM, administrators), which the
    restriction keeps: the secret file is then refused, as it must be.
    """
    if not WINDOWS:
        return
    icacls = str(Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "icacls.exe")
    # S-1-5-32-545: the local users group, readable like a data folder on a shared volume. S-1-3-4: owner rights.
    arguments = ["/grant", "*S-1-5-32-545:(OI)(CI)R"] if inheritable else ["/inheritance:r", "/grant:r", "*S-1-3-4:F"]
    completed = subprocess.run([icacls, str(folder), *arguments], capture_output=True, check=False)
    if completed.returncode != 0:
        raise AssertionError(f"the test could not set the folder access list (icacls exit {completed.returncode})")


def install_fake_docker(directory: Path) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    program = directory / "fake_docker.py"
    program.write_text(FAKE_DOCKER, encoding="utf-8")
    if WINDOWS:
        (directory / "docker.cmd").write_text(f'@"{sys.executable}" "{program}" %*\r\n', encoding="ascii")
    else:
        launcher = directory / "docker"
        launcher.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{program}" "$@"\n', encoding="ascii")
        launcher.chmod(launcher.stat().st_mode | stat.S_IEXEC)
    return directory


class Runtime:
    """An installed MINOS Docker runtime in a temporary folder, with a recording docker."""

    def __init__(self, directory: Path, compose: Path = CONNECTED, managed_postgresql: bool = True,
                 inheritable_secrets_acl: bool = True):
        self.root = directory
        self.runtime = directory / "install" / "runtime"
        self.runtime.mkdir(parents=True)
        self.data = directory / "data"
        (self.data / "config").mkdir(parents=True)
        (self.data / "secrets").mkdir()
        set_folder_acl(self.data / "secrets", inheritable_secrets_acl)
        (self.runtime / "compose-mcp.prod.yaml").write_text(compose.read_text(encoding="utf-8"), encoding="utf-8")
        environment = QUERY_ENVIRONMENT
        if managed_postgresql:
            (self.data / "config" / "minos.properties").write_text(MANAGED_POSTGRESQL, encoding="utf-8")
            (self.data / "secrets" / "postgres.password").write_text("fixture-postgres\n", encoding="ascii")
            environment += (f"MINOS_POSTGRES_VOLUME={POSTGRES_VOLUME}\nMINOS_OLLAMA_VOLUME=minos-test-ollama-models\n"
                            "MINOS_POSTGRES_USER=minos\nMINOS_POSTGRES_DATABASE=minos\n"
                            f'MINOS_POSTGRES_PASSWORD_FILE="{(self.data / "secrets" / "postgres.password").as_posix()}"\n')
        self.environment = self.runtime / ".env"
        self.environment.write_text(environment, encoding="ascii")
        (self.runtime / "installation.json").write_text('{"formatVersion":6,"image":"minos-code-intelligence:test"}',
                                                        encoding="utf-8")
        (self.runtime / "provider-inventory.json").write_text("{}", encoding="utf-8")
        (self.runtime / "provider-binary-sha256.txt").write_text("", encoding="ascii")
        self.log = directory / "docker-calls.log"
        self.stdin = directory / "docker-stdin.log"
        self.inspect = directory / "inspect.json"
        self.inspect.write_text(json.dumps([{"Config": {"Labels": {"io.minos.image.plane": "query"}}}]), encoding="ascii")
        self.table = directory / "table.json"
        self.table.write_text(lifecycle.EMPTY_TABLE, encoding="ascii")
        self.bin = install_fake_docker(directory / "bin")
        self.wrapper = directory / "call.ps1"
        self.wrapper.write_text(WRAPPER, encoding="utf-8-sig")
        self.password = self.data / "secrets" / "pgadmin.password"
        self.pgpass = self.data / "secrets" / "pgadmin.pgpass"
        self.servers = self.runtime / "pgadmin-servers.json"

    def run(self, executable: str, action: str, running: str = "minos-postgres", failing: str = "",
            volumes: str = "", ambient: dict[str, str] | None = None) -> tuple[int, str]:
        """Runs the action; the record of the docker calls and of their standard input starts empty."""
        for record in (self.log, self.stdin):
            if record.is_file():
                record.unlink()
        environment = {name: value for name, value in os.environ.items() if not name.startswith("MINOS_")}
        environment.update(FAKE_DOCKER_LOG=str(self.log), FAKE_DOCKER_STDIN=str(self.stdin),
                           FAKE_DOCKER_INSPECT=str(self.inspect), FAKE_DOCKER_TABLE=str(self.table),
                           FAKE_DOCKER_RUNNING=running, FAKE_DOCKER_FAIL=failing, FAKE_DOCKER_VOLUMES=volumes,
                           PATH=str(self.bin) + os.pathsep + os.environ.get("PATH", ""))
        environment.update(ambient or {})
        completed = subprocess.run(
            [executable, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(self.wrapper),
             "-Script", str(SCRIPT), "-Action", action, "-Root", str(self.root)],
            capture_output=True, timeout=180, check=False, env=environment, stdin=subprocess.DEVNULL,
            **lifecycle.DECODING)
        return completed.returncode, lifecycle.flatten(completed.stdout + completed.stderr)

    def calls(self) -> list[str]:
        if not self.log.is_file():
            return []
        return [" ".join(json.loads(line)) for line in self.log.read_text(encoding="utf-8").splitlines() if line]

    def sent_sql(self) -> str:
        return self.stdin.read_text(encoding="utf-8") if self.stdin.is_file() else ""

    def environment_lines(self, prefix: str = "MINOS_PGADMIN_") -> list[str]:
        return [line for line in self.environment.read_text(encoding="ascii").splitlines() if line.startswith(prefix)]


def index_of(calls: list[str], fragment: str) -> int:
    return next((index for index, call in enumerate(calls) if fragment in call), -1)


def calls_missing_a_profile(calls: list[str]) -> list[str]:
    """The Compose calls that name pgAdmin without activating both profiles.

    pgAdmin depends on minos-postgres: Compose refuses a project where an active service depends on a service of
    an inactive profile, even to stop or remove it (observed with Docker Compose 5.7.0).
    """
    return [call for call in calls if call.startswith("compose ") and call.endswith(SERVICE)
            and not ("--profile postgresql" in call and "--profile pgadmin" in call)]


class EnableRefusalTests(lifecycle.PowerShellCase):
    """EnablePgAdmin refuses before pgAdmin is started; nothing is written."""

    def assert_nothing_started(self, runtime: Runtime, context):
        self.assertFalse(any(" up " in f" {call} " and SERVICE in call for call in runtime.calls()),
                         (context, runtime.calls()))
        self.assertFalse(runtime.password.exists(), (context, "a secret file was written by a refused action"))
        self.assertEqual([], runtime.environment_lines(), context)

    def test_a_runtime_without_the_pgadmin_service_is_refused(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = Runtime(Path(tmp), compose=BASE, managed_postgresql=False)
                code, output = runtime.run(executable, "EnablePgAdmin")
                self.assertNotEqual(0, code, (label, output))
                self.assertIn("managed PostgreSQL", output, label)
                self.assertNotIn(tmp, output, (label, "no absolute path in a public message"))
                self.assert_nothing_started(runtime, label)

    @unittest.skipUnless(WINDOWS, "the secret files are restricted with Windows access control lists")
    def test_a_runtime_without_managed_postgresql_is_refused_by_naming_it(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = Runtime(Path(tmp), managed_postgresql=False)
                code, output = runtime.run(executable, "EnablePgAdmin")
                self.assertNotEqual(0, code, (label, output))
                self.assertIn("PostgreSQL", output, label)
                self.assertNotIn(tmp, output, label)
                self.assert_nothing_started(runtime, label)

    @unittest.skipUnless(WINDOWS, "the secret files are restricted with Windows access control lists")
    def test_a_stopped_managed_postgresql_is_refused(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = Runtime(Path(tmp))
                code, output = runtime.run(executable, "EnablePgAdmin", running="")
                self.assertNotEqual(0, code, (label, output))
                self.assertIn("PostgreSQL", output, label)
                self.assert_nothing_started(runtime, label)

    @unittest.skipIf(WINDOWS, "the refusal of a host that is not Windows")
    def test_a_host_that_is_not_windows_is_refused(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = Runtime(Path(tmp))
                code, output = runtime.run(executable, "EnablePgAdmin")
                self.assertNotEqual(0, code, (label, output))
                self.assertIn("Windows", output, label)
                self.assert_nothing_started(runtime, label)


@unittest.skipUnless(WINDOWS, "the secret files are restricted with Windows access control lists")
class EnableTests(lifecycle.PowerShellCase):
    """EnablePgAdmin: role first, private files, then one loopback service; replayable."""

    def test_the_role_is_applied_before_the_service_starts_and_no_secret_is_an_argument(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = Runtime(Path(tmp))
                code, output = runtime.run(executable, "EnablePgAdmin")
                self.assertEqual(0, code, (label, output))
                calls = runtime.calls()
                role = index_of(calls, "exec -i fake-minos-postgres psql")
                up = index_of(calls, f"up -d --wait {SERVICE}")
                self.assertGreaterEqual(role, 0, (label, calls))
                self.assertGreater(up, role, (label, "pgAdmin must start after the role is applied", calls))
                self.assertIn("--profile pgadmin", calls[up], label)
                self.assertIn("--profile postgresql", calls[up], label)
                self.assertIn("--single-transaction", calls[role], (label, "the role script runs in one transaction"))
                self.assertIn("ON_ERROR_STOP=1", calls[role], label)

                sql = runtime.sent_sql()
                self.assertIn("CREATE ROLE minos_inspect", sql, label)
                self.assertRegex(sql, r"ALTER ROLE minos_inspect WITH LOGIN PASSWORD '", label)
                self.assertIn('SET search_path TO "minos"', sql, label)

                ui_password = runtime.password.read_text(encoding="utf-8").strip()
                host, port, database, user, role_password = runtime.pgpass.read_text(encoding="utf-8").strip().split(":")
                self.assertEqual(("minos-postgres", "5432", "*", "minos_inspect"), (host, port, database, user), label)
                self.assertGreaterEqual(len(ui_password), 32, label)
                self.assertGreaterEqual(len(role_password), 32, label)
                self.assertNotEqual(ui_password, role_password, (label, "the two identities have two passwords"))
                self.assertIn(role_password, sql, (label, "the role password travels on standard input"))
                recorded = "\n".join(calls) + runtime.environment.read_text(encoding="ascii") + output
                for secret in (ui_password, role_password):
                    self.assertNotIn(secret, recorded, (label, "a secret reached an argument, the .env or the console"))

                servers = json.loads(runtime.servers.read_text(encoding="utf-8"))
                server = servers["Servers"]["1"]
                self.assertEqual(("minos-postgres", "minos_inspect", "minos"),
                                 (server["Host"], server["Username"], server["MaintenanceDB"]), label)
                self.assertNotIn(role_password, runtime.servers.read_text(encoding="utf-8"), label)
                self.assertNotIn("Password", server, label)

                lines = runtime.environment_lines()
                for variable in SECRET_VARIABLES:
                    self.assertEqual(1, len([line for line in lines if line.startswith(variable + "=")]), (label, lines))
                self.assertNotIn(tmp, output, (label, "no absolute path on the console"))

    def test_enabling_twice_changes_nothing(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = Runtime(Path(tmp))
                self.assertEqual(0, runtime.run(executable, "EnablePgAdmin")[0], label)
                first = (runtime.password.read_bytes(), runtime.pgpass.read_bytes(), runtime.servers.read_bytes(),
                         runtime.environment.read_bytes())
                code, output = runtime.run(executable, "EnablePgAdmin")
                self.assertEqual(0, code, (label, output))
                second = (runtime.password.read_bytes(), runtime.pgpass.read_bytes(), runtime.servers.read_bytes(),
                          runtime.environment.read_bytes())
                self.assertEqual(first, second, (label, "a replayed EnablePgAdmin must not duplicate or regenerate"))
                self.assertGreaterEqual(index_of(runtime.calls(), f"up -d --wait {SERVICE}"), 0, label)

    def test_a_failed_role_script_does_not_start_the_service(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = Runtime(Path(tmp))
                code, output = runtime.run(executable, "EnablePgAdmin", failing="exec -i")
                self.assertNotEqual(0, code, (label, output))
                self.assertEqual(-1, index_of(runtime.calls(), f"up -d --wait {SERVICE}"), (label, runtime.calls()))
                self.assertNotIn(tmp, output, label)

    def test_a_new_password_never_meets_the_volume_of_the_previous_one(self):
        # pgAdmin reads its account password only when it creates its configuration: a regenerated password with
        # the old volume would leave the old password in force.
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = Runtime(Path(tmp))
                self.assertEqual(0, runtime.run(executable, "EnablePgAdmin", volumes=PGADMIN_VOLUME)[0], label)
                first = runtime.password.read_bytes()
                code, output = runtime.run(executable, "EnablePgAdmin", volumes=PGADMIN_VOLUME)
                self.assertEqual(0, code, (label, output))
                self.assertEqual(-1, index_of(runtime.calls(), "volume rm"), (label, "nothing changed: the volume stays"))
                self.assertEqual(-1, index_of(runtime.calls(), "rm --stop"), label)

                runtime.password.unlink()
                code, output = runtime.run(executable, "EnablePgAdmin", volumes=PGADMIN_VOLUME)
                self.assertEqual(0, code, (label, output))
                calls = runtime.calls()
                removal = index_of(calls, f"volume rm {PGADMIN_VOLUME}")
                self.assertGreaterEqual(removal, 0, (label, calls))
                self.assertLess(removal, index_of(calls, f"up -d --wait {SERVICE}"), (label, calls))
                self.assertNotEqual(first, runtime.password.read_bytes(), label)
                for call in calls:
                    self.assertNotIn(POSTGRES_VOLUME, call, (label, call))

    def test_an_ambient_pgadmin_variable_is_refused_by_name(self):
        # For Compose the process environment wins over the runtime .env: an ambient image or file would replace
        # the validated one.
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = Runtime(Path(tmp))
                code, output = runtime.run(executable, "EnablePgAdmin",
                                           ambient={"MINOS_PGADMIN_IMAGE": "example.invalid/pgadmin:latest"})
                self.assertNotEqual(0, code, (label, output))
                self.assertIn("MINOS_PGADMIN_IMAGE", output, label)
                self.assertFalse(runtime.password.exists(), label)
                self.assertEqual(-1, index_of(runtime.calls(), " up "), (label, runtime.calls()))

    def test_a_secret_file_that_cannot_be_made_private_is_refused(self):
        # A secrets folder with no inheritable entry: the new file keeps entries for other accounts after the
        # restriction. The verification refuses it; nothing is started and no secret is left on disk.
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = Runtime(Path(tmp), inheritable_secrets_acl=False)
                code, output = runtime.run(executable, "EnablePgAdmin")
                self.assertNotEqual(0, code, (label, output))
                self.assertIn("secrets/pgadmin.password", output, label)
                self.assertIn("not restricted to the current user", output, (label, "the cause must be named"))
                self.assertNotIn(tmp, output, (label, "no absolute path in a public message"))
                self.assertFalse(runtime.password.exists(), (label, "a refused secret file must not stay on disk"))
                self.assertEqual(-1, index_of(runtime.calls(), f"up -d --wait {SERVICE}"), (label, runtime.calls()))
                self.assertEqual(-1, index_of(runtime.calls(), "exec -i"), (label, "the role must not be created"))

    def test_stop_keeps_the_configuration(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = Runtime(Path(tmp))
                self.assertEqual(0, runtime.run(executable, "EnablePgAdmin")[0], label)
                code, output = runtime.run(executable, "StopPgAdmin")
                self.assertEqual(0, code, (label, output))
                calls = runtime.calls()
                self.assertEqual(1, len([call for call in calls if f"stop --timeout 10 {SERVICE}" in call]), (label, calls))
                self.assertEqual([], calls_missing_a_profile(calls), label)
                self.assertFalse(any(" rm " in f" {call} " or " down " in f" {call} " for call in calls), (label, calls))
                self.assertTrue(runtime.password.is_file() and runtime.pgpass.is_file() and runtime.servers.is_file(), label)
                self.assertEqual(len(SECRET_VARIABLES), len([line for line in runtime.environment_lines()
                                                              if line.split("=")[0] in SECRET_VARIABLES]), label)


DESTRUCTIVE_SQL = re.compile(r"\b(DELETE|TRUNCATE|UPDATE|INSERT|DROP\s+(TABLE|SCHEMA|DATABASE)|ALTER\s+TABLE)\b", re.IGNORECASE)


class RemoveTests(lifecycle.PowerShellCase):
    """RemovePgAdmin: container, pgAdmin volume, private files and role; nothing of MINOS."""

    def enabled_runtime(self, directory: Path) -> Runtime:
        """A runtime in the state EnablePgAdmin leaves, written here so that the removal is also tested off Windows."""
        runtime = Runtime(directory)
        runtime.password.write_text("fixture-ui-password\n", encoding="ascii")
        runtime.pgpass.write_text("minos-postgres:5432:*:minos_inspect:fixture-role-password\n", encoding="ascii")
        runtime.servers.write_text('{"Servers":{}}', encoding="ascii")
        with runtime.environment.open("a", encoding="ascii") as environment:
            environment.write("MINOS_PGADMIN_PORT=5050\nMINOS_PGADMIN_MEM_LIMIT=2g\n")
            for variable, path in zip(SECRET_VARIABLES, (runtime.password, runtime.pgpass, runtime.servers)):
                environment.write(f'{variable}="{path.as_posix()}"\n')
        return runtime

    def test_removal_takes_pgadmin_and_the_role_and_nothing_else(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = self.enabled_runtime(Path(tmp))
                postgres_secret = (runtime.data / "secrets" / "postgres.password").read_bytes()
                code, output = runtime.run(executable, "RemovePgAdmin", volumes=PGADMIN_VOLUME)
                self.assertEqual(0, code, (label, output))
                calls = runtime.calls()

                stop = index_of(calls, f"stop --timeout 10 {SERVICE}")
                role = index_of(calls, "exec -i fake-minos-postgres psql")
                removal = index_of(calls, f"rm --stop --force --volumes {SERVICE}")
                volume = index_of(calls, f"volume rm {PGADMIN_VOLUME}")
                self.assertTrue(0 <= stop < role < removal < volume, (label, calls))
                self.assertEqual([], calls_missing_a_profile(calls), label)
                sql = runtime.sent_sql()
                self.assertIn("DROP ROLE minos_inspect", sql, label)
                statements = "\n".join(line for line in sql.splitlines() if not line.startswith("-- docker "))
                self.assertIsNone(DESTRUCTIVE_SQL.search(statements), (label, "the removal must touch no table", sql))

                # The PostgreSQL volume and the rest of the runtime are named by no command of the removal.
                for call in calls:
                    self.assertNotIn(POSTGRES_VOLUME, call, (label, call))
                    self.assertNotIn(" down", f" {call}", (label, call))
                    self.assertFalse(call.startswith("volume rm") and PGADMIN_VOLUME not in call, (label, call))
                    self.assertFalse(" rm " in f" {call} " and "minos-postgres" in call, (label, call))

                for gone in (runtime.password, runtime.pgpass, runtime.servers):
                    self.assertFalse(gone.exists(), (label, gone.name))
                self.assertEqual(postgres_secret, (runtime.data / "secrets" / "postgres.password").read_bytes(), label)
                lines = runtime.environment.read_text(encoding="utf-8").splitlines()
                self.assertFalse([line for line in lines if line.split("=")[0] in SECRET_VARIABLES], (label, lines))
                self.assertIn("MINOS_PGADMIN_MEM_LIMIT=2g", lines, (label, "a ceiling override is not a pgAdmin file"))
                self.assertTrue(any(line.startswith("MINOS_POSTGRES_PASSWORD_FILE=") for line in lines), label)
                self.assertNotIn(tmp, output, (label, "no absolute path on the console"))

    def test_removal_is_refused_before_anything_when_postgresql_is_stopped(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = self.enabled_runtime(Path(tmp))
                code, output = runtime.run(executable, "RemovePgAdmin", running="", volumes=PGADMIN_VOLUME)
                self.assertNotEqual(0, code, (label, output))
                self.assertIn("minos_inspect", output, label)
                calls = runtime.calls()
                self.assertFalse(any(" rm" in f" {call}" or " stop " in f" {call} " for call in calls), (label, calls))
                self.assertTrue(runtime.password.is_file() and runtime.pgpass.is_file(), label)

    def test_a_role_that_cannot_be_removed_keeps_the_volume_and_the_files(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = self.enabled_runtime(Path(tmp))
                code, output = runtime.run(executable, "RemovePgAdmin", failing="exec -i", volumes=PGADMIN_VOLUME)
                self.assertNotEqual(0, code, (label, output))
                self.assertEqual(-1, index_of(runtime.calls(), "volume rm"), (label, runtime.calls()))
                self.assertTrue(runtime.password.is_file() and runtime.pgpass.is_file(), label)

    def test_removing_twice_is_not_an_error(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = self.enabled_runtime(Path(tmp))
                self.assertEqual(0, runtime.run(executable, "RemovePgAdmin", volumes=PGADMIN_VOLUME)[0], label)
                code, output = runtime.run(executable, "RemovePgAdmin")
                self.assertEqual(0, code, (label, output))
                self.assertEqual(-1, index_of(runtime.calls(), "volume rm"), (label, "the volume is already gone"))


class UninstallTests(lifecycle.PowerShellCase):
    """Uninstall leaves no pgAdmin secret behind: the named volume and the two private files go with the container."""

    def test_uninstall_removes_the_pgadmin_volume_and_its_private_files(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                runtime = RemoveTests.enabled_runtime(self, Path(tmp))
                code, output = runtime.run(executable, "Uninstall", volumes=PGADMIN_VOLUME)
                self.assertEqual(0, code, (label, output))
                calls = runtime.calls()
                volume = index_of(calls, f"volume rm {PGADMIN_VOLUME}")
                down = index_of(calls, "down --timeout 10 --remove-orphans --volumes")
                self.assertTrue(0 <= volume < down, (label, calls))
                self.assertEqual([], calls_missing_a_profile(calls), label)
                self.assertFalse(runtime.password.exists() or runtime.pgpass.exists(), label)
                self.assertTrue((runtime.data / "secrets" / "postgres.password").is_file(), (label, "MINOS data is kept"))
                for call in calls:
                    self.assertFalse(call.startswith("volume rm") and PGADMIN_VOLUME not in call, (label, call))


class InstallCarryOverTests(lifecycle.PowerShellCase):
    """An Install regenerates the runtime .env: the pgAdmin settings are carried over, nothing else."""

    def test_only_the_pgadmin_settings_are_carried_over(self):
        for label, executable in self.runtimes:
            with tempfile.TemporaryDirectory() as tmp:
                directory = Path(tmp)
                environment = directory / ".env"
                environment.write_text(QUERY_ENVIRONMENT + "MINOS_PGADMIN_PORT=5051\nMINOS_PGADMIN_EMAIL=a@b.example\n"
                                       'MINOS_PGADMIN_PASSWORD_FILE="D:/x/pgadmin.password"\nMINOS_PGADMIN_MEM_LIMIT=2g\n'
                                       "MINOS_PGADMIN_OTHER=1\n# MINOS_PGADMIN_PGPASS_FILE=commented\n", encoding="ascii")
                result = lifecycle.run_named_functions(executable, directory, ("Get-PreservedPgAdminSettings",),
                                                       f"(Get-PreservedPgAdminSettings -Path '{environment}') | ForEach-Object {{ \"$_\" }}")
                self.assertEqual(["MINOS_PGADMIN_PORT=5051", "MINOS_PGADMIN_EMAIL=a@b.example",
                                  'MINOS_PGADMIN_PASSWORD_FILE="D:/x/pgadmin.password"'], result["value"], label)
                absent = lifecycle.run_named_functions(executable, directory, ("Get-PreservedPgAdminSettings",),
                                                       f"(Get-PreservedPgAdminSettings -Path '{directory / 'none.env'}') | ForEach-Object {{ \"$_\" }}")
                self.assertEqual([], absent["value"], label)


if __name__ == "__main__":
    unittest.main()
