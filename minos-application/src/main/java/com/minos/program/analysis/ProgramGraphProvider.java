package com.minos.program.analysis;

import com.minos.program.ProgramGraph;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;

import java.io.IOException;

/** Extension point for provider-specific advanced program facts. */
public interface ProgramGraphProvider {
    String id();

    ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) throws IOException;
}
