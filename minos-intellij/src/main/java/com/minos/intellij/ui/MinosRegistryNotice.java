package com.minos.intellij.ui;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;

/**
 * A discreet balloon telling the user that MINOS skipped unreadable registry entries while resolving the open project
 * (the partial result of {@code minos project list}, exit 3). The project still works; the user should know the
 * registry is damaged rather than discover it later.
 */
public final class MinosRegistryNotice {

    static final String GROUP_ID = "MINOS";

    private MinosRegistryNotice() { }

    /** {@code unreadable} is the CLI's own wording, for instance « 1 registry entry is unreadable ». */
    public static void show(Project project, String unreadable) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
                .createNotification("MINOS: " + unreadable + ". The project works; run `minos project list` to see which.",
                        NotificationType.WARNING)
                .notify(project);
    }
}
