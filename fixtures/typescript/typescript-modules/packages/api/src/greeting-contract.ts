export interface GreetingPort {
  greet(name: string): string
}

export abstract class GreetingBase {
  abstract greet(name: string): string

  protected normalize(name: string): string {
    return name.trim()
  }
}
