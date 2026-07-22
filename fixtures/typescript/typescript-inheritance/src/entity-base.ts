import { Identified } from './contracts'

export abstract class EntityBase implements Identified {
  constructor(public readonly id: string) {}

  abstract describe(): string
}
