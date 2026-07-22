export type UserId = string

export enum UserStatus {
  ACTIVE = 'ACTIVE',
  DISABLED = 'DISABLED',
}

export interface User {
  readonly id: UserId
  readonly name: string
  readonly status: UserStatus
}
