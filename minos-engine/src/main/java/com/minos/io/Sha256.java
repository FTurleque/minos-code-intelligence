package com.minos.io;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Empreinte SHA-256, en un seul endroit (Q13).
 *
 * <p>SHA-256 est exigé de toute JVM ; son absence est un environnement cassé et lève une
 * {@link IllegalStateException}. Les calculs incrémentaux (fichiers bornés, empreintes de projet)
 * obtiennent leur {@link MessageDigest} ici et écrivent eux-mêmes la boucle de lecture, qui porte
 * leurs propres bornes ; les empreintes de texte ou d'octets passent par {@link #hex(String)} et
 * {@link #hex(byte[])}.</p>
 *
 * <p>Lieu : {@code minos-engine}, module que voient tous les appelants sans dépendance interdite par
 * {@code check-module-boundaries.py} ; le domaine, sans appelant, reste minimal.</p>
 */
public final class Sha256 {

    private Sha256() {
    }

    /** Un nouvel algorithme de hachage SHA-256, jamais partagé. */
    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Empreinte, en hexadécimal minuscule, du texte encodé en UTF-8. */
    public static String hex(String value) {
        return hex(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    /** Empreinte, en hexadécimal minuscule, des octets donnés. */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(Objects.requireNonNull(bytes, "bytes")));
    }

    /** Termine {@code digest} (qui est remis à zéro) et rend son empreinte en hexadécimal minuscule. */
    public static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(Objects.requireNonNull(digest, "digest").digest());
    }
}
