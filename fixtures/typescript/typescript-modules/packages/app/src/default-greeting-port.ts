import { GreetingBase, GreetingPort } from '@minos/greeting-api'

export class DefaultGreetingPort extends GreetingBase implements GreetingPort {
  override greet(name: string): string {
    return `Hello ${this.normalize(name)}`
  }
}
