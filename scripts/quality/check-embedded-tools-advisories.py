#!/usr/bin/env python3
"""Fail when a tool of the embedded-tools catalogue is hit by a known advisory or is out of support (AUD-DEP-03).

The catalogue (embedded-tools.json) describes what MINOS ships or installs on the user's machine: indexers,
Node.js, Maven, Coursier, the resolved classpath of scip-java. Nothing watched them: Dependabot does not read the
catalogue and Dependency-Check excludes the embedded tools. This script is that watch. It is run by a scheduled
workflow, never by a pull request: an advisory published on a Tuesday must not turn every unrelated pull request
red (the pull-request gate is limited to what the pull request ships, the npm lockfiles).

What it does:

1. Every entry of the catalogue (providers, artifacts, assembled) must be in WATCHES, which says how it is watched
   (packages of an OSV ecosystem, a support schedule, the jars of the classpath lock) or why it is not. An entry
   missing from the table fails, and so does a table entry that the catalogue no longer has.
2. The packages are queried at the catalogue version through the OSV batch API. The 74 jars of
   scip-java-classpath.lock.json are queried in the same batch; the lock holds file names, so CLASSPATH_GROUPS
   gives the Maven group of each artifact (checked against the SHA-256 of the lock when the table was written);
   a jar missing from that table fails.
3. The release line of a runtime (Node.js) is compared with the published end-of-support schedule.
4. A finding can be waived until a date in embedded-tools-advisory-waivers.json (component, finding, reason,
   expiry). An expired waiver, a malformed one, or one that no longer matches a finding fails.

The output always has the same sections: affected, out of support, waived, not covered. "Not covered" is not a
failure: it is the declared limit of this watch (a binary downloaded from a release page has no package name that
an advisory database knows).

Exit codes: 0 nothing found; 1 a component is affected or out of support, or the table or the waivers are wrong;
2 the advisory service or the support schedule could not be read. A network failure is never "no advisory".

What this does not prove: that a component without a queryable identifier is safe, nor that the libraries bundled
inside a queried distribution (Maven, the scip-dotnet package, the scip-go binary) are free of advisories.

Self-test (no network, simulated answers): python scripts/quality/check-embedded-tools-advisories.py --self-test
"""
from __future__ import annotations

import argparse
import contextlib
import datetime
import io
import json
import re
import shutil
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - après l'ajout du dossier du script au chemin

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
LABEL = "EMBEDDED TOOLS ADVISORY WATCH"
CATALOGUE = "minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/embedded-tools.json"
WAIVERS = "scripts/quality/embedded-tools-advisory-waivers.json"
CATALOGUE_KINDS = ("providers", "artifacts", "assembled")

OSV_BATCH = "https://api.osv.dev/v1/querybatch"
OSV_PAGE = "https://osv.dev/vulnerability/"
OSV_BATCH_SIZE = 500
OSV_MAX_PAGES = 20
NODE_SCHEDULE = "https://raw.githubusercontent.com/nodejs/Release/main/schedule.json"
USER_AGENT = "minos-embedded-tools-advisory-watch"
TIMEOUT_SECONDS = 30
ATTEMPTS = 3
RETRY_PAUSE_SECONDS = 2
END_OF_SUPPORT = "end-of-support"

JAR_NAME = re.compile(r"^(?P<artifact>.+?)-(?P<version>\d.*)\.jar$")
LEADING_NUMBER = re.compile(r"^v?(\d+)")


@dataclass(frozen=True)
class Watch:
    """How one catalogue identifier is watched; ``limit`` says what is not watched, and why."""
    packages: tuple[tuple[str, str], ...] = ()  # (OSV ecosystem, package name), queried at the catalogue version
    support: str | None = None                  # key of SUPPORT_SCHEDULES
    classpath_lock: bool = False                # every jar of the entry's lock is queried as a Maven package
    limit: str | None = None


RELEASE_BINARY = ("binary downloaded from a release page: no advisory database describes it by a package name "
                  "and a version")

WATCHES: dict[str, Watch] = {
    # providers
    "scip-java": Watch(packages=(("Maven", "org.scip-code:scip-java"),)),
    "scip-typescript": Watch(packages=(("npm", "@sourcegraph/scip-typescript"),)),
    "scip-python": Watch(packages=(("npm", "@sourcegraph/scip-python"),)),
    "scip-dotnet": Watch(packages=(("NuGet", "scip-dotnet"),),
                         limit="the assemblies bundled in the NuGet package are not enumerated"),
    "scip-go": Watch(packages=(("Go", "github.com/scip-code/scip-go"),),
                     limit="the Go modules compiled into the binary are not enumerated"),
    "rust-analyzer-scip": Watch(limit=RELEASE_BINARY),
    # providers and artifacts
    "scip-clang": Watch(limit=RELEASE_BINARY),
    # artifacts
    "coursier": Watch(limit="native launcher pinned by a commit of coursier/launchers: the catalogue holds no "
                            "released version that an advisory database could match"),
    "maven": Watch(packages=(("Maven", "org.apache.maven:maven-core"), ("Maven", "org.apache.maven:maven-compat"),
                             ("Maven", "org.apache.maven:maven-embedder")),
                   limit="the third-party libraries bundled in the Maven distribution are not enumerated"),
    "nodejs": Watch(support="nodejs",
                    limit="advisories of the Node.js runtime itself: OSV has no ecosystem for a runtime binary, "
                          "only the end of support of the release line is checked"),
    "rust-analyzer": Watch(limit=RELEASE_BINARY),
    "scip-dotnet-nupkg": Watch(packages=(("NuGet", "scip-dotnet"),),
                               limit="the assemblies bundled in the NuGet package are not enumerated"),
    # assembled
    "scip-typescript-modules": Watch(packages=(("npm", "@sourcegraph/scip-typescript"),)),
    "scip-java-classpath": Watch(classpath_lock=True),
}

