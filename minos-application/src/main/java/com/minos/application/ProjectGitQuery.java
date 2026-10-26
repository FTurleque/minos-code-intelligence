package com.minos.application;

import com.minos.domain.git.ActivityQuery;
import com.minos.domain.git.ActivityReport;
import com.minos.domain.git.RepositoryView;

import java.nio.file.Path;

/**
 * Application query for the factual Git facts of a project's work tree (ADR 0019): what the CLI and the Java API read
 * instead of the engine port. The facts are the records of {@code com.minos.domain.git}.
 *
 * <p>Both methods declare {@code throws Exception}, as the port does: the failure of the Git library is propagated
 * unchanged, so the public error messages and the error codes the Java API derives from the exception type stay the
 * same. A specific or wrapping exception would change both.</p>
 */
public interface ProjectGitQuery {

    RepositoryView inspect(Path projectRoot) throws Exception; // NOSONAR: the port's failure is propagated unchanged

    ActivityReport analyze(Path projectRoot, ActivityQuery query) throws Exception; // NOSONAR: same as inspect
}
