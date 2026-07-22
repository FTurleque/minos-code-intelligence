import { UserEntity } from './user-entity'

export class AdminEntity extends UserEntity {
  override describe(): string {
    return `Admin ${super.describe()}`
  }
}