# Maven group of every artifact of scip-java-classpath.lock.json (the lock holds jar names, sizes and SHA-256).
# Written from a Coursier cache whose 74 jars matched the SHA-256 of the lock. A wrong group would query a package
# that does not exist and answer "no advisory": a change of this table is reviewed like a change of the lock.
CLASSPATH_GROUPS_BY_GROUP: dict[str, tuple[str, ...]] = {
    "aopalliance": ("aopalliance",),
    "com.github.ajalt.clikt": ("clikt-core-jvm", "clikt-jvm"),
    "com.github.ajalt.colormath": ("colormath-jvm",),
    "com.github.ajalt.mordant": ("mordant-core-jvm", "mordant-jvm", "mordant-jvm-ffm-jvm",
                                 "mordant-jvm-graal-ffi-jvm", "mordant-jvm-jna-jvm"),
    "com.google.code.findbugs": ("jsr305",),
    "com.google.errorprone": ("error_prone_annotations",),
    "com.google.guava": ("guava",),
    "com.google.inject": ("guice",),
    "com.google.j2objc": ("j2objc-annotations",),
    "com.google.protobuf": ("protobuf-java",),
    "commons-codec": ("commons-codec",),
    "commons-io": ("commons-io",),
    "javax.annotation": ("javax.annotation-api",),
    "javax.inject": ("javax.inject",),
    "net.java.dev.jna": ("jna",),
    "org.apache.commons": ("commons-lang3",),
    "org.apache.httpcomponents": ("httpclient", "httpcore"),
    "org.apache.maven": ("maven-artifact", "maven-builder-support", "maven-core", "maven-model",
                         "maven-model-builder", "maven-plugin-api", "maven-repository-metadata",
                         "maven-resolver-provider", "maven-settings", "maven-settings-builder"),
    "org.apache.maven.resolver": ("maven-resolver-api", "maven-resolver-connector-basic", "maven-resolver-impl",
                                  "maven-resolver-named-locks", "maven-resolver-spi",
                                  "maven-resolver-transport-file", "maven-resolver-transport-wagon",
                                  "maven-resolver-util"),
    "org.apache.maven.shared": ("maven-shared-utils",),
    "org.apache.maven.wagon": ("wagon-http", "wagon-http-shared", "wagon-provider-api"),
    "org.checkerframework": ("checker-compat-qual",),
    "org.codehaus.mojo": ("animal-sniffer-annotations",),
    "org.codehaus.plexus": ("plexus-cipher", "plexus-classworlds", "plexus-component-annotations",
                            "plexus-interpolation", "plexus-sec-dispatcher", "plexus-utils"),
    "org.eclipse.sisu": ("org.eclipse.sisu.inject", "org.eclipse.sisu.plexus"),
    "org.jetbrains": ("annotations",),
    "org.jetbrains.kotlin": ("kotlin-compiler-embeddable", "kotlin-daemon-embeddable", "kotlin-reflect",
                             "kotlin-script-runtime", "kotlin-scripting-common", "kotlin-scripting-dependencies",
                             "kotlin-scripting-dependencies-maven", "kotlin-scripting-jvm", "kotlin-stdlib"),
    "org.jetbrains.kotlinx": ("kotlinx-coroutines-core-jvm", "kotlinx-serialization-core-jvm",
                              "kotlinx-serialization-json-jvm"),
    "org.scip-code": ("scip-aggregator", "scip-java", "scip-java-bindings", "scip-shared"),
    "org.slf4j": ("jcl-over-slf4j", "slf4j-api"),
}
CLASSPATH_GROUPS: dict[str, str] = {artifact: group
                                    for group, artifacts in CLASSPATH_GROUPS_BY_GROUP.items()
                                    for artifact in artifacts}
# A classifier is part of the jar name, not of the version an advisory is written against.
CLASSPATH_CLASSIFIERS: dict[str, str] = {"guice": "-no_aop"}

# Runtime -> (address of the schedule, name shown, key of a release line in the schedule).
SUPPORT_SCHEDULES: dict[str, tuple[str, str, Callable[[str], str]]] = {
    "nodejs": (NODE_SCHEDULE, "Node.js", lambda major: f"v{major}"),
}

Fetch = Callable[[str, "dict | None"], object]


class ServiceUnavailable(Exception):
    """An advisory or schedule service could not be read: there is no verdict."""


@dataclass(frozen=True)
class Subject:
    """The catalogue entries that share an identifier and a version (one per platform, plus the provider)."""
    identifier: str
    version: str
    platforms: tuple[str, ...]
    lock: str | None

    @property
    def label(self) -> str:
        platforms = f" [{', '.join(self.platforms)}]" if self.platforms else ""
        return f"{self.identifier} {self.version}{platforms}"


@dataclass(frozen=True)
class Query:
    ecosystem: str
    name: str
    version: str


@dataclass
class Outcome:
    failures: list[str] = field(default_factory=list)
    affected: list[str] = field(default_factory=list)
    out_of_support: list[str] = field(default_factory=list)
    waived: list[str] = field(default_factory=list)
    uncovered: list[str] = field(default_factory=list)
    entries: int = 0
    queries: int = 0


