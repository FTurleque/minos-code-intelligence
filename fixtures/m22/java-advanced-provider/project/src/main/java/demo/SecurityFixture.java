package demo;

final class SecurityFixture {
    static String source() {
        return "input";
    }

    static String sanitize(String value) {
        return value;
    }

    static void sink(String value) {
    }

    static void run() {
        String raw = source();
        String clean = sanitize(raw);
        sink(clean);
    }
}
