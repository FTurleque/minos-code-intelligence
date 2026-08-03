package com.minos.storage.postgresql;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Path;

final class PostgresJsonCodec {
    private final ObjectMapper mapper;

    PostgresJsonCodec() {
        SimpleModule paths = new SimpleModule();
        paths.addSerializer(Path.class, new PathSerializer());
        paths.addDeserializer(Path.class, new PathDeserializer());
        mapper = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .registerModule(paths);
    }

    String write(Object value) throws IOException { return mapper.writeValueAsString(value); }
    <T> T read(String json, Class<T> type) throws IOException { return mapper.readValue(json, type); }
    JsonNode tree(String json) throws IOException { return mapper.readTree(json); }

    private static final class PathSerializer extends StdSerializer<Path> {
        PathSerializer() { super(Path.class); }
        @Override public void serialize(Path value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString(value.toString().replace('\\', '/'));
        }
    }

    private static final class PathDeserializer extends StdDeserializer<Path> {
        PathDeserializer() { super(Path.class); }
        @Override public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return Path.of(parser.getValueAsString());
        }
    }
}