def fetch_json(url: str, payload: dict | None = None) -> object:
    """GET (or POST ``payload`` as JSON) and parse the JSON answer; ServiceUnavailable after ATTEMPTS failures."""
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers={
        "Accept": "application/json", "Content-Type": "application/json", "User-Agent": USER_AGENT})
    last: Exception | None = None
    for attempt in range(ATTEMPTS):
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:  # noqa: S310 - https constants
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, OSError, ValueError) as error:
            last = error
            if attempt + 1 < ATTEMPTS:
                time.sleep(RETRY_PAUSE_SECONDS)
    raise ServiceUnavailable(f"{url}: {type(last).__name__}: {last}")


def read_json(root: Path, relative: str) -> tuple[object, str | None]:
    """(content, None), or (None, why the file cannot be used)."""
    path = root / relative
    if not path.is_file():
        return None, f"{relative}: missing"
    try:
        return json.loads(path.read_text(encoding="utf-8")), None
    except ValueError as error:
        return None, f"{relative}: unreadable ({error})"


def catalogue_subjects(catalogue: object) -> tuple[list[Subject], int, list[str]]:
    """(subjects sorted by identifier and version, number of catalogue entries, failures)."""
    if not isinstance(catalogue, dict):
        return [], 0, [f"{CATALOGUE}: not a JSON object"]
    failures: list[str] = []
    grouped: dict[tuple[str, str], dict] = {}
    entries = 0
    for kind in CATALOGUE_KINDS:
        items = catalogue.get(kind)
        if not isinstance(items, list):
            failures.append(f"{CATALOGUE}: '{kind}' is not a list")
            continue
        for item in items:
            entries += 1
            identifier = item.get("id") if isinstance(item, dict) else None
            version = item.get("version") if isinstance(item, dict) else None
            if not (isinstance(identifier, str) and identifier and isinstance(version, str) and version):
                failures.append(f"{CATALOGUE}: an entry of '{kind}' has no id or no version")
                continue
            slot = grouped.setdefault((identifier, version), {"platforms": set(), "lock": None})
            if isinstance(item.get("platform"), str):
                slot["platforms"].add(item["platform"])
            if isinstance(item.get("lock"), str):
                slot["lock"] = item["lock"]
    subjects = [Subject(identifier, version, tuple(sorted(slot["platforms"])), slot["lock"])
                for (identifier, version), slot in sorted(grouped.items())]
    known = {subject.identifier for subject in subjects}
    failures += [f"{CATALOGUE}: the component '{identifier}' is not in the WATCHES table of this script: say how "
                 f"it is watched, or why it is not"
                 for identifier in sorted(known - set(WATCHES))]
    failures += [f"WATCHES: '{identifier}' is no longer in {CATALOGUE}: remove the entry"
                 for identifier in sorted(set(WATCHES) - known)]
    return subjects, entries, failures


def classpath_queries(root: Path, subject: Subject) -> tuple[list[Query], list[str]]:
    """The Maven package of every jar of the subject's lock, or why the lock cannot be turned into packages."""
    if not subject.lock:
        return [], [f"{CATALOGUE}: the entry '{subject.identifier}' names no lock, its jars cannot be queried"]
    relative = f"{Path(CATALOGUE).parent.as_posix()}/{subject.lock}"
    lock, problem = read_json(root, relative)
    if problem:
        return [], [problem]
    jars = lock.get("jars") if isinstance(lock, dict) else None
    if not isinstance(jars, list) or not jars:
        return [], [f"{relative}: no 'jars' list"]
    queries: list[Query] = []
    failures: list[str] = []
    seen: set[str] = set()
    for jar in jars:
        name = jar.get("name") if isinstance(jar, dict) else None
        match = JAR_NAME.match(name) if isinstance(name, str) else None
        artifact = match.group("artifact") if match else None
        if artifact not in CLASSPATH_GROUPS:
            failures.append(f"{relative}: the jar '{name}' has no Maven group in CLASSPATH_GROUPS, it cannot be "
                            f"queried")
            continue
        version = match.group("version")
        classifier = CLASSPATH_CLASSIFIERS.get(artifact)
        if classifier and version.endswith(classifier):
            version = version[:-len(classifier)]
        seen.add(artifact)
        queries.append(Query("Maven", f"{CLASSPATH_GROUPS[artifact]}:{artifact}", version))
    failures += [f"CLASSPATH_GROUPS: '{artifact}' is no longer a jar of {relative}: remove the entry"
                 for artifact in sorted(set(CLASSPATH_GROUPS) - seen)]
    return queries, failures


