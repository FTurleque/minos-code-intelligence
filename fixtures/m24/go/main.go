package main

import "fmt"

type Greeter interface {
	Greet(name string) string
}

type englishGreeter struct{}

func (englishGreeter) Greet(name string) string {
	return "Hello " + name
}

func main() {
	var greeter Greeter = englishGreeter{}
	fmt.Println(greeter.Greet("MINOS"))
}
