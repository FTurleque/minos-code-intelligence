import { MissingClient } from '@missing/client'

export interface Adapter {
  execute(input: string): string
}

export class BrokenAdapter implements Adapter {
  constructor(private readonly client: MissingClient) {}

  execute(input: string): string {
    return this.client.transform(input)
  }
}

export function createBrokenAdapter(): BrokenAdapter {
  return new BrokenAdapter(new MissingClient())
}