def query_advisories(queries: list[Query], fetch: Fetch) -> dict[Query, list[str]]:
    """The advisory identifiers OSV knows for each query (all pages); ServiceUnavailable on any unusable answer."""
    found: dict[Query, list[str]] = {query: [] for query in queries}
    pending: list[tuple[Query, str | None]] = [(query, None) for query in queries]
    for _ in range(OSV_MAX_PAGES):
        if not pending:
            return found
        following: list[tuple[Query, str | None]] = []
        for start in range(0, len(pending), OSV_BATCH_SIZE):
            chunk = pending[start:start + OSV_BATCH_SIZE]
            body = {"queries": [
                {"package": {"ecosystem": query.ecosystem, "name": query.name}, "version": query.version,
                 **({"page_token": token} if token else {})}
                for query, token in chunk]}
            answer = fetch(OSV_BATCH, body)
            results = answer.get("results") if isinstance(answer, dict) else None
            if not isinstance(results, list) or len(results) != len(chunk):
                raise ServiceUnavailable(f"{OSV_BATCH}: the answer does not hold one result per query "
                                         f"({len(chunk)} asked)")
            for (query, _), result in zip(chunk, results):
                vulnerabilities = result.get("vulns", []) if isinstance(result, dict) else None
                if not isinstance(vulnerabilities, list):
                    raise ServiceUnavailable(f"{OSV_BATCH}: unreadable result for {query.ecosystem} {query.name}")
                for vulnerability in vulnerabilities:
                    identifier = vulnerability.get("id") if isinstance(vulnerability, dict) else None
                    if not isinstance(identifier, str) or not identifier:
                        raise ServiceUnavailable(f"{OSV_BATCH}: an advisory without an identifier for "
                                                 f"{query.ecosystem} {query.name}")
                    found[query].append(identifier)
                if result.get("next_page_token"):
                    following.append((query, result["next_page_token"]))
        pending = following
    raise ServiceUnavailable(f"{OSV_BATCH}: more than {OSV_MAX_PAGES} pages of results, the answer is not complete")


def support_finding(subject: Subject, runtime: str, schedule: dict, today: datetime.date) -> tuple[str | None, str | None]:
    """(out-of-support line or None, failure or None) for the release line of one runtime."""
    _, name, key_of = SUPPORT_SCHEDULES[runtime]
    major = LEADING_NUMBER.match(subject.version)
    key = key_of(major.group(1)) if major else None
    line = schedule.get(key) if key else None
    end = line.get("end") if isinstance(line, dict) else None
    try:
        ended = datetime.date.fromisoformat(end)
    except (TypeError, ValueError):
        return None, (f"{subject.label}: the {name} schedule has no end-of-support date for the release line "
                      f"'{key}', it cannot be said to be supported")
    if ended < today:
        return f"{subject.label}: {name} {major.group(1)} reached its end of support on {end}", None
    return None, None


def load_waivers(root: Path, today: datetime.date) -> tuple[dict[tuple[str, str], dict], list[str]]:
    """({(component, finding): waiver} for the waivers still valid, failures for malformed or expired ones)."""
    content, problem = read_json(root, WAIVERS)
    if problem:
        return {}, [problem]
    items = content.get("waivers") if isinstance(content, dict) else None
    if not isinstance(items, list):
        return {}, [f"{WAIVERS}: no 'waivers' list"]
    valid: dict[tuple[str, str], dict] = {}
    failures: list[str] = []
    for index, item in enumerate(items, start=1):
        where = f"{WAIVERS}: waiver {index}"
        values = [item.get(key) if isinstance(item, dict) else None for key in ("component", "finding", "reason", "expires")]
        if not all(isinstance(value, str) and value.strip() for value in values):
            failures.append(f"{where} must state a component, a finding, a reason and an expiry date")
            continue
        component, finding, _, expires = values
        try:
            expiry = datetime.date.fromisoformat(expires)
        except ValueError:
            failures.append(f"{where} ({component} {finding}): 'expires' is not a YYYY-MM-DD date")
            continue
        if expiry < today:
            failures.append(f"{where} ({component} {finding}) expired on {expires}: the finding fails again")
            continue
        valid[(component, finding)] = item
    return valid, failures


def evaluate(root: Path, fetch: Fetch, today: datetime.date) -> Outcome:
    """Reads the catalogue, queries the services through ``fetch`` and sorts what was found."""
    outcome = Outcome()
    catalogue, problem = read_json(root, CATALOGUE)
    if problem:
        outcome.failures.append(problem)
        return outcome
    subjects, outcome.entries, failures = catalogue_subjects(catalogue)
    owners: dict[Query, list[Subject]] = {}
    for subject in subjects:
        watch = WATCHES.get(subject.identifier)
        if watch is None:
            continue
        queries = [Query(ecosystem, name, subject.version) for ecosystem, name in watch.packages]
        if watch.classpath_lock:
            jars, problems = classpath_queries(root, subject)
            queries += jars
            failures += problems
        for query in queries:
            owners.setdefault(query, []).append(subject)
    if failures:  # the tables do not describe the catalogue: no query, a partial watch would look complete
        outcome.failures = failures
        return outcome
    waivers, outcome.failures = load_waivers(root, today)

    # (component, finding, line, section) for everything the services report.
    findings: list[tuple[str, str, str, list[str]]] = []
    advisories = query_advisories(list(owners), fetch)
    outcome.queries = len(owners)
    for query, subjects_of_query in owners.items():
        for advisory in sorted(set(advisories[query])):
            for subject in subjects_of_query:
                findings.append((subject.identifier, advisory,
                                 f"{subject.label}: {advisory} in {query.ecosystem} {query.name} {query.version} "
                                 f"({OSV_PAGE}{advisory})", outcome.affected))
    schedules: dict[str, dict] = {}
    for subject in subjects:
        watch = WATCHES[subject.identifier]
        if watch.support:
            url = SUPPORT_SCHEDULES[watch.support][0]
            if url not in schedules:
                schedule = fetch(url, None)
                if not isinstance(schedule, dict):
                    raise ServiceUnavailable(f"{url}: the schedule is not a JSON object")
                schedules[url] = schedule
            line, problem = support_finding(subject, watch.support, schedules[url], today)
            if problem:
                outcome.failures.append(problem)
            if line:
                findings.append((subject.identifier, END_OF_SUPPORT, line, outcome.out_of_support))
        if watch.limit:
            watched = watch.packages or watch.support or watch.classpath_lock
            outcome.uncovered.append(f"{subject.label}: {'partly watched' if watched else 'not watched'}: {watch.limit}")

    used: set[tuple[str, str]] = set()
    for component, finding, line, section in findings:
        waiver = waivers.get((component, finding))
        if waiver:
            used.add((component, finding))
            outcome.waived.append(f"{line} [waived until {waiver['expires']}: {waiver['reason'].strip()}]")
        else:
            section.append(line)
            outcome.failures.append(line)
    outcome.failures += [f"{WAIVERS}: the waiver of '{finding}' for '{component}' matches no finding: remove it"
                         for component, finding in sorted(set(waivers) - used)]
    return outcome


