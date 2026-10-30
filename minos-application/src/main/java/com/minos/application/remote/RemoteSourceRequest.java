package com.minos.application.remote;

import com.minos.remote.RemoteRepositoryRequest;

import java.util.Objects;
import java.util.Optional;

/**
 * A validated remote source: an allowed HTTPS host, a reference, a complete commit pin, a project subdirectory and an
 * optional credential variable name, never a credential. {@link #of} validates at once (D9): a command builds it
 * before it opens the application, so an invalid URL, reference, subdirectory or variable name is refused, with the
 * engine message, without touching {@code MINOS_HOME}.
 */
public final class RemoteSourceRequest {

    private final RemoteRepositoryRequest request;

    private RemoteSourceRequest(RemoteRepositoryRequest request) {
        this.request = Objects.requireNonNull(request, "request");
    }

    /** Validates the source; an invalid part is an {@link IllegalArgumentException} whose message names no secret. */
    public static RemoteSourceRequest of(
            String repositoryUri,
            String reference,
            String expectedCommit,
            String projectSubdirectory,
            String credentialEnvironmentVariable
    ) {
        return new RemoteSourceRequest(RemoteRepositoryRequest.of(
                repositoryUri, reference, expectedCommit, projectSubdirectory, credentialEnvironmentVariable));
    }

    /** The canonical, secret-free repository URI. */
    public String repository() {
        return request.canonicalRepositoryUri();
    }

    /** The canonical reference ({@code refs/heads/…} or {@code refs/tags/…}). */
    public String reference() {
        return request.reference();
    }

    /** The pinned commit, lower case. */
    public String commit() {
        return request.expectedCommit();
    }

    /** The name of the environment variable that holds the credential, never its value. */
    public Optional<String> credentialEnvironmentVariable() {
        return request.credentialEnvironmentVariable();
    }

    /** The canonical, secret-free description: repository, reference, commit, subdirectory, credential variable name. */
    @Override
    public String toString() {
        return "RemoteSourceRequest[repository=" + repository() + ", reference=" + reference() + ", commit=" + commit()
                + ", projectSubdirectory=" + request.projectSubdirectory().toString().replace('\\', '/')
                + ", credentialEnvironmentVariable=" + credentialEnvironmentVariable().orElse("-") + "]";
    }

    RemoteRepositoryRequest request() {
        return request;
    }
}
