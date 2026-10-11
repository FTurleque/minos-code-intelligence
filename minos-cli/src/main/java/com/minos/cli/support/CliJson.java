package com.minos.cli.support;

import com.minos.output.json.DeterministicJson;

/** CLI compatibility facade over the shared deterministic JSON renderer. */
public final class CliJson {

    private CliJson() {
    }

    public static String render(Object value) {
        return DeterministicJson.render(value);
    }
}
