import { GreetingPort } from '@minos/greeting-api'

export class GreetingService {
  constructor(private readonly port: GreetingPort) {}

  greet(name: string): string
  greet(names: readonly string[]): string[]
  greet(input: string | readonly string[]): string | string[] {
    if (typeof input === 'string') {
      return this.port.greet(input)
    }
    return input.map(name => this.port.greet(name))
  }
}