def render(outcome: Outcome, today: datetime.date) -> str:
    lines = [f"{LABEL} (catalogue entries={outcome.entries}, packages queried={outcome.queries}, date={today.isoformat()})"]
    for title, items in (("Affected by an advisory", outcome.affected), ("Out of support", outcome.out_of_support),
                         ("Waived until a date", outcome.waived), ("Not covered by this watch", outcome.uncovered)):
        lines.append(f"{title} ({len(items)}):")
        lines.extend(f"  - {item}" for item in items)
        if not items:
            lines.append("  (none)")
    return "\n".join(lines)


def run(root: Path, fetch: Fetch, today: datetime.date) -> int:
    try:
        outcome = evaluate(root, fetch, today)
    except ServiceUnavailable as error:
        print(f"{LABEL} UNAVAILABLE: {error}. No verdict was reached: this is not 'no advisory'.", file=sys.stderr)
        return 2
    print(render(outcome, today), flush=True)
    if gate_cli.report_failures(LABEL, outcome.failures):
        return 1
    print(f"{LABEL} SUCCESS (no component affected or out of support; not covered={len(outcome.uncovered)}, "
          f"waived={len(outcome.waived)})")
    return 0


# ---------------------------------------------------------------------------------------------------------------
# Self-test: simulated answers, no network.
# ---------------------------------------------------------------------------------------------------------------

TODAY = datetime.date(2026, 10, 13)
SCHEDULE = {"v20": {"start": "2023-04-18", "end": "2026-04-30"}, "v24": {"start": "2025-05-06", "end": "2028-04-30"}}


class FakeServices:
    """Answers like the OSV batch API and the Node.js schedule; records what was asked."""

    def __init__(self, advisories: dict[tuple[str, str, str], list[str]] | None = None,
                 schedule: object = None, failing: str | None = None, pages: bool = False):
        self.advisories = advisories or {}
        self.schedule = SCHEDULE if schedule is None else schedule
        self.failing = failing
        self.pages = pages
        self.asked: list[tuple[str, str, str]] = []
        self.calls = 0

    def __call__(self, url: str, payload: dict | None = None) -> object:
        self.calls += 1
        if self.failing and self.failing in url:
            raise ServiceUnavailable(f"{url}: simulated outage")
        if url == NODE_SCHEDULE:
            return self.schedule
        results = []
        for query in payload["queries"]:
            key = (query["package"]["ecosystem"], query["package"]["name"], query["version"])
            identifiers = self.advisories.get(key, [])
            if "page_token" not in query:
                self.asked.append(key)
            if self.pages and len(identifiers) > 1:
                if "page_token" in query:
                    results.append({"vulns": [{"id": identifier} for identifier in identifiers[1:]]})
                else:
                    results.append({"vulns": [{"id": identifiers[0]}], "next_page_token": "next"})
            else:
                results.append({"vulns": [{"id": identifier} for identifier in identifiers]} if identifiers else {})
        return {"results": results}


def small_catalogue() -> dict:
    return {
        "formatVersion": 1,
        "providers": [{"id": "scip-typescript", "version": "0.4.0", "components": ["nodejs"]},
                      {"id": "scip-clang", "version": "0.4.0", "components": ["scip-clang"]}],
        "artifacts": [{"id": "nodejs", "platform": "windows-x64", "version": "24.20.0"},
                      {"id": "nodejs", "platform": "linux-x64", "version": "24.20.0"},
                      {"id": "scip-clang", "platform": "linux-x64", "version": "0.4.0"},
                      {"id": "maven", "platform": "any", "version": "3.9.16"}],
        "assembled": [{"id": "scip-java-classpath", "platform": "windows-x64", "version": "0.13.1",
                       "lock": "scip-java-classpath.lock.json"}],
    }


def small_lock() -> dict:
    return {"coordinate": "org.scip-code:scip-java:0.13.1", "version": "0.13.1",
            "jars": [{"name": "guava-25.1-android.jar", "size": 1, "sha256": "00"},
                     {"name": "guice-4.2.2-no_aop.jar", "size": 1, "sha256": "00"},
                     {"name": "javax.inject-1.jar", "size": 1, "sha256": "00"}]}


