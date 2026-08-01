#include "greeting.h"

const char *m24_greeting(const char *name) {
    return name == 0 ? "MINOS" : name;
}
