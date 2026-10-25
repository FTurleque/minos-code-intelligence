package com.minos.runtime.local.sandbox;

import com.minos.runtime.local.acl.WindowsIcacls;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The grants the Windows sandbox backend gives its container identity, through the single {@code icacls} launcher
 * of the local runtime ({@link WindowsIcacls}).
 *
 * <p>A grant is additive ({@code /grant}, never {@code /grant:r}, never a whole-DACL rewrite) and names one
 * identity by SID; a removal ({@code /remove:g}) touches the grants of that one identity only. Both leave every
 * other entry of the DACL as it is, restrictions an administrator put there included. Whatever goes wrong -- a
 * target that is not an absolute path, no Windows directory, an identity or rights that are not what they claim, a
 * process that cannot start, does not end in time, is interrupted or exits non-zero -- the answer is
 * {@code false}, "not granted", and the caller fails.</p>
 */
final class WindowsAclGrants {

    static final Duration DEFAULT_TIMEOUT = WindowsIcacls.DEFAULT_TIMEOUT;

    // Bounded character classes, no nested repetition: a SID is digits and dashes after "S-1-", and rights are
    // letters, commas and parentheses ("(OI)(CI)F", "(RX)"). Neither can carry a space, a slash or a quote.
    private static final Pattern SID = Pattern.compile("S-1-\\d[\\d-]{0,79}");
    private static final Pattern RIGHTS = Pattern.compile("[A-Z(),]{1,32}");

    private static final WindowsAclGrants SYSTEM = new WindowsAclGrants(WindowsIcacls.system());

    private final WindowsIcacls icacls;

    WindowsAclGrants(WindowsIcacls icacls) {
        this.icacls = Objects.requireNonNull(icacls, "icacls");
    }

    WindowsAclGrants(Supplier<String> systemRoot, WindowsIcacls.Launcher launcher, Duration timeout) {
        this(new WindowsIcacls(systemRoot, launcher, timeout));
    }

    static WindowsAclGrants system() {
        return SYSTEM;
    }

    /** Adds {@code rights} for the identity {@code sid} on {@code target}; {@code false} if not granted. */
    boolean grant(Path target, String sid, String rights) {
        if (!SID.matcher(String.valueOf(sid)).matches() || !RIGHTS.matcher(String.valueOf(rights)).matches()) {
            return false;
        }
        return run(target, "/grant", "*" + sid + ":" + rights);
    }

    /** Removes the grants of the identity {@code sid} on {@code target}; {@code false} if not done. */
    boolean remove(Path target, String sid) {
        if (!SID.matcher(String.valueOf(sid)).matches()) return false;
        return run(target, "/remove:g", "*" + sid);
    }

    private boolean run(Path target, String operation, String subject) {
        try {
            icacls.run(Objects.requireNonNull(target, "target"), List.of(operation, subject, "/q"),
                    "cannot change a sandbox grant");
            return true;
        } catch (IOException _) {
            return false;
        }
    }
}
