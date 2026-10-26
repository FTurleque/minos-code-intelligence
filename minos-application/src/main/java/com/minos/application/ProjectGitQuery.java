package com.minos.application;

import com.minos.domain.git.ActivityQuery;
import com.minos.domain.git.ActivityReport;
import com.minos.domain.git.RepositoryView;

import java.nio.file.Path;

/**
 * Application query for the factual Git facts of a project's work tree (ADR 0019): what the CLI and the Java API read
 * instead of the engine port. The facts are the records of {@code com.minos.domain.git}.
 *
 * <p>Both methods declare {@code throws Exception}: the failure of the Git port is propagated unchanged, so the public
 * error messages stay those of the Git library.</p>
 */
public interface ProjectGitQuery {

    RepositoryView inspect(Path projectRoot) throws Exception;

    ActivityReport analyze(Path projectRoot, ActivityQuery query) throws Exception;
}
