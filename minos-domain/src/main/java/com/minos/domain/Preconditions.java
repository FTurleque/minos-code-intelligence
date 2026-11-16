package com.minos.domain;

import com.minos.diagnostics.UsageException;

/**
 * Argument validations shared by every MINOS module.
 *
 * <p>This module is the one that every caller sees without a dependency forbidden by
 * {@code check-module-boundaries.py} (it depends on no other MINOS module): a validation written once here replaces
 * the private copies of each record and of each service.</p>
 */
public final class Preconditions {

    private Preconditions() {
    }

    /**
     * Refuses a missing or blank text.
     *
     * @param value the text to check
     * @param name the name of the field, used as is in the error message
     * @return {@code value}, unchanged (never trimmed)
     * @throws IllegalArgumentException if {@code value} is {@code null} or blank, with the message
     *         {@code "<name> must not be blank"}
     */
    public static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * As {@link #requireText}, for an input that the caller of a surface supplied: the failure is a
     * {@link UsageException}, reported to that caller. A blank value met inside the system (stored data, a
     * descriptor) stays a plain {@link IllegalArgumentException}: a defect, not advice to the caller.
     *
     * @throws UsageException if {@code value} is {@code null} or blank, with the message
     *         {@code "<name> must not be blank"}
     */
    public static String requireArgument(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new UsageException(name + " must not be blank");
        }
        return value;
    }
}
