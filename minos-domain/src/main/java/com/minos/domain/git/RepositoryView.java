package com.minos.domain.git;

import java.util.List;

/** Factual state of a Git work tree: identity, branch, head and the limitations of what could be read. */
public record RepositoryView(
        String repositoryId,
        String workTree,
        String originRemote,
        String branch,
        String headCommit,
        boolean detachedHead,
        boolean shallow,
        boolean clean,
        List<String> limitations
) {
    public RepositoryView {
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
