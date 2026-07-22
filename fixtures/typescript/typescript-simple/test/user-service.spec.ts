import { InMemoryUserRepository } from '../src/in-memory-user-repository'
import { getUserName } from '../src/user-resource'
import { UserService } from '../src/user-service'

export function findsExistingUser(): void {
  const service = new UserService(new InMemoryUserRepository())
  const actual = getUserName(service, '42')

  if (actual !== 'Ada') {
    throw new Error(`Expected Ada, got ${actual}`)
  }
}

findsExistingUser()
