package com.minos.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Fake hostile provider used by containment tests.
 *
 * <p>It materializes a pathological tree inside the roots MINOS made writable and then stays alive,
 * so that a test can prove MINOS intervenes <em>during</em> execution instead of measuring the
 * damage afterwards.</p>
 */
public final class HostileProviderMain {

    private HostileProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Path target = Path.of(arguments[0]);
        int files = Integer.parseInt(arguments[1]);
        int bytesPerFile = Integer.parseInt(arguments[2]);
        long aliveSeconds = Long.parseLong(arguments[3]);
        byte[] payload = new byte[bytesPerFile];
        Path nested = target.resolve("hostile-tree");
        Files.createDirectories(nested);
        for (int index = 0; index < files; index++) {
            Files.write(nested.resolve("hostile-" + index + ".bin"), payload);
        }
        TimeUnit.SECONDS.sleep(aliveSeconds);
    }
}
