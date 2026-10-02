import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'

// Only the endpoints keel-server has not implemented yet are mocked; everything else goes to the real backend.
export const worker = setupWorker(...handlers)
