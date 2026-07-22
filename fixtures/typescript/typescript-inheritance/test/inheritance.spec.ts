import { AdminEntity } from '../src/admin-entity'
import { describeEntity } from '../src/describe-entity'

export function verifiesInheritance(): void {
  const entity = new AdminEntity('42', 'Ada')
  if (describeEntity(entity) !== 'Admin Ada') {
    throw new Error('Unexpected entity description')
  }
}

verifiesInheritance()
