import type { User, UserId } from './user'
import { UserStatus } from './user'
import type { UserRepository } from './user-repository'

export class InMemoryUserRepository implements UserRepository {
  private readonly users = new Map<UserId, User>([
    ['42', { id: '42', name: 'Ada', status: UserStatus.ACTIVE }],
  ])

  findById(id: UserId): User | undefined {
    return this.users.get(id)
  }
}
