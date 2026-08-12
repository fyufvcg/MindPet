import { resolve } from 'path'

/**
 * 默认数据存储根目录。
 * __dirname 在 src/main/ 下，../../.. 回到 MINDPET 仓库根。
 * 运行时会被编译到 out/main/，层级关系保持不变。
 */
export function getDefaultDataDir(): string {
  return resolve(__dirname, '..', '..', '..', 'data')
}
