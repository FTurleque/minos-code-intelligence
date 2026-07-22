import { DefaultGreetingPort } from '../src/default-greeting-port'
import { GreetingService } from '../src/greeting-service'

export function verifiesCrossProjectOverloads(): void {
  const service = new GreetingService(new DefaultGreetingPort())
  const single = service.greet(' Ada ')
  const multiple = service.greet(['Ada', 'Bob'])

  if (single !== 'Hello Ada' || multiple.join(',') !== 'Hello Ada,Hello Bob') {
    throw new Error('Unexpected greeting results')
  }
}

verifiesCrossProjectOverloads()
