package com.minos.application;

import com.minos.domain.git.ActivityQuery;
import com.minos.domain.git.ActivityReport;
import com.minos.domain.git.RepositoryView;
import com.minos.git.GitIntelligence;

import java.nio.file.Path;
import java.util.Objects;

/** Application query over the Git port of the composition: it never wraps the port's failures. */
public final class LocalProjectGitQuery implements ProjectGitQuery {

    private final GitIntelligence git;

    /** The query over the Git port of {@code application}, which assembles it (the port is never read here). */
    public LocalProjectGitQuery(MinosApplication application) {
        this(Objects.requireNonNull(application, "application").gitQuery().git);
    }

    LocalProjectGitQuery(GitIntelligence git) {
        this.git = Objects.requireNonNull(git, "git");
    }

    @Override
    public RepositoryView inspect(Path projectRoot) throws Exception {
        return git.inspect(projectRoot);
    }

    @Override
    public ActivityReport analyze(Path projectRoot, ActivityQuery query) throws Exception {
        return git.analyze(projectRoot, Objects.requireNonNull(query, "query"));
    }
}
