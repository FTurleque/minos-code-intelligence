package com.minos.runtime.local;

import com.minos.runtime.local.CgroupJobOwnership.OwnerLookup;
import com.minos.runtime.local.CgroupJobOwnership.OwnerStatus;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Child-process half of {@link OwnerLookupDescriptorExhaustionTest}: asks the real process table about a
 * process that is certainly alive (this one) after using up every file descriptor it is allowed.
 *
 * <p>Not a test: it is started by the test in a fresh JVM under a low {@code ulimit -n}, because running
 * out of descriptors inside the test JVM would break every other test sharing it.</p>
 */
public final class OwnerLookupDescriptorExhaustionProbe {

    private OwnerLookupDescriptorExhaustionProbe() {
    }

    public static void main(String[] arguments) throws IOException {
        long self = ProcessHandle.current().pid();
        OwnerLookup lookup = OwnerLookup.system(Path.of(arguments[0]));
        // Load everything the lookup touches while descriptors are still available.
        System.out.println("BEFORE=" + lookup.find(self).presence());
        lookup.find(Long.parseLong(arguments[1]));

        List<Closeable> held = new ArrayList<>();
        try {
            while (true) held.add(new FileInputStream("/proc/self/stat"));
        } catch (IOException exhausted) {
            System.out.println("EXHAUSTED_AFTER=" + held.size());
        }
        OwnerStatus live = lookup.find(self);
        OwnerStatus init = lookup.find(1L);
        for (Closeable descriptor : held) descriptor.close();
        System.out.println("LIVE_SELF=" + live.presence());
        System.out.println("LIVE_INIT=" + init.presence());
    }
}
