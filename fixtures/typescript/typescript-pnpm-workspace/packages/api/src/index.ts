export interface GreetingPort { greet(name: string): string; }
export class GreetingService implements GreetingPort {
  greet(name: string): string { return `Hello, ${name}`; }
}
