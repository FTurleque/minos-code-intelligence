package demo;

final class InterproceduralFixture {
    static String callee(String value) {
        return value;
    }

    static String caller(String input) {
        return callee(input);
    }
}
