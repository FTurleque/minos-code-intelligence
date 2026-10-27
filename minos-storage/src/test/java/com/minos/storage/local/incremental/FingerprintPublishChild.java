package com.minos.storage.local.incremental;

import com.minos.incremental.ProjectFingerprintService;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Child process of {@link FingerprintPublicationCrashTest}: captures, publishes and promotes the fingerprint of a
 * source tree, then prints {@value #DONE}. The parent kills it at various points.
 */
public final class FingerprintPublishChild {
    static final String DONE = "PUBLISHED";

    private FingerprintPublishChild() {
    }

    public static void main(String[] args) throws Exception {
        FileProjectFingerprintSnapshotStore store = new FileProjectFingerprintSnapshotStore(Path.of(args[0]));
        UUID project = UUID.fromString(args[1]);
        store.publish(project, args[2], new ProjectFingerprintService().capture(Path.of(args[3])));
        store.promote(project, args[2]);
        System.out.println(DONE);
    }
}
