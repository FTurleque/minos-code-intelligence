package com.minos.runtime.local.wsl;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.io.BoundedProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request of protocol {@value #PROTOCOL}, written by the Windows installation and read by the internal worker
 * command run in the WSL2 distribution by the same jar.
 *
 * <p>It names the project by its path inside the distribution ({@code /mnt/<drive>/…}), never by a Windows path,
 * and carries no secret. The protocol is internal: both sides run the same jar, so an unknown protocol or an extra
 * field is refused rather than interpreted.</p>
 *
 * @param projectRoot         absolute path of the registered project root inside the distribution
 * @param projectRelativeRoot scope inside the project, {@code ""} for the root, with {@code '/'} separators
 * @param projectFingerprint  SHA-256 fingerprint of the project captured by the Windows installation
 * @param workerId            identity the manifest must carry, {@code wsl:<distribution>}
 */
record WslWorkerRequest(
        UUID runId,
        UUID projectId,
        String projectRoot,
        String projectRelativeRoot,
        Language language,
        String providerId,
        String providerVersion,
        String projectFingerprint,
        String workerId
) {

    static final String PROTOCOL = "minos-wsl-worker-v1";
    /** Name of the request file in the exchange directory. */
    static final String FILE_NAME = "request.properties";
    /** Name of the bundle the worker writes next to the request. */
    static final String BUNDLE_NAME = "bundle.zip";

    private static final long MAX_BYTES = 16L * 1024L;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE_TEXT = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private static final String KEY_PROTOCOL = "protocol";
    private static final String KEY_RUN_ID = "runId";
    private static final String KEY_PROJECT_ID = "projectId";
    private static final String KEY_PROJECT_ROOT = "projectRoot";
    private static final String KEY_PROJECT_RELATIVE_ROOT = "projectRelativeRoot";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_PROVIDER_ID = "providerId";
    private static final String KEY_PROVIDER_VERSION = "providerVersion";
    private static final String KEY_PROJECT_FINGERPRINT = "projectFingerprint";
    private static final String KEY_WORKER_ID = "workerId";
    private static final Set<String> KEYS = Set.of(KEY_PROTOCOL, KEY_RUN_ID, KEY_PROJECT_ID, KEY_PROJECT_ROOT,
            KEY_PROJECT_RELATIVE_ROOT, KEY_LANGUAGE, KEY_PROVIDER_ID, KEY_PROVIDER_VERSION, KEY_PROJECT_FINGERPRINT,
            KEY_WORKER_ID);

    WslWorkerRequest {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(projectId, "projectId");
        projectRoot = WslPaths.requireDistributionPath(projectRoot);
        projectRelativeRoot = WslPaths.requireRelativeScope(projectRelativeRoot);
        Objects.requireNonNull(language, "language");
        providerId = requireSafe(providerId, "providerId");
        providerVersion = requireSafe(providerVersion, "providerVersion");
        if (projectFingerprint == null || !SHA256.matcher(projectFingerprint).matches()) {
            throw new IllegalArgumentException("projectFingerprint must be a lowercase SHA-256 value");
        }
        workerId = requireSafe(workerId, "workerId");
        if (!workerId.startsWith(WslIndexWorker.WORKER_ID_PREFIX)) {
            throw new IllegalArgumentException("workerId must name a WSL distribution");
        }
    }

    byte[] encode() throws IOException {
        Properties properties = new Properties();
        properties.setProperty(KEY_PROTOCOL, PROTOCOL);
        properties.setProperty(KEY_RUN_ID, runId.toString());
        properties.setProperty(KEY_PROJECT_ID, projectId.toString());
        properties.setProperty(KEY_PROJECT_ROOT, projectRoot);
        properties.setProperty(KEY_PROJECT_RELATIVE_ROOT, projectRelativeRoot);
        properties.setProperty(KEY_LANGUAGE, language.name());
        properties.setProperty(KEY_PROVIDER_ID, providerId);
        properties.setProperty(KEY_PROVIDER_VERSION, providerVersion);
        properties.setProperty(KEY_PROJECT_FINGERPRINT, projectFingerprint);
        properties.setProperty(KEY_WORKER_ID, workerId);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            properties.store(writer, "MINOS WSL worker request - no secrets");
        }
        return output.toByteArray();
    }

    /**
     * Decodes a request; an unknown protocol, a missing or extra field, or an invalid value is refused.
     *
     * @throws IOException with a fixed message that names no path
     */
    static WslWorkerRequest decode(byte[] bytes) throws IOException {
        Properties properties = BoundedProperties.loadUtf8(bytes, MAX_BYTES, 16, 64, 4_096, "WSL worker request");
        String protocol = properties.getProperty(KEY_PROTOCOL);
        if (!PROTOCOL.equals(protocol)) {
            throw new IOException("unsupported WSL worker protocol; expected " + PROTOCOL);
        }
        if (!properties.stringPropertyNames().equals(KEYS)) {
            throw new IOException("WSL worker request fields do not match protocol " + PROTOCOL);
        }
        try {
            return new WslWorkerRequest(
                    UUID.fromString(properties.getProperty(KEY_RUN_ID)),
                    UUID.fromString(properties.getProperty(KEY_PROJECT_ID)),
                    properties.getProperty(KEY_PROJECT_ROOT),
                    properties.getProperty(KEY_PROJECT_RELATIVE_ROOT),
                    Language.valueOf(properties.getProperty(KEY_LANGUAGE)),
                    properties.getProperty(KEY_PROVIDER_ID),
                    properties.getProperty(KEY_PROVIDER_VERSION),
                    properties.getProperty(KEY_PROJECT_FINGERPRINT),
                    properties.getProperty(KEY_WORKER_ID));
        } catch (RuntimeException invalid) {
            throw new IOException("WSL worker request is invalid", invalid);
        }
    }

    private static String requireSafe(String value, String label) {
        if (value == null || !SAFE_TEXT.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a short identifier");
        }
        return value;
    }
}
