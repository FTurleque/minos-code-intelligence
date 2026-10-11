package com.minos.integration.git;

import org.eclipse.jgit.attributes.FilterCommand;
import org.eclipse.jgit.attributes.FilterCommandRegistry;
import org.eclipse.jgit.lib.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Built-in filter that copies its input unchanged. A repository's own {@code filter.*} drivers are
 * pointed at it so that no command the repository declares is ever run, while the working-tree
 * comparison is still computed.
 */
final class PassThroughFilter extends FilterCommand {

    /** Registry key; the built-in prefix keeps it from ever being taken for a shell command. */
    static final String COMMAND = Constants.BUILTIN_FILTER_PREFIX + "minos/pass-through";

    private static final int BUFFER_SIZE = 8192;

    private final byte[] buffer = new byte[BUFFER_SIZE];

    private PassThroughFilter(InputStream in, OutputStream out) {
        super(in, out);
    }

    /** Idempotent: the registry is process-wide and the key is owned by MINOS. */
    static synchronized void register() {
        if (!FilterCommandRegistry.isRegistered(COMMAND)) {
            FilterCommandRegistry.register(COMMAND, (repository, in, out) -> new PassThroughFilter(in, out));
        }
    }

    @Override
    public int run() throws IOException {
        int read = in.read(buffer);
        if (read < 0) {
            in.close();
            out.close();
            return -1;
        }
        out.write(buffer, 0, read);
        return read;
    }
}
