package com.minos.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Protège en écriture une arborescence qui existe déjà, pour les tests d'un chemin de <strong>lecture</strong> qui ne
 * doit rien écrire (un répertoire de données monté en lecture seule dans un conteneur, par exemple).
 *
 * <p>Sous Windows, chaque répertoire et chaque fichier reçoit une entrée ACL de refus d'écriture pour l'utilisateur
 * courant : ouvrir un fichier en écriture ou créer une entrée échoue réellement, et le durcissement de
 * {@code PrivateLocalStorage} conserve cette entrée. Sous POSIX, aucune permission ne résiste à son propriétaire (le
 * durcissement remet {@code 0600}, root ignore les permissions) : les fichiers passent en {@code 0400} et c'est
 * {@link #state()} qui prouve, par comparaison avant/après, qu'aucun fichier n'a été ré-ouvert aux écritures, créé,
 * supprimé ni modifié. Un vrai système de fichiers en lecture seule ne se monte pas depuis un test : cette preuve-là
 * est faite par la qualification de l'image Docker.</p>
 *
 * <p>La restauration est dans {@link #close()} : sans elle {@code @TempDir} ne pourrait pas nettoyer.</p>
 */
public final class WriteProtectedTree implements AutoCloseable {

    private static final Set<PosixFilePermission> READ_ONLY_FILE = PosixFilePermissions.fromString("r--------");

    private final Path root;
    private final Map<Path, Set<PosixFilePermission>> posixBefore;
    private final Map<Path, List<AclEntry>> aclBefore;

    private WriteProtectedTree(
            Path root,
            Map<Path, Set<PosixFilePermission>> posixBefore,
            Map<Path, List<AclEntry>> aclBefore
    ) {
        this.root = root;
        this.posixBefore = posixBefore;
        this.aclBefore = aclBefore;
    }

    /** Protège en écriture {@code root} et tout ce qu'il contient à cet instant. */
    public static WriteProtectedTree protect(Path root) throws IOException {
        Map<Path, Set<PosixFilePermission>> posixBefore = new LinkedHashMap<>();
        Map<Path, List<AclEntry>> aclBefore = new LinkedHashMap<>();
        for (Path path : entries(root)) {
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                posixBefore.put(path, posix.readAttributes().permissions());
                posix.setPermissions(READ_ONLY_FILE);
                continue;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(
                    path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("the test filesystem has neither POSIX permissions nor ACLs");
            List<AclEntry> before = List.copyOf(acl.getAcl());
            List<AclEntry> entries = new ArrayList<>(before);
            entries.addFirst(AclEntry.newBuilder()
                    .setType(AclEntryType.DENY)
                    .setPrincipal(currentUser(path, acl))
                    .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
                            AclEntryPermission.DELETE_CHILD)
                    .build());
            acl.setAcl(entries);
            aclBefore.put(path, before);
        }
        return new WriteProtectedTree(root, posixBefore, aclBefore);
    }

    /**
     * Ce qu'une lecture ne doit pas changer, par chemin relatif : taille, date de modification, et permissions POSIX
     * ou présence du refus d'écriture. Deux appels qui encadrent une lecture doivent rendre la même valeur.
     */
    public Map<String, String> state() throws IOException {
        Map<String, String> state = new TreeMap<>();
        for (Path path : entries(root)) {
            StringBuilder value = new StringBuilder();
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                value.append(Files.size(path)).append('@')
                        .append(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()).append(' ');
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                value.append(PosixFilePermissions.toString(posix.readAttributes().permissions()));
            } else {
                AclFileAttributeView acl = Files.getFileAttributeView(
                        path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                boolean denied = acl.getAcl().stream().anyMatch(entry -> entry.type() == AclEntryType.DENY
                        && entry.permissions().contains(AclEntryPermission.WRITE_DATA));
                value.append(denied ? "write-denied" : "writable");
            }
            state.put(root.relativize(path).toString().replace('\\', '/'), value.toString());
        }
        return state;
    }

    private static List<Path> entries(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.toList();
        }
    }

    private static UserPrincipal currentUser(Path path, AclFileAttributeView acl) throws IOException {
        try {
            return path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
        } catch (IOException unknown) {
            return acl.getOwner();
        }
    }

    @Override
    public void close() throws IOException {
        for (Map.Entry<Path, Set<PosixFilePermission>> entry : posixBefore.entrySet()) {
            if (!Files.exists(entry.getKey(), LinkOption.NOFOLLOW_LINKS)) continue;
            Files.getFileAttributeView(entry.getKey(), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .setPermissions(entry.getValue());
        }
        for (Map.Entry<Path, List<AclEntry>> entry : aclBefore.entrySet()) {
            if (!Files.exists(entry.getKey(), LinkOption.NOFOLLOW_LINKS)) continue;
            Files.getFileAttributeView(entry.getKey(), AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .setAcl(entry.getValue());
        }
    }
}
