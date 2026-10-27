package com.minos.storage.postgresql;

import com.github.dockerjava.api.exception.NotFoundException;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Testcontainers image pull policy of the CI jobs that log out of Docker Hub before the tests (AUD-DEP-13, option B
 * of decision D4 of durcir-les-workflows-github).
 *
 * <p>Those jobs pull the images Testcontainers needs while logged in ({@code check-image-pins.py
 * --testcontainers-images}), then run {@code docker logout}. This policy never pulls: an image of that list must
 * already be local, and any other image is refused by name. A test that needs a new image therefore fails with a
 * message that says where to declare it, instead of an anonymous pull that would fail later on Docker Hub's quota.
 * The jobs select it with {@code TESTCONTAINERS_PULL_POLICY}; outside them Testcontainers keeps its default policy.</p>
 */
public final class PrePulledImagesOnlyPullPolicy implements ImagePullPolicy {

    static final String LIST_SOURCE = "scripts/quality/check-image-pins.py --testcontainers-images";

    private final Set<String> allowed;
    private final Predicate<DockerImageName> local;

    /** The policy Testcontainers instantiates: the pinned PostgreSQL image, looked up in the local Docker images. */
    public PrePulledImagesOnlyPullPolicy() {
        this(Set.of(pinnedPostgresImage()), PrePulledImagesOnlyPullPolicy::presentLocally);
    }

    PrePulledImagesOnlyPullPolicy(Set<String> allowed, Predicate<DockerImageName> local) {
        this.allowed = Set.copyOf(Objects.requireNonNull(allowed, "allowed"));
        this.local = Objects.requireNonNull(local, "local");
    }

    @Override
    public boolean shouldPull(DockerImageName imageName) {
        String requested = imageName.asCanonicalNameString();
        if (allowed.stream().map(DockerImageName::parse).map(DockerImageName::asCanonicalNameString)
                .noneMatch(requested::equals)) {
            throw new IllegalStateException("Testcontainers image " + requested + " is not in the pre-pulled list ("
                    + LIST_SOURCE + "): the CI logs out of Docker Hub before the tests, so declare the image there "
                    + "instead of pulling it anonymously");
        }
        if (!local.test(imageName)) {
            throw new IllegalStateException("Testcontainers image " + requested + " is listed but was not pulled "
                    + "before the tests: run the pre-pull step of the job (" + LIST_SOURCE + ")");
        }
        return false;
    }

    private static String pinnedPostgresImage() {
        try {
            return PostgresTestSupport.pinnedPostgresImage();
        } catch (IOException exception) {
            throw new UncheckedIOException("the pinned PostgreSQL image cannot be read", exception);
        }
    }

    private static boolean presentLocally(DockerImageName imageName) {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(imageName.asCanonicalNameString()).exec();
            return true;
        } catch (NotFoundException _) {
            return false;
        }
    }
}
