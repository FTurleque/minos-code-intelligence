#!/usr/bin/env python3
"""Fail when a GitHub workflow uses a mutable third-party action, an unpinned Inno Setup,
an ambiguously-resolved Inno Setup compiler, a floating runner image, or interpolates a GitHub
Actions expression directly inside a shell run: block instead of routing it through env:.

A floating runner image (`ubuntu-latest`, any `runs-on` label ending in `-latest`) changes under
the workflow without a commit: the job then runs on a system no pull request qualified. Every
`runs-on` names a versioned image (`ubuntu-24.04`, `windows-2022`); a GitHub-hosted floating label
is refused anywhere in a workflow, so a matrix cannot reintroduce it through `${{ matrix.os }}`.

Job hardening (AUD-DEP-12, AUD-DEP-13): every `actions/checkout` says `persist-credentials: false` (this repository
is public: its `git fetch` calls are anonymous; a private repository would authenticate them explicitly); every job
that runs on a runner declares `timeout-minutes`; a job that logs in to Docker Hub logs out in its first `run:` step
after the login, unless DOCKER_LOGIN_KEPT names it with a reason, and a stale entry of that list is refused. A job that
pulls the Testcontainers images before logging out (`check-image-pins.py --testcontainers-images`) declares, in its
`env:`, the pull policy that refuses any other image and disables Ryuk (TESTCONTAINERS_GUARD_ENV): without them a test
needing a new image would pull it anonymously, under Docker Hub's quota.

Release provenance (AUD-DEP-06): a job that publishes release assets (`publish-windows-release.ps1` outside
`-ValidateOnly`, `gh release create`, `gh release upload`) runs `actions/attest-build-provenance` before it and holds
`id-token: write` and `attestations: write` at job level; no workflow grants either at its top level. Every script
path named by docs/developer/supply-chain.md is a file of the repository.

`--root` points the gate at another tree (default: this repository).
Self-test: scripts/quality/test_check_workflow_pins.py.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - après l'ajout du dossier du script au chemin

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ".github/workflows"
USES = re.compile(r"\buses:\s*[\"']?(?P<target>[^@\s\"']+)@(?P<ref>[^\s\"'#]+)")
SHA = re.compile(r"[0-9a-fA-F]{40}")
INNO_VERSION = "6.7.1"

# `run:` as a key of a step, including the first key of a step written `- run: ...`.
RUN_KEY = re.compile(r"^(?P<indent>[ \t]*)(?:-[ \t]+)?run:[ \t]*(?P<rest>.*)$")
BLOCK_SCALAR = re.compile(r"^[|>][+-]?\d*$")
GHA_EXPRESSION = re.compile(r"\$\{\{\s*(?P<inner>.*?)\s*\}\}")

# GitHub-runtime-controlled, closed-enum values that cannot carry attacker-influenced text no
# matter what a workflow input, PR title/branch, tag, or external event contains. These are exempt
# from the run: interpolation check; everything else must cross into the shell through env:.
SAFE_RUN_EXPRESSIONS = re.compile(r"^steps\.[A-Za-z0-9_-]+\.(outcome|conclusion)$")

ISCC_AMBIGUOUS_SEARCH_MARKERS = ("Get-Command ISCC.exe",)
ISCC_ENV_EXPORT = re.compile(r"ISCC_PATH=.*>>\s*\$env:GITHUB_ENV")

RUNS_ON = re.compile(r"^(?P<indent>[ \t]*)(?:-[ \t]+)?runs-on:[ \t]*(?P<value>.*)$")
# A runs-on label that floats with its provider: anything ending in `-latest`.
FLOATING_RUNNER = re.compile(r"(?<![\w.-])(?P<label>[\w.-]+-latest)(?![\w.-])")
# GitHub-hosted floating labels: only ever runner images, refused on any line (matrix, env, inputs).
HOSTED_FLOATING_RUNNER = re.compile(r"(?<![\w.-])(?P<label>(?:ubuntu|windows|macos)-latest)(?![\w.-])")


def relative(root: Path, path: Path) -> str:
    """Path of a file of the tree, with `/` separators on every system."""
    return path.relative_to(root).as_posix()


def _without_comment(line: str) -> str:
    """The YAML content of a line: a whole-line comment is empty, a trailing ` #` comment is dropped."""
    if line.strip().startswith("#"):
        return ""
    return re.split(r"\s#", line, maxsplit=1)[0]


def find_floating_runner_violations(label: str, text: str) -> tuple[list[str], int]:
    """(failures, number of runs-on keys read): no job runs on a runner image that floats.

    A `runs-on` value is read inline (scalar or flow sequence) and on the lines nested under it (block
    sequence, `labels:` of a runner group). A GitHub-hosted floating label is also refused on any other
    line, where it can only be a runner chosen through an expression (`${{ matrix.os }}`).
    """
    failures: list[str] = []
    flagged: set[int] = set()
    runs_on = 0
    lines = text.splitlines()
    nested_indent: int | None = None
    for number, line in enumerate(lines, start=1):
        content = _without_comment(line)
        if not content.strip():
            continue
        indent = len(line) - len(line.lstrip(" \t"))
        if nested_indent is not None and indent <= nested_indent:
            nested_indent = None
        match = RUNS_ON.match(content)
        if match:
            runs_on += 1
            nested_indent = indent
            candidates = FLOATING_RUNNER.finditer(match.group("value"))
        elif nested_indent is not None:
            candidates = FLOATING_RUNNER.finditer(content)
        else:
            candidates = HOSTED_FLOATING_RUNNER.finditer(content)
        for candidate in candidates:
            if number in flagged:
                break
            flagged.add(number)
            failures.append(
                f"{label}:{number}: floating runner image '{candidate.group('label')}' -- pin a versioned "
                f"runner image (ubuntu-24.04, windows-2022): '-latest' changes without a commit."
            )
    return failures, runs_on


TOP_LEVEL_JOBS = re.compile(r"^jobs:\s*$")
MAPPING_KEY = re.compile(r"^(?P<indent>[ \t]*)(?:-[ \t]+)?(?P<key>[A-Za-z0-9_-]+):(?:\s|$)")
STEP_ITEM = re.compile(r"^(?P<indent>[ \t]*)-(?:[ \t]|$)")
PERSIST_CREDENTIALS_FALSE = re.compile(r"^\s*persist-credentials:\s*['\"]?false['\"]?\s*$")
CHECKOUT = "actions/checkout"
DOCKER_LOGIN = "docker/login-action"

# Jobs that keep their Docker Hub login while the repository's code runs (AUD-DEP-13, decision D4 of
# durcir-les-workflows-github): {(workflow file, job id): reason}. Every other job that logs in pulls what it needs,
# then runs `docker logout` in the first `run:` step after the login. An entry that no longer names a job logging
# in is refused, so the list can only shrink with the workflows.
DOCKER_LOGIN_KEPT = {
    ("docker-upgrade-qualification.yml", "real-docker-upgrade"):
        "Candidate A is built from another commit, whose Dockerfile may name other base-image digests.",
    ("release-promotion-gate.yml", "docker-upgrade-qualification"):
        "Candidate A is real main, whose Dockerfile may name other base-image digests than the candidate's.",
}


# A job that pulls the Testcontainers images, then logs out (AUD-DEP-13, option B of decision D4), lets the tests start
# those images only: the pull policy refuses any other by name, and Ryuk (an image outside the list) is not started.
TESTCONTAINERS_PREPULL = "--testcontainers-images"
TESTCONTAINERS_GUARD_ENV = {
    "TESTCONTAINERS_PULL_POLICY": "com.minos.storage.postgresql.PrePulledImagesOnlyPullPolicy",
    "TESTCONTAINERS_RYUK_DISABLED": "true",
}
TESTCONTAINERS_GUARD_CLASS = ("minos-storage-postgresql/src/test/java/com/minos/storage/postgresql/"
                              "PrePulledImagesOnlyPullPolicy.java")


def _content_indent(line: str) -> int:
    return len(line) - len(line.lstrip(" \t"))


def _jobs(text: str) -> list[tuple[str, int, list[tuple[int, str]]]]:
    """(job id, line number of its key, [(line number, line)] of its body) for each job of the top-level `jobs:`."""
    jobs: list[tuple[str, int, list[tuple[int, str]]]] = []
    in_jobs = False
    job_indent: int | None = None
    for number, line in enumerate(text.splitlines(), start=1):
        content = _without_comment(line)
        if not content.strip():
            continue
        indent = _content_indent(content)
        if indent == 0:
            in_jobs = bool(TOP_LEVEL_JOBS.match(content))
            continue
        if not in_jobs:
            continue
        if job_indent is None:
            job_indent = indent
        key = MAPPING_KEY.match(content)
        if indent == job_indent and key:
            jobs.append((key.group("key"), number, []))
        elif jobs and indent > job_indent:
            jobs[-1][2].append((number, content))
    return jobs


def _child_keys(body: list[tuple[int, str]]) -> dict[str, int]:
    """{key: line number} of the direct keys of a job body."""
    if not body:
        return {}
    indent = _content_indent(body[0][1])
    keys: dict[str, int] = {}
    for number, line in body:
        key = MAPPING_KEY.match(line)
        if key and _content_indent(line) == indent and not STEP_ITEM.match(line):
            keys.setdefault(key.group("key"), number)
    return keys


def _steps(body: list[tuple[int, str]]) -> list[tuple[int, list[str]]]:
    """(line number, lines) of each item of the `steps:` sequence of a job body."""
    steps: list[tuple[int, list[str]]] = []
    steps_indent: int | None = None
    item_indent: int | None = None
    for number, line in body:
        indent = _content_indent(line)
        if steps_indent is None:
            key = MAPPING_KEY.match(line)
            if key and key.group("key") == "steps" and not STEP_ITEM.match(line):
                steps_indent = indent
            continue
        item = STEP_ITEM.match(line)
        if indent < steps_indent or (indent == steps_indent and not item):
            break
        if item and (item_indent is None or indent == item_indent):
            item_indent = indent
            steps.append((number, [line]))
        elif steps:
            steps[-1][1].append(line)
    return steps


def _step_uses(step: list[str], action: str) -> bool:
    return any((match := USES.search(line)) is not None and match.group("target") == action for line in step)


def _step_runs(step: list[str]) -> bool:
    return any(RUN_KEY.match(line) for line in step)


def find_job_hardening_violations(label: str, text: str) -> tuple[list[str], set[str]]:
    """(failures, ids of the jobs kept logged in to Docker Hub) for the jobs of one workflow.

    * a checkout leaves the repository token in `.git/config` unless it says `persist-credentials: false`; every
      `git fetch` of the workflows is anonymous on this public repository, so none needs it;
    * a job that runs on a runner (`runs-on`) without `timeout-minutes` can hold it for GitHub's 360-minute default;
      a reusable-workflow call (`uses:` at job level) has no runner and cannot carry the key;
    * a job that logs in to Docker Hub runs `docker logout` in its first `run:` step after the login, unless it is
      named in DOCKER_LOGIN_KEPT with its reason;
    * a job that pulls the Testcontainers images declares TESTCONTAINERS_GUARD_ENV in its own `env:`.
    """
    workflow = label.rsplit("/", 1)[-1]
    failures: list[str] = []
    kept: set[str] = set()
    for job, job_line, body in _jobs(text):
        keys = _child_keys(body)
        if "runs-on" in keys and "timeout-minutes" not in keys:
            failures.append(
                f"{label}:{job_line}: job '{job}' runs on a runner without timeout-minutes -- a stuck step would "
                f"hold the runner for GitHub's 360-minute default."
            )
        logged_in = False
        for step_line, step in _steps(body):
            if _step_uses(step, CHECKOUT) and not any(PERSIST_CREDENTIALS_FALSE.match(line) for line in step):
                failures.append(
                    f"{label}:{step_line}: actions/checkout without 'persist-credentials: false' -- the repository "
                    f"token stays in .git/config for every later step of job '{job}'."
                )
            if _step_uses(step, DOCKER_LOGIN):
                logged_in = True
                if (workflow, job) in DOCKER_LOGIN_KEPT:
                    kept.add(job)
                continue
            if logged_in and _step_runs(step) and (workflow, job) not in DOCKER_LOGIN_KEPT:
                logged_in = False
                if not any("docker logout" in line for line in step):
                    failures.append(
                        f"{label}:{step_line}: job '{job}' runs code while logged in to Docker Hub -- pull the pinned "
                        f"images, then 'docker logout' in the first run: step after the login, or name the job in "
                        f"DOCKER_LOGIN_KEPT with its reason."
                    )
        if any(_step_runs(step) and any(TESTCONTAINERS_PREPULL in line for line in step) for _line, step in _steps(body)):
            env = _job_mapping(body, "env")
            for name, value in TESTCONTAINERS_GUARD_ENV.items():
                if env.get(name) != value:
                    failures.append(
                        f"{label}:{job_line}: job '{job}' pulls the Testcontainers images, then logs out, without "
                        f"'{name}: {value}' in its env -- a test needing another image would pull it anonymously."
                    )
    return failures, kept


def _job_mapping(body: list[tuple[int, str]], name: str) -> dict[str, str]:
    """{key: value} of the mapping `name:` written directly under a job (`env:`, ...), quotes removed."""
    if not body:
        return {}
    indent = _content_indent(body[0][1])
    values: dict[str, str] = {}
    inside = False
    for _number, line in body:
        current = _content_indent(line)
        key = MAPPING_KEY.match(line)
        if inside:
            if current <= indent:
                break
            if key:
                values[key.group("key")] = line.split(":", 1)[1].strip().strip("'\"")
            continue
        inside = bool(key) and current == indent and key.group("key") == name and not STEP_ITEM.match(line)
    return values


ATTESTATION = "actions/attest-build-provenance"
ATTESTATION_PERMISSIONS = ("id-token", "attestations")
# A step that puts files on a GitHub Release: the publication script outside its validation-only mode, or the CLI.
RELEASE_ASSET_PUBLICATION = re.compile(
    r"publish-windows-release\.ps1(?!.*-ValidateOnly)|\bgh\s+release\s+(?:create|upload)\b")
TOP_LEVEL_PERMISSIONS = re.compile(r"^permissions:\s*(?P<value>\S*)\s*$")
SUPPLY_CHAIN_DOC = "docs/developer/supply-chain.md"
DOCUMENTED_SCRIPT = re.compile(r"(?:\.[\\/])?(?P<path>scripts[\\/][A-Za-z0-9_.\\/-]+\.(?:ps1|psm1|py|sh|json))")


def _permissions(lines: list[tuple[int, str]], indent: int) -> dict[str, str]:
    """{scope: access} of the `permissions:` key written at `indent` among `lines` (`write-all` grants every scope)."""
    granted: dict[str, str] = {}
    inside = False
    for _number, line in lines:
        current = _content_indent(line)
        key = MAPPING_KEY.match(line)
        if inside:
            if current <= indent:
                break
            if key:
                granted[key.group("key")] = line.split(":", 1)[1].strip().strip("'\"")
            continue
        if key and current == indent and key.group("key") == "permissions" and not STEP_ITEM.match(line):
            value = line.split(":", 1)[1].strip().strip("'\"")
            if value == "write-all":
                return {scope: "write" for scope in ATTESTATION_PERMISSIONS}
            inside = value == ""
    return granted


def find_release_attestation_violations(label: str, text: str) -> list[str]:
    """A job that publishes release assets attests them first, with the two permissions at job level only."""
    failures: list[str] = []
    lines = [(number, _without_comment(line)) for number, line in enumerate(text.splitlines(), start=1)]
    top_level = _permissions([(n, line) for n, line in lines if line.strip()], 0)
    for scope in ATTESTATION_PERMISSIONS:
        if top_level.get(scope) == "write":
            failures.append(
                f"{label}: '{scope}: write' granted to the whole workflow -- grant it to the publishing job only."
            )
    for job, job_line, body in _jobs(text):
        steps = _steps(body)
        publishing = [line for line, step in steps
                      if _step_runs(step) and any(RELEASE_ASSET_PUBLICATION.search(content) for content in step)]
        if not publishing:
            continue
        if not any(_step_uses(step, ATTESTATION) for line, step in steps if line < publishing[0]):
            failures.append(
                f"{label}:{job_line}: job '{job}' publishes release assets without attesting their provenance "
                f"first ({ATTESTATION})."
            )
        granted = _permissions(body, _content_indent(body[0][1]) if body else 0)
        for scope in ATTESTATION_PERMISSIONS:
            if granted.get(scope) != "write":
                failures.append(
                    f"{label}:{job_line}: job '{job}' publishes release assets without '{scope}: write' in its own "
                    f"permissions; the provenance attestation needs it."
                )
    return failures


def find_missing_documented_scripts(root: Path) -> list[str]:
    """Script paths named by the supply-chain documentation that are not files of the repository."""
    document = root / SUPPLY_CHAIN_DOC
    if not document.is_file():
        return []
    failures: list[str] = []
    for number, line in enumerate(document.read_text(encoding="utf-8").splitlines(), start=1):
        for match in DOCUMENTED_SCRIPT.finditer(line):
            if not (root / match.group("path").replace("\\", "/")).is_file():
                failures.append(
                    f"{SUPPLY_CHAIN_DOC}:{number}: names {match.group(0)}, which is not a file of the repository."
                )
    return failures


def find_stale_docker_login_entries(kept: set[tuple[str, str]]) -> list[str]:
    """Entries of DOCKER_LOGIN_KEPT that no longer name a job logging in to Docker Hub (missing workflow included)."""
    return [
        f"{WORKFLOWS}/{workflow}: job '{job}' is listed in DOCKER_LOGIN_KEPT but no longer logs in to Docker Hub "
        f"-- remove the entry."
        for workflow, job in sorted(DOCKER_LOGIN_KEPT) if (workflow, job) not in kept
    ]


def _has_unsafe_interpolation(text: str) -> bool:
    matches = list(GHA_EXPRESSION.finditer(text))
    if not matches:
        return False
    return any(not SAFE_RUN_EXPRESSIONS.match(match.group("inner")) for match in matches)


def find_run_block_violations(label: str, text: str) -> list[str]:
    """Flag any `${{ ... }}` interpolated directly inside a run: shell block.

    Untrusted/external GitHub Actions values (workflow inputs, PR/ref/tag metadata, step
    outputs derived from them) must cross into the shell only through `env:`, never through
    direct expression interpolation into the script text -- interpolation happens before the
    shell parses the command, so a value containing a quote or shell metacharacter could break
    out of its surrounding literal. Whole-line comments are ignored to avoid flagging prose that
    merely mentions the `${{ }}` syntax (e.g. this file's own workflow-hardening comments).
    """
    failures: list[str] = []
    lines = text.splitlines()
    index = 0
    in_block = False
    block_content_indent: int | None = None
    while index < len(lines):
        line = lines[index]
        line_number = index + 1
        if in_block:
            if line.strip() == "":
                index += 1
                continue
            indent = len(line) - len(line.lstrip(" \t"))
            if block_content_indent is None:
                block_content_indent = indent
            elif indent < block_content_indent:
                in_block = False
                continue  # reprocess this line outside the block
            stripped = line.strip()
            if not stripped.startswith("#") and _has_unsafe_interpolation(line):
                failures.append(
                    f"{label}:{line_number}: "
                    f"'${{{{ ... }}}}' interpolated directly inside a run: block -- route it "
                    f"through env: instead: {stripped}"
                )
            index += 1
            continue

        match = RUN_KEY.match(line)
        if match:
            rest = match.group("rest").strip()
            if rest == "" or BLOCK_SCALAR.match(rest):
                in_block = True
                block_content_indent = None
                index += 1
                continue
            if not rest.startswith("#") and _has_unsafe_interpolation(rest):
                failures.append(
                    f"{label}:{line_number}: "
                    f"'${{{{ ... }}}}' interpolated directly inside a run: block -- route it "
                    f"through env: instead: {rest}"
                )
        index += 1
    return failures


def find_inno_setup_provenance_violations(label: str, text: str) -> list[str]:
    """A workflow that installs a pinned Inno Setup must also prove it uses exactly that binary.

    A textual `--version=X.Y.Z` pin on `choco install innosetup` does not, by itself, guarantee
    the compiler that later runs ISCC.exe is the one just installed: a stray pre-existing
    ISCC.exe reachable via PATH/chocolatey shim/another Inno Setup major version could still be
    picked up first. This check requires that any workflow installing Inno Setup via choco also
    (a) exports a resolved ISCC_PATH via GITHUB_ENV in the same step, rather than searching PATH
    or multiple install locations there, and (b) forwards that resolved path to the build script
    via -IsccPath.
    """
    failures: list[str] = []
    lines = text.splitlines()
    if not any("choco install innosetup" in line.lower() for line in lines):
        return failures

    install_block_lines: list[tuple[int, str]] = []
    in_install_step = False
    step_indent: int | None = None
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped.lower().startswith("- name:") and "inno setup" in stripped.lower():
            in_install_step = True
            step_indent = len(line) - len(line.lstrip(" \t"))
            continue
        if in_install_step:
            indent = len(line) - len(line.lstrip(" \t"))
            if stripped and stripped.startswith("- ") and indent <= (step_indent or 0):
                in_install_step = False
                continue
            install_block_lines.append((index + 1, line))

    block_text = "\n".join(content for _, content in install_block_lines)
    if not block_text:
        # choco install innosetup exists but not inside a recognizably-named step: fail closed,
        # the invariant cannot be verified.
        failures.append(
            f"{label}: 'choco install innosetup' found outside an "
            f"identifiable Inno Setup install step (expected a step name containing "
            f"'Inno Setup'); cannot verify compiler provenance."
        )
        return failures

    for marker in ISCC_AMBIGUOUS_SEARCH_MARKERS:
        if marker in block_text:
            failures.append(
                f"{label}: Inno Setup install step still searches for "
                f"ISCC.exe ambiguously ('{marker}') instead of using the deterministic path the "
                f"choco package just installed."
            )

    if not ISCC_ENV_EXPORT.search(block_text):
        failures.append(
            f"{label}: Inno Setup install step does not export a resolved "
            f"ISCC_PATH via $env:GITHUB_ENV; downstream steps cannot prove which compiler they use."
        )

    if "-IsccPath" not in text:
        failures.append(
            f"{label}: workflow installs Inno Setup but no step forwards "
            f"-IsccPath to a release/installer build script; the resolved compiler is never "
            f"actually enforced downstream."
        )

    return failures


SETUP_BUILDING_INVOCATION = re.compile(
    r"scripts[\\/]release[\\/](?P<script>publish-windows-release\.ps1|build-local-windows-candidate\.ps1)")


def find_iscc_propagation_violations(label: str, text: str) -> list[str]:
    """Every setup-building invocation in an Inno-installing workflow must carry the qualified compiler.

    build-windows-installer.ps1 only honours a pinned compiler when it is actually told about one;
    with neither -IsccPath nor -RequiredIsccVersion it falls back to searching PATH and the Inno
    Setup install locations. A release/qualification workflow that installs a pinned Inno Setup and
    then omits those arguments on any build-capable call therefore silently reintroduces the
    ambiguity for that build -- which is exactly how the smoke setup once ended up compiled by the
    Chocolatey shim while the production setup used the qualified binary.

    Invocations passing -PublishOnly are exempt: that mode uploads already-built, already-verified
    artifacts and compiles nothing.
    """
    failures: list[str] = []
    if not any("choco install innosetup" in line.lower() for line in text.splitlines()):
        return failures

    for line_number, line in enumerate(text.splitlines(), start=1):
        match = SETUP_BUILDING_INVOCATION.search(line)
        if not match:
            continue
        if "-PublishOnly" in line:
            continue
        missing = [flag for flag in ("-IsccPath", "-RequiredIsccVersion") if flag not in line]
        if missing:
            failures.append(
                f"{label}:{line_number}: {match.group('script')} can compile a "
                f"setup here but the invocation does not pass {', '.join(missing)}; the build would "
                f"fall back to ambiguous ISCC.exe resolution."
            )
    return failures


def find_windows_qualification_coverage_violations(root: Path) -> list[str]:
    """windows-installer.yml must re-qualify Windows when the files defining release compilation change.

    The Windows qualification workflow is what actually proves the candidate builds, installs,
    handshakes over MCP and uninstalls. If the real publication workflow (or the supply-chain gate
    that constrains it) can be edited without triggering it, that proof silently stops covering the
    thing being changed.
    """
    failures: list[str] = []
    workflow = root / WORKFLOWS / "windows-installer.yml"
    label = relative(root, workflow)
    if not workflow.is_file():
        return [f"{label}: Windows qualification workflow not found"]

    trigger_paths = _trigger_path_filters(workflow.read_text(encoding="utf-8"))
    required_coverage = (
        ".github/workflows/release-windows.yml",
        "scripts/quality/check-workflow-pins.py",
    )
    for trigger in ("pull_request", "push"):
        if trigger not in trigger_paths:
            failures.append(
                f"{label}: no '{trigger}' paths filter found; cannot verify that "
                f"Windows qualification covers the release workflow."
            )
            continue
        for required in required_coverage:
            if required not in trigger_paths[trigger]:
                failures.append(
                    f"{label}: '{trigger}' paths filter does not include "
                    f"'{required}'; changing it would skip Windows qualification."
                )

    if "pull_request" in trigger_paths and "push" in trigger_paths:
        if trigger_paths["pull_request"] != trigger_paths["push"]:
            failures.append(
                f"{label}: pull_request and push paths filters differ; a change "
                f"qualified on a PR must be re-qualified on the branch it lands on."
            )
    return failures


def _trigger_path_filters(text: str) -> dict[str, list[str]]:
    """Extract {trigger: [path globs]} from a workflow's `on:` block without a YAML dependency."""
    filters: dict[str, list[str]] = {}
    lines = text.splitlines()
    in_on_block = False
    trigger: str | None = None
    paths_indent: int | None = None
    for raw in lines:
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        if indent == 0:
            in_on_block = stripped in ("on:", '"on":', "'on':")
            trigger = None
            paths_indent = None
            continue
        if not in_on_block:
            continue
        if paths_indent is not None:
            if indent > paths_indent and stripped.startswith("- "):
                filters[trigger].append(stripped[2:].strip().strip("'\""))
                continue
            paths_indent = None
        if indent == 2 and stripped.endswith(":"):
            trigger = stripped[:-1]
            continue
        if trigger is not None and stripped == "paths:":
            paths_indent = indent
            filters.setdefault(trigger, [])
    return filters


def check_build_windows_installer_script(root: Path) -> list[str]:
    """The installer build script must not let an explicit -IsccPath be second-guessed."""
    failures: list[str] = []
    script = root / "scripts" / "release" / "build-windows-installer.ps1"
    if not script.is_file():
        failures.append(f"{relative(root, script)}: script not found")
        return failures
    text = script.read_text(encoding="utf-8")
    if "$IsccPath" not in text:
        failures.append(
            f"{relative(root, script)}: does not accept an -IsccPath parameter; the release/CI "
            f"path cannot pin the compiler it uses."
        )
        return failures
    if not re.search(r"IsNullOrWhiteSpace\(\$IsccPath\)", text):
        failures.append(
            f"{relative(root, script)}: does not branch on whether -IsccPath was supplied; "
            f"an explicit release-qualified path could still be silently overridden by a search."
        )
    # The ambiguous multi-candidate search must only run when -IsccPath was NOT supplied. A
    # reasonable static proxy: the candidate-array search ($IsccCandidates) must textually appear
    # after an IsNullOrWhiteSpace($IsccPath) guard, i.e. inside its negative branch.
    guard_match = re.search(r"IsNullOrWhiteSpace\(\$IsccPath\)", text)
    candidates_match = re.search(r"\$IsccCandidates\s*=", text)
    if guard_match and candidates_match and candidates_match.start() < guard_match.start():
        failures.append(
            f"{relative(root, script)}: ambiguous ISCC candidate search appears before the "
            f"-IsccPath guard; an explicit release-qualified path could be shadowed."
        )

    # Package metadata proves what was installed, not what executed. The build must assert the
    # engine version reported by the compiler it actually ran.
    if "Assert-IsccEngineVersion" not in text:
        failures.append(
            f"{relative(root, script)}: does not assert the engine version reported by the "
            f"compiler it executes; installed-package metadata alone cannot prove which binary "
            f"produced the setup."
        )
    if not re.search(r"IsNullOrWhiteSpace\(\$IsccPath\)\s*-ne\s*\[string\]::IsNullOrWhiteSpace\(\$RequiredIsccVersion\)", text):
        failures.append(
            f"{relative(root, script)}: does not reject a half-qualified invocation; -IsccPath and "
            f"-RequiredIsccVersion must be required together so neither the binary nor its version "
            f"can go unproven."
        )

    helper = root / "scripts" / "release" / "iscc-provenance.ps1"
    tests = root / "scripts" / "release" / "test-iscc-provenance.ps1"
    for required in (helper, tests):
        if not required.is_file():
            failures.append(f"{relative(root, required)}: required Inno Setup provenance file not found")
    return failures


def find_pin_violations(label: str, text: str) -> tuple[list[str], int]:
    """(failures, number of external `uses:` read): actions pinned by commit SHA, Inno Setup by version."""
    failures: list[str] = []
    checked = 0
    for line_number, line in enumerate(text.splitlines(), start=1):
        match = USES.search(line)
        if match:
            target = match.group("target")
            ref = match.group("ref")
            if not target.startswith("./"):
                checked += 1
                if not SHA.fullmatch(ref):
                    failures.append(f"{label}:{line_number}: mutable uses ref {target}@{ref}")
        if "choco install innosetup" in line.lower() and f"--version={INNO_VERSION}" not in line and "$env:INNO_SETUP_VERSION" not in line:
            failures.append(f"{label}:{line_number}: Inno Setup must be pinned to {INNO_VERSION}")
        if "choco install innosetup" in line.lower() and "$env:INNO_SETUP_VERSION" in line:
            if not re.search(rf"INNO_SETUP_VERSION:\s*['\"]?{re.escape(INNO_VERSION)}\b", text):
                failures.append(
                    f"{label}:{line_number}: 'choco install innosetup' "
                    f"reads $env:INNO_SETUP_VERSION but no env: INNO_SETUP_VERSION: '{INNO_VERSION}' "
                    f"pin was found in this workflow."
                )
    return failures, checked


def check(root: Path) -> tuple[list[str], int, int]:
    """(failures, number of external `uses:` read, number of `runs-on` read) for the workflows of `root`."""
    failures: list[str] = []
    checked = 0
    runners = 0
    kept_logins: set[tuple[str, str]] = set()
    guarded = False
    workflows = root / WORKFLOWS
    for workflow in sorted(workflows.glob("*.y*ml")) if workflows.is_dir() else []:
        label = relative(root, workflow)
        text = workflow.read_text(encoding="utf-8")
        guarded = guarded or TESTCONTAINERS_GUARD_ENV["TESTCONTAINERS_PULL_POLICY"] in text
        pin_failures, pin_checked = find_pin_violations(label, text)
        failures.extend(pin_failures)
        checked += pin_checked
        failures.extend(find_run_block_violations(label, text))
        failures.extend(find_inno_setup_provenance_violations(label, text))
        failures.extend(find_iscc_propagation_violations(label, text))
        runner_failures, runner_count = find_floating_runner_violations(label, text)
        failures.extend(runner_failures)
        runners += runner_count
        job_failures, kept = find_job_hardening_violations(label, text)
        failures.extend(job_failures)
        kept_logins.update((workflow.name, job) for job in kept)
        failures.extend(find_release_attestation_violations(label, text))
    failures.extend(find_stale_docker_login_entries(kept_logins))
    if guarded and not (root / TESTCONTAINERS_GUARD_CLASS).is_file():
        failures.append(f"{TESTCONTAINERS_GUARD_CLASS} is missing: the workflows select it as the Testcontainers pull "
                        f"policy, so the tests would fail to start any container.")
    failures.extend(find_missing_documented_scripts(root))
    failures.extend(find_windows_qualification_coverage_violations(root))
    failures.extend(check_build_windows_installer_script(root))
    return failures, checked, runners


def main(argv: list[str] | None = None) -> int:
    failures, checked, runners = check(gate_cli.parse_root(__doc__, DEFAULT_ROOT, argv))
    if failures:
        print("WORKFLOW SUPPLY-CHAIN PIN GATE FAILED", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1
    print(f"WORKFLOW SUPPLY-CHAIN PIN GATE SUCCESS (external uses={checked}, runs-on={runners}, Inno={INNO_VERSION})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
