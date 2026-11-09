package com.minos.runtime.local.sandbox;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The provider sees its own job cgroup, read-only, where a JVM looks for its container limits: it then sizes its heap
 * and its threads on the job instead of the host, without any option or variable that would print a banner.
 */
class LinuxSandboxJobCgroupViewTest {

    @Test
    void theJobCgroupIsBoundReadOnlyWhereAJvmReadsItsContainerLimits() {
        Path job = Path.of("/sys/fs/cgroup/minos.slice/minos-controller/minos-run");

        assertEquals(List.of("--ro-bind", job.toString(), "/sys/fs/cgroup"),
                LinuxBubblewrapWorkerSandboxBackend.jobCgroupView(job));
        assertEquals("/sys/fs/cgroup", LinuxBubblewrapWorkerSandboxBackend.JOB_CGROUP_VIEW);
    }
}
