package com.minos.explorer;

import com.minos.application.resolution.ProjectResolver;

/**
 * The closed list of the error codes of the API, contract version 1 (ARCH-0086). Every error answer carries one of
 * them in {@code error.code}; the page translates the code and never shows the English {@code error.message}. A code
 * is never renamed nor removed within version 1; a new code is added here, and the contract test and the page's
 * label table follow it.
 */
enum ExplorerErrorCode {
    BUSY,
    DOCUMENT_NOT_FOUND,
    INDEX_CHANGED,
    INTERNAL_ERROR,
    INVALID_PROJECT_REFERENCE,
    INVALID_REQUEST,
    METHOD_NOT_ALLOWED,
    NOT_FOUND,
    PROJECT_NOT_FOUND,
    PROJECT_REFERENCE_AMBIGUOUS,
    TIMEOUT,
    UNSUPPORTED_MEDIA_TYPE;

    /** The code of the same name as a project resolution failure; a resolver code without one is a defect. */
    static ExplorerErrorCode of(ProjectResolver.ErrorCode resolution) {
        return valueOf(resolution.name());
    }
}
