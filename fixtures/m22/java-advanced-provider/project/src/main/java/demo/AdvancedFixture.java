package demo;

final class AdvancedFixture {

    static String source() {
        return "input";
    }

    static String sanitize(String value) {
        return value;
    }

    static void sink(String value) {
    }

    static String callee(String value) {
        String copy = value;
        return copy;
    }

    static String linearFlow(String input) {
        String copy = input;
        return copy;
    }

    static int controlFlow(boolean flag) {
        int value = 0;
        if (flag) {
            value = 1;
        } else {
            value = 2;
        }
        while (value < 3) {
            value = value + 1;
        }
        return value;
    }

    static String interprocedural(String input) {
        return callee(input);
    }

    static void security() {
        String raw = source();
        String clean = sanitize(raw);
        sink(clean);
    }
}
