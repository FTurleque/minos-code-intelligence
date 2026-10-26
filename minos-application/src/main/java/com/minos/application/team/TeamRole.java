package com.minos.application.team;

/**
 * Role of a team member, with the same values as the closed role set of the hosted control plane. Permissions are
 * decided by the engine; a member's permissions are read from {@link TeamMember#permissions()}.
 */
public enum TeamRole {
    OWNER,
    ADMIN,
    CONTRIBUTOR,
    VIEWER,
    AUDITOR
}
