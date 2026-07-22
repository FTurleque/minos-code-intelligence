import { EntityBase } from './entity-base'

export function describeEntity(entity: EntityBase): string {
  return entity.describe()
}
