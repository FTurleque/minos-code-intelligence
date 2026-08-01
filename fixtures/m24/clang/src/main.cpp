#include "greeting.h"

#include <string>

namespace minos::m24 {
class Greeter {
public:
    std::string greet(const std::string &name) const {
        return m24_greeting(name.c_str());
    }
};
}

int main() {
    minos::m24::Greeter greeter;
    return greeter.greet("polyglot").empty() ? 1 : 0;
}
