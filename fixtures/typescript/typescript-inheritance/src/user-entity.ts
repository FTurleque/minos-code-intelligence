import { Named } from './contracts'
import { EntityBase } from './entity-base'

export class UserEntity extends EntityBase implements Named {
  constructor(id: string, public readonly name: string) {
    super(id)
  }

  override describe(): string {
    return this.name
  }
}
