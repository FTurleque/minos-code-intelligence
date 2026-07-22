import type { UserId } from './user'
import type { UserService } from './user-service'

export function getUserName(service: UserService, id: UserId): string {
  return service.findUser(id)?.name ?? 'unknown'
}
