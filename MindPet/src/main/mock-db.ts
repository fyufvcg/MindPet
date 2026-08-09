// SQLite 原生模块在 Electron 中不可用，返回空操作 stub
// 会话和记忆数据全部走后端 Redis/PostgreSQL
export function open(_opts?: any): any {
  return {
    run: async (..._args: any[]): Promise<any> => ({ changes: 0 }),
    get: async (..._args: any[]): Promise<any> => undefined,
    all: async (..._args: any[]): Promise<any[]> => [],
    exec: async (..._args: any[]): Promise<void> => {},
    close: async (): Promise<void> => {},
    config: { filename: '' }
  }
}
