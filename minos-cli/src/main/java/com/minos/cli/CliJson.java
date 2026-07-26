package com.minos.cli;

import com.minos.output.DeterministicJson;

/** CLI compatibility facade over the shared deterministic JSON renderer. */
final class CliJson {

    private CliJson() {
    }

    static String render(Object value) {
        return DeterministicJson.render(value);
    }

    static void quote(StringBuilder builder, String value) {
        DeterministicJson.quote(builder, value);
    }
}
