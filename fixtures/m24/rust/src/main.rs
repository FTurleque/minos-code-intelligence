mod greeting {
    pub trait Greeter {
        fn greet(&self, name: &str) -> String;
    }

    pub struct EnglishGreeter;

    impl Greeter for EnglishGreeter {
        fn greet(&self, name: &str) -> String {
            format!("Hello {name}")
        }
    }
}

use greeting::{EnglishGreeter, Greeter};

fn main() {
    let greeter = EnglishGreeter;
    println!("{}", greeter.greet("MINOS"));
}
