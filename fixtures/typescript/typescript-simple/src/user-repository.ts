import type { User, UserId } from './user'

export interface UserRepository {
  findById(id: UserId): User | undefined
}