class AdvisoryWatchTest(unittest.TestCase):

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.write(CATALOGUE, small_catalogue())
        self.write(str(Path(CATALOGUE).parent.as_posix()) + "/scip-java-classpath.lock.json", small_lock())
        self.waivers()
        self.table({**WATCHES})
        self.groups({"guava": "com.google.guava", "guice": "com.google.inject", "javax.inject": "javax.inject"})

    def table(self, watches: dict[str, Watch]) -> None:
        """Limits the table to the identifiers of the small catalogue (an unused entry of the table is refused)."""
        kept = {"scip-typescript", "scip-clang", "nodejs", "maven", "scip-java-classpath"}
        original = dict(WATCHES)
        WATCHES.clear()
        WATCHES.update({identifier: watch for identifier, watch in watches.items() if identifier in kept})
        self.addCleanup(lambda: (WATCHES.clear(), WATCHES.update(original)))

    def groups(self, groups: dict[str, str]) -> None:
        original = dict(CLASSPATH_GROUPS)
        CLASSPATH_GROUPS.clear()
        CLASSPATH_GROUPS.update(groups)
        self.addCleanup(lambda: (CLASSPATH_GROUPS.clear(), CLASSPATH_GROUPS.update(original)))

    def write(self, relative: str, content: object) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content if isinstance(content, str) else json.dumps(content), encoding="utf-8")

    def waivers(self, *waivers: dict) -> None:
        self.write(WAIVERS, {"formatVersion": 1, "waivers": list(waivers)})

    def evaluate(self, services: FakeServices | None = None) -> Outcome:
        return evaluate(self.root, services or FakeServices(), TODAY)

    def run_watch(self, services: FakeServices) -> tuple[int, str, str]:
        """(exit code, standard output, standard error) of one run."""
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            code = run(self.root, services, TODAY)
        return code, output.getvalue(), errors.getvalue()

    # --- nominal ---

    def test_a_catalogue_without_any_finding_is_accepted_and_lists_what_is_not_covered(self):
        services = FakeServices()
        outcome = self.evaluate(services)
        self.assertEqual([], outcome.failures)
        self.assertEqual([], outcome.affected)
        self.assertEqual([], outcome.out_of_support)
        self.assertEqual(7, outcome.entries)
        self.assertEqual(3, len(outcome.uncovered), outcome.uncovered)
        self.assertTrue(any(line.startswith("scip-clang 0.4.0 [linux-x64]: not watched: ") for line in outcome.uncovered))
        self.assertTrue(any(line.startswith("maven 3.9.16 [any]: partly watched: ") for line in outcome.uncovered))
        self.assertTrue(any(line.startswith("nodejs 24.20.0 [linux-x64, windows-x64]: partly watched: ")
                            for line in outcome.uncovered))
        self.assertIn(("npm", "@sourcegraph/scip-typescript", "0.4.0"), services.asked)
        self.assertIn(("Maven", "org.apache.maven:maven-core", "3.9.16"), services.asked)
        self.assertEqual(len(services.asked), outcome.queries)

    def test_every_jar_of_the_classpath_lock_is_queried_as_a_maven_package_without_its_classifier(self):
        services = FakeServices()
        self.evaluate(services)
        self.assertIn(("Maven", "com.google.guava:guava", "25.1-android"), services.asked)
        self.assertIn(("Maven", "com.google.inject:guice", "4.2.2"), services.asked)
        self.assertIn(("Maven", "javax.inject:javax.inject", "1"), services.asked)

    def test_the_report_always_has_its_four_sections(self):
        text = render(self.evaluate(), TODAY)
        for title in ("Affected by an advisory (0):", "Out of support (0):", "Waived until a date (0):",
                      "Not covered by this watch (3):"):
            self.assertIn(title, text)
        code, output, errors = self.run_watch(FakeServices())
        self.assertEqual(0, code)
        self.assertIn(text, output)
        self.assertIn(f"{LABEL} SUCCESS", output)
        self.assertEqual("", errors)

    # --- findings ---

    def test_a_component_hit_by_an_advisory_is_refused_by_name(self):
        services = FakeServices({("Maven", "com.google.guava:guava", "25.1-android"): ["GHSA-aaaa-bbbb-cccc"]})
        outcome = self.evaluate(services)
        self.assertEqual(1, len(outcome.affected), outcome.affected)
        self.assertIn("scip-java-classpath 0.13.1 [windows-x64]", outcome.affected[0])
        self.assertIn("GHSA-aaaa-bbbb-cccc", outcome.affected[0])
        self.assertIn("com.google.guava:guava 25.1-android", outcome.affected[0])
        self.assertEqual(outcome.affected, outcome.failures)
        code, output, errors = self.run_watch(services)
        self.assertEqual(1, code)
        self.assertIn("Affected by an advisory (1):", output)
        self.assertIn(f"{LABEL} FAILED: scip-java-classpath 0.13.1 [windows-x64]: GHSA-aaaa-bbbb-cccc", errors)
        self.assertNotIn("SUCCESS", output)

    def test_an_advisory_listed_on_a_following_page_is_found(self):
        services = FakeServices({("Maven", "org.apache.maven:maven-core", "3.9.16"): ["GHSA-1", "GHSA-2", "GHSA-3"]},
                                pages=True)
        outcome = self.evaluate(services)
        self.assertEqual(3, len(outcome.affected), outcome.affected)

    def test_a_runtime_whose_release_line_has_ended_is_refused(self):
        catalogue = small_catalogue()
        catalogue["artifacts"][1]["version"] = "20.20.2"
        self.write(CATALOGUE, catalogue)
        outcome = self.evaluate()
        self.assertEqual(1, len(outcome.out_of_support), outcome.out_of_support)
        self.assertIn("nodejs 20.20.2 [linux-x64]", outcome.out_of_support[0])
        self.assertIn("2026-04-30", outcome.out_of_support[0])
        self.assertEqual(outcome.out_of_support, outcome.failures)

    def test_a_release_line_that_ends_today_is_still_supported(self):
        services = FakeServices(schedule={"v24": {"end": TODAY.isoformat()}})
        self.assertEqual([], self.evaluate(services).failures)

    def test_a_release_line_missing_from_the_schedule_is_refused_not_assumed_supported(self):
        services = FakeServices(schedule={"v22": {"end": "2027-04-30"}})
        outcome = self.evaluate(services)
        self.assertEqual(1, len(outcome.failures), outcome.failures)
        self.assertIn("nodejs 24.20.0", outcome.failures[0])
        self.assertIn("v24", outcome.failures[0])

    # --- table ---

    def test_a_catalogue_component_missing_from_the_table_is_refused_before_any_query(self):
        catalogue = small_catalogue()
        catalogue["artifacts"].append({"id": "scip-ruby", "platform": "linux-x64", "version": "0.4.7"})
        self.write(CATALOGUE, catalogue)
        services = FakeServices()
        outcome = self.evaluate(services)
        self.assertEqual(1, len(outcome.failures), outcome.failures)
        self.assertIn("'scip-ruby'", outcome.failures[0])
        self.assertEqual(0, services.calls)

    def test_a_table_entry_that_the_catalogue_no_longer_has_is_refused(self):
        catalogue = small_catalogue()
        catalogue["artifacts"] = [entry for entry in catalogue["artifacts"] if entry["id"] != "maven"]
        self.write(CATALOGUE, catalogue)
        outcome = self.evaluate()
        self.assertEqual(1, len(outcome.failures), outcome.failures)
        self.assertIn("'maven'", outcome.failures[0])

    def test_a_jar_missing_from_the_group_table_is_refused(self):
        lock = small_lock()
        lock["jars"].append({"name": "netty-all-4.1.0.Final.jar", "size": 1, "sha256": "00"})
        self.write(str(Path(CATALOGUE).parent.as_posix()) + "/scip-java-classpath.lock.json", lock)
        outcome = self.evaluate()
        self.assertEqual(1, len(outcome.failures), outcome.failures)
        self.assertIn("netty-all-4.1.0.Final.jar", outcome.failures[0])

    def test_a_group_table_entry_without_a_jar_is_refused(self):
        lock = small_lock()
        lock["jars"] = lock["jars"][:2]
        self.write(str(Path(CATALOGUE).parent.as_posix()) + "/scip-java-classpath.lock.json", lock)
        outcome = self.evaluate()
        self.assertEqual(1, len(outcome.failures), outcome.failures)
        self.assertIn("'javax.inject'", outcome.failures[0])

    def test_a_missing_or_unreadable_catalogue_or_lock_is_refused(self):
        (self.root / Path(CATALOGUE).parent / "scip-java-classpath.lock.json").unlink()
        self.assertEqual(1, len(self.evaluate().failures))
        self.write(CATALOGUE, "{not json")
        self.assertEqual(1, len(self.evaluate().failures))
        (self.root / CATALOGUE).unlink()
        self.assertEqual(1, len(self.evaluate().failures))

    # --- services ---

    def test_an_unreachable_advisory_service_is_a_failure_distinct_from_an_alert(self):
        services = FakeServices(failing="api.osv.dev")
        with self.assertRaises(ServiceUnavailable):
            self.evaluate(services)
        code, output, errors = self.run_watch(services)
        self.assertEqual(2, code)
        self.assertIn(f"{LABEL} UNAVAILABLE", errors)
        self.assertNotIn("FAILED", errors)
        self.assertEqual("", output, "no section is printed: an empty 'affected' list would read as 'no advisory'")

    def test_an_unreachable_support_schedule_is_a_failure_too(self):
        code, output, errors = self.run_watch(FakeServices(failing="nodejs/Release"))
        self.assertEqual(2, code)
        self.assertIn(f"{LABEL} UNAVAILABLE", errors)
        self.assertEqual("", output)

    def test_the_real_reader_turns_a_connection_failure_into_an_unavailable_service(self):
        def refuse(request, timeout):
            raise urllib.error.URLError("simulated refusal")
        original, pause = urllib.request.urlopen, time.sleep
        urllib.request.urlopen, time.sleep = refuse, lambda seconds: None
        self.addCleanup(lambda: (setattr(urllib.request, "urlopen", original), setattr(time, "sleep", pause)))
        with self.assertRaises(ServiceUnavailable):
            fetch_json(OSV_BATCH, {"queries": []})

    def test_an_answer_that_does_not_match_the_queries_is_not_read_as_no_advisory(self):
        for answer in ({"results": []}, {"results": "nothing"}, {}, [], {"results": [{"vulns": [{"no": "id"}]}] * 7}):
            with self.subTest(answer=answer):
                def fetch(url, payload=None, answer=answer):
                    return SCHEDULE if url == NODE_SCHEDULE else answer
                with self.assertRaises(ServiceUnavailable):
                    evaluate(self.root, fetch, TODAY)

    def test_a_schedule_that_is_not_an_object_is_not_read_as_supported(self):
        with self.assertRaises(ServiceUnavailable):
            self.evaluate(FakeServices(schedule=["v24"]))

    # --- waivers ---

    def test_a_dated_waiver_moves_the_finding_out_of_the_failures(self):
        services = FakeServices({("Maven", "com.google.guava:guava", "25.1-android"): ["GHSA-aaaa-bbbb-cccc"]})
        self.waivers({"component": "scip-java-classpath", "finding": "GHSA-aaaa-bbbb-cccc",
                      "reason": "not reachable from the indexer", "expires": "2026-12-31"})
        outcome = self.evaluate(services)
        self.assertEqual([], outcome.failures)
        self.assertEqual([], outcome.affected)
        self.assertEqual(1, len(outcome.waived), outcome.waived)
        self.assertIn("2026-12-31", outcome.waived[0])
        self.assertIn("not reachable from the indexer", outcome.waived[0])

    def test_an_expired_waiver_is_refused_and_its_finding_fails_again(self):
        services = FakeServices({("Maven", "com.google.guava:guava", "25.1-android"): ["GHSA-aaaa-bbbb-cccc"]})
        self.waivers({"component": "scip-java-classpath", "finding": "GHSA-aaaa-bbbb-cccc",
                      "reason": "not reachable from the indexer", "expires": "2026-10-12"})
        outcome = self.evaluate(services)
        self.assertEqual(1, len(outcome.affected), outcome.affected)
        self.assertEqual(2, len(outcome.failures), outcome.failures)
        self.assertTrue(any("expired on 2026-10-12" in failure for failure in outcome.failures), outcome.failures)

    def test_a_waiver_can_cover_the_end_of_support_of_a_runtime(self):
        catalogue = small_catalogue()
        catalogue["artifacts"][1]["version"] = "20.20.2"
        self.write(CATALOGUE, catalogue)
        self.waivers({"component": "nodejs", "finding": END_OF_SUPPORT,
                      "reason": "image rebuilt on Node.js 24 in the next release", "expires": "2026-11-30"})
        outcome = self.evaluate()
        self.assertEqual([], outcome.failures)
        self.assertEqual(1, len(outcome.waived))

    def test_a_waiver_that_matches_no_finding_is_refused(self):
        self.waivers({"component": "maven", "finding": "GHSA-gone-gone-gone", "reason": "fixed since",
                      "expires": "2026-12-31"})
        outcome = self.evaluate()
        self.assertEqual(1, len(outcome.failures), outcome.failures)
        self.assertIn("GHSA-gone-gone-gone", outcome.failures[0])
        self.assertIn("matches no finding", outcome.failures[0])

    def test_a_malformed_waiver_or_a_missing_waiver_file_is_refused(self):
        for waiver in ({"component": "maven", "finding": "GHSA-1", "expires": "2026-12-31"},
                       {"component": "maven", "finding": "GHSA-1", "reason": "  ", "expires": "2026-12-31"},
                       {"component": "maven", "finding": "GHSA-1", "reason": "later", "expires": "one day"},
                       "GHSA-1"):
            with self.subTest(waiver=waiver):
                self.waivers(waiver)
                self.assertEqual(1, len(self.evaluate().failures))
        (self.root / WAIVERS).unlink()
        self.assertEqual(1, len(self.evaluate().failures))


