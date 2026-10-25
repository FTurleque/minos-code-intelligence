package com.minos.io;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The platform access control port is discovered once per process through the class loader of MINOS and is
 * consulted only on a file system with ACLs: none, or more than one, implementation fails the hardening closed,
 * with a message that names no path.
 */
class PlatformAccessControlDiscoveryTest {

    @TempDir
    Path temporary;

    @AfterEach
    void resetProbe() {
        PrivateLocalStorage.resetCapabilityProbeForTesting();
    }

    @Test
    void theSingleRegisteredImplementationIsTheOneUsed() throws Exception {
        PlatformAccessControl found = PrivateLocalStorage.discoverPlatformAccessControl(
                loaderRegistering(RecordingAccessControl.class));

        found.protectFromInheritance(temporary);

        assertTrue(found instanceof RecordingAccessControl, found.getClass().getName());
    }

    @Test
    void withoutAnyImplementationBothOperationsAreRefusedWithoutAPath() {
        PlatformAccessControl found = PrivateLocalStorage.discoverPlatformAccessControl(loaderRegistering());

        IOException protect = assertThrows(IOException.class, () -> found.protectFromInheritance(temporary));
        IOException read = assertThrows(IOException.class, () -> found.requireNoConditionalDeny(temporary));

        assertTrue(protect.getMessage().contains("no platform access control"), protect.getMessage());
        assertTrue(read.getMessage().contains("no platform access control"), read.getMessage());
        assertFalse(protect.getMessage().contains(temporary.toString()));
    }

    @Test
    void twoImplementationsAreRefusedRatherThanChosenBetween() {
        PlatformAccessControl found = PrivateLocalStorage.discoverPlatformAccessControl(
                loaderRegistering(RecordingAccessControl.class, OtherAccessControl.class));

        IOException refusal = assertThrows(IOException.class, () -> found.requireNoConditionalDeny(temporary));

        assertTrue(refusal.getMessage().contains("several platform access controls"), refusal.getMessage());
    }

    @Test
    void anAclFileSystemWithoutImplementationCreatesNothingAndNamesNoPath() throws Exception {
        UserPrincipal owner = Files.getOwner(temporary);
        PlatformAccessControl none = PrivateLocalStorage.discoverPlatformAccessControl(loaderRegistering());
        PrivateLocalStorage.useForTesting(new DelegatingProbe(
                FakeCapabilityProbe.aclOnly(target -> FakeAclFileAttributeView.ownerOnly(owner)), none));
        Path directory = temporary.resolve("private");

        IOException refusal = assertThrows(IOException.class, () -> PrivateLocalStorage.ensurePrivateDirectory(directory));

        assertTrue(refusal.getMessage().contains("no platform access control"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temporary.toString()));
        assertFalse(Files.exists(directory), "the directory this call created is removed");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aPosixFileSystemNeverConsultsThePort() throws Exception {
        RecordingAccessControl recording = new RecordingAccessControl();
        PrivateLocalStorage.useForTesting(new DelegatingProbe(new PosixProbe(), recording));

        PrivateLocalStorage.ensurePrivateDirectory(temporary.resolve("private"));

        assertTrue(recording.calls.isEmpty(), recording.calls.toString());
    }

    // ------------------------------------------------------------------------------ helpers

    private URLClassLoader loaderRegistering(Class<?>... implementations) {
        try {
            Path root = Files.createTempDirectory(temporary, "services-");
            Path services = Files.createDirectories(root.resolve("META-INF").resolve("services"));
            StringBuilder lines = new StringBuilder();
            for (Class<?> implementation : implementations) lines.append(implementation.getName()).append('\n');
            Files.writeString(services.resolve(PlatformAccessControl.class.getName()), lines, StandardCharsets.UTF_8);
            // A parent-less loader would not see the test classes; the parent below sees no services file of its
            // own for the port because the registration here is the only one read by name from this root first.
            return new IsolatedServicesLoader(new URL[]{root.toUri().toURL()}, getClass().getClassLoader());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Reads the port's services file from its own root only, so that the test classpath's registration is ignored. */
    private static final class IsolatedServicesLoader extends URLClassLoader {
        IsolatedServicesLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        public java.util.Enumeration<URL> getResources(String name) throws IOException {
            if (name.equals("META-INF/services/" + PlatformAccessControl.class.getName())) return findResources(name);
            return super.getResources(name);
        }
    }

    /** Records the operations it receives; public with a no-argument constructor for ServiceLoader. */
    public static final class RecordingAccessControl implements PlatformAccessControl {
        final List<String> calls = new ArrayList<>();

        public RecordingAccessControl() {
            // ServiceLoader needs a public constructor without arguments.
        }

        @Override
        public void protectFromInheritance(Path target) {
            calls.add("protect");
        }

        @Override
        public void requireNoConditionalDeny(Path target) {
            calls.add("read");
        }
    }

    /** A second implementation, for the refusal of an ambiguous registration. */
    public static final class OtherAccessControl implements PlatformAccessControl {
        public OtherAccessControl() {
            // ServiceLoader needs a public constructor without arguments.
        }

        @Override
        public void protectFromInheritance(Path target) {
            // Only its registration matters: two implementations must be refused.
        }

        @Override
        public void requireNoConditionalDeny(Path target) {
            // Only its registration matters: two implementations must be refused.
        }
    }

    /** A capability probe that answers like {@code base} and hands both Windows operations to {@code port}. */
    private record DelegatingProbe(PrivateLocalStorage.CapabilityProbe base, PlatformAccessControl port)
            implements PrivateLocalStorage.CapabilityProbe {
        @Override
        public boolean supportsPosix(Path target) {
            return base.supportsPosix(target);
        }

        @Override
        public java.nio.file.attribute.AclFileAttributeView aclView(Path target) {
            return base.aclView(target);
        }

        @Override
        public void protectFromInheritance(Path target) throws IOException {
            port.protectFromInheritance(target);
        }

        @Override
        public void requireNoConditionalDeny(Path target) throws IOException {
            port.requireNoConditionalDeny(target);
        }
    }

    /** The real POSIX answer of the file system the test runs on. */
    private static final class PosixProbe implements PrivateLocalStorage.CapabilityProbe {
        @Override
        public boolean supportsPosix(Path target) {
            return target.getFileSystem().supportedFileAttributeViews().contains("posix");
        }

        @Override
        public java.nio.file.attribute.AclFileAttributeView aclView(Path target) {
            return Files.getFileAttributeView(target, java.nio.file.attribute.AclFileAttributeView.class);
        }
    }

    @Test
    void theDiscoveredPortIsKeptForTheProcess() {
        assertSame(PrivateLocalStorage.platformAccessControl(), PrivateLocalStorage.platformAccessControl());
    }
}
