import type { RpaRunRepository } from './run-repository'
import { HttpRpaRunRepository } from './http-run-repository'

let repository: HttpRpaRunRepository | null = null

export function getRpaRunRepository(): RpaRunRepository {
  if (!repository) {
    repository = new HttpRpaRunRepository()
  }
  return repository
}

export async function closeAllRpaRepositories(): Promise<void> {
  if (repository) {
    await repository.close()
    repository = null
  }
}