class RepositoryTableTest(unittest.TestCase):
    """The real catalogue and the real lock, with simulated answers: the tables of this script know all of them."""

    def test_the_repository_catalogue_is_entirely_known_and_its_uncovered_part_is_listed(self):
        services = FakeServices()
        outcome = evaluate(DEFAULT_ROOT, services, datetime.date(2020, 1, 1))
        # The simulated answers carry no advisory, so a real waiver matches no finding here: that is the weekly
        # run's verdict, not this table's.
        self.assertEqual([], [failure for failure in outcome.failures if "matches no finding" not in failure])
        self.assertGreaterEqual(outcome.entries, 11)
        self.assertTrue(outcome.uncovered)
        self.assertGreaterEqual(len([key for key in services.asked if key[0] == "Maven"]), 70)

    def test_the_repository_waivers_are_well_formed_and_name_catalogue_components(self):
        # Checked against a fixed date: an expiry is the weekly run's verdict and never turns a pull request red.
        waivers, failures = load_waivers(DEFAULT_ROOT, datetime.date(2020, 1, 1))
        self.assertEqual([], failures)
        unknown = sorted({component for component, _ in waivers} - set(WATCHES))
        self.assertEqual([], unknown, "a waiver names a component that the catalogue does not have")

    def test_a_copy_of_the_repository_catalogue_with_one_more_component_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in (WAIVERS, CATALOGUE):
                (root / relative).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(DEFAULT_ROOT / WAIVERS, root / WAIVERS)
            shutil.copytree(DEFAULT_ROOT / Path(CATALOGUE).parent, root / Path(CATALOGUE).parent, dirs_exist_ok=True)
            catalogue = json.loads((root / CATALOGUE).read_text(encoding="utf-8"))
            catalogue["artifacts"].append({"id": "scip-ruby", "platform": "linux-x64", "version": "0.4.7"})
            (root / CATALOGUE).write_text(json.dumps(catalogue), encoding="utf-8")
            outcome = evaluate(root, FakeServices(), TODAY)
            self.assertEqual(1, len(outcome.failures), outcome.failures)
            self.assertIn("'scip-ruby'", outcome.failures[0])


def self_test() -> int:
    suite = unittest.TestSuite()
    for case in (AdvisoryWatchTest, RepositoryTableTest):
        suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(case))
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    if not result.wasSuccessful():
        print(f"{LABEL} SELF-TEST FAILED", file=sys.stderr)
        return 1
    print(f"{LABEL} SELF-TEST SUCCESS (cases={result.testsRun}, simulated answers, no network)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--self-test", action="store_true", help="run the simulated cases (no network) and exit")
    arguments = parser.parse_args(argv)
    if arguments.self_test:
        return self_test()
    return run(arguments.root, fetch_json, datetime.datetime.now(datetime.timezone.utc).date())


if __name__ == "__main__":
    raise SystemExit(main())
