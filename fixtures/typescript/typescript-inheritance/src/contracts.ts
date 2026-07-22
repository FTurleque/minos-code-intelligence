export interface Identified {
  readonly id: string
}

export interface Named extends Identified {
  readonly name: string
}
