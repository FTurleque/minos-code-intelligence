package demo;

final class CfgFixture {
    static void run(boolean flag) {
        first();
        if (flag) {
            second();
        } else {
            third();
        }
        while (flag) {
            fourth();
        }
        fifth();
    }

    static void first() {}
    static void second() {}
    static void third() {}
    static void fourth() {}
    static void fifth() {}
}
