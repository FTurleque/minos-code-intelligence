package com.minos.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Q13 — caractérise la validation des champs texte de {@link SnapshotDescriptor} avant la mutualisation de
 * {@code requireText} : {@code null} lève une {@link NullPointerException} (message = nom du champ), un texte
 * blanc une {@link IllegalArgumentException} {@code "<champ> must not be blank"}. Cette différence avec les autres
 * copies (où {@code null} donne aussi une {@link IllegalArgumentException}) est conservée.
 */
class SnapshotDescriptorValidationTest {

    @Test
    void aNullTextIsANullPointerNamingTheField() {
        NullPointerException snapshot = assertThrows(NullPointerException.class,
                () -> new SnapshotDescriptor(1, null, "file", "sha", 0, 0, 0));
        NullPointerException file = assertThrows(NullPointerException.class,
                () -> new SnapshotDescriptor(1, "id", null, "sha", 0, 0, 0));
        NullPointerException sha = assertThrows(NullPointerException.class,
                () -> new SnapshotDescriptor(1, "id", "file", null, 0, 0, 0));

        assertEquals("snapshotId", snapshot.getMessage());
        assertEquals("fileName", file.getMessage());
        assertEquals("sha256", sha.getMessage());
    }

    @Test
    void aBlankTextIsAnIllegalArgumentNamingTheField() {
        IllegalArgumentException snapshot = assertThrows(IllegalArgumentException.class,
                () -> new SnapshotDescriptor(1, " ", "file", "sha", 0, 0, 0));
        IllegalArgumentException file = assertThrows(IllegalArgumentException.class,
                () -> new SnapshotDescriptor(1, "id", "", "sha", 0, 0, 0));
        IllegalArgumentException sha = assertThrows(IllegalArgumentException.class,
                () -> new SnapshotDescriptor(1, "id", "file", "\t", 0, 0, 0));

        assertEquals("snapshotId must not be blank", snapshot.getMessage());
        assertEquals("fileName must not be blank", file.getMessage());
        assertEquals("sha256 must not be blank", sha.getMessage());
    }

    @Test
    void aValidDescriptorKeepsItsTextUntouched() {
        SnapshotDescriptor descriptor = new SnapshotDescriptor(1, " id ", " file ", " sha ", 1, 2, 3);

        assertEquals(" id ", descriptor.snapshotId());
        assertEquals(" file ", descriptor.fileName());
        assertEquals(" sha ", descriptor.sha256());
    }
}
