package com.minos.git;

import com.minos.domain.git.ActivityQuery;
import com.minos.domain.git.ActivityReport;
import com.minos.domain.git.RepositoryView;

import java.nio.file.Path;

/**
 * Port: factual Git intelligence of a local repository. The JGit adapter ({@code GitIntelligenceService},
 * minos-integration-git) implements it; the surfaces read it through the application query {@code ProjectGitQuery}.
 * The facts it returns are records of {@code com.minos.domain.git}.
 *
 * <p>The methods declare {@code throws Exception}: an adapter propagates the failure of its Git library unchanged (for
 * example a checked JGit exception), so the public error messages stay those of the library.</p>
 */
public interface GitIntelligence {

    /**
     * Value rendered instead of an author's e-mail address unless the caller asked for it
     * ({@link ActivityQuery#includeAuthorEmails()}): the {@code authorEmail} component stays present (versioned,
     * additive API), only its default value is masked (ADR 0019).
     */
    String MASKED_AUTHOR_EMAIL = "[masked]";

    RepositoryView inspect(Path projectRoot) throws Exception;

    ActivityReport analyze(Path projectRoot, ActivityQuery query) throws Exception;
}
