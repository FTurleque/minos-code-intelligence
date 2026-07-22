import type { User, UserId } from './user'
import type { UserRepository } from './user-repository'

export class UserService {
  constructor(private readonly repository: UserRepository) {}

  findUser(id: UserId): User | undefined {
    return this.repository.findById(id)
  }
}
