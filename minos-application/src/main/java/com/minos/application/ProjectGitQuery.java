package com.minos.application;

import com.minos.domain.git.ActivityQuery;
import com.minos.domain.git.ActivityReport;
import com.minos.domain.git.RepositoryView;

import java.nio.file.Path;

/**
 * Application query for the factual Git facts of a project's work tree (ADR 0019): what the CLI and the Java API read
 * instead of the engine port. The facts are the records of {@code com.minos.domain.git}.
 *
 * <p>Both methods declare {@code throws Exception}, as the port does, which Sonar rule java:S112 reports. The
 * deviation is deliberate: the failure of the Git library is propagated unchanged because the surfaces classify it by
 * its type. The Java API maps an {@link java.io.IOException} to its I/O error code and any other checked failure (a
 * JGit {@code GitAPIException}, for instance) to its execution error code; the CLI and the Java API publish the
 * failure's own message, falling back to its simple class name. The port cannot name JGit types, and a specific or
 * wrapping exception would merge the two error codes and replace the published messages. Each declaration therefore
 * carries a {@code NOSONAR} marker aimed at java:S112; the marker silences every rule on its line, so these lines hold
 * the bare declaration and nothing else.</p>
 */
public interface ProjectGitQuery {

    RepositoryView inspect(Path projectRoot) throws Exception; // NOSONAR java:S112, type-classified failure (see above)

    ActivityReport analyze(Path projectRoot, ActivityQuery query) throws Exception; // NOSONAR java:S112, as inspect
}
