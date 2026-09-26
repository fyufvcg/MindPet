import { useCallback, useEffect, useState } from 'react'
import type { JSX } from 'react'
import {
  CheckCircle2,
  CircleAlert,
  Download,
  FileText,
  LoaderCircle,
  Mic,
  PackageOpen
} from 'lucide-react'

type OfficeRuntimeStatus = {
  installed: boolean
  installing: boolean
  supported: boolean
  progress: number
  detail: string
  error: string
  installPath: string
  pythonVersion: string
}

const initialStatus: OfficeRuntimeStatus = {
  installed: false,
  installing: false,
  supported: true,
  progress: 0,
  detail: '',
  error: '',
  installPath: '',
  pythonVersion: '3.11.9'
}

export function OfficeExtensionsPage(): JSX.Element {
  const [status, setStatus] = useState(initialStatus)
  const [checking, setChecking] = useState(true)
  const [runtimeApiAvailable, setRuntimeApiAvailable] = useState(true)

  const refreshStatus = useCallback(async (): Promise<void> => {
    if (typeof window.api?.getOfficeRuntimeStatus !== 'function') {
      setRuntimeApiAvailable(false)
      setStatus(current => ({ ...current, error: '扩展包接口尚未加载，请重启 MindPet 后再查看安装状态。' }))
      setChecking(false)
      return
    }
    try {
      setStatus(await window.api.getOfficeRuntimeStatus())
    } catch (error) {
      setRuntimeApiAvailable(false)
      setStatus(current => ({
        ...current,
        error: error instanceof Error ? error.message : '无法读取扩展包状态'
      }))
    } finally {
      setChecking(false)
    }
  }, [])

  useEffect(() => {
    const subscribe = window.api?.onOfficeRuntimeProgress
    const unsubscribe = typeof subscribe === 'function'
      ? subscribe(nextStatus => {
          setStatus(current => ({ ...current, ...nextStatus }))
          setChecking(false)
        })
      : () => undefined
    if (typeof subscribe !== 'function') setRuntimeApiAvailable(false)
    void refreshStatus()
    return unsubscribe
  }, [refreshStatus])

  const install = async (): Promise<void> => {
    if (typeof window.api?.installOfficeRuntime !== 'function') {
      setRuntimeApiAvailable(false)
      setStatus(current => ({ ...current, error: '扩展包安装接口尚未加载，请重启 MindPet 后重试。' }))
      return
    }
    setStatus(current => ({ ...current, installing: true, error: '', progress: Math.max(current.progress, 1), detail: '正在准备安装' }))
    try {
      setStatus(await window.api.installOfficeRuntime())
    } catch (error) {
      setStatus(current => ({
        ...current,
        installing: false,
        error: error instanceof Error ? error.message : '安装失败，请检查网络后重试'
      }))
    }
  }

  const isBusy = checking || status.installing
  const statusLabel = checking
    ? '正在检查'
    : status.installing
      ? '安装中'
      : status.installed
        ? '已安装'
        : status.supported
          ? '尚未安装'
          : '设备不支持'

  return (
    <div className="office-package-workspace">
      <div className="office-extensions-page">
      <section className="office-package-overview" aria-labelledby="office-package-title">
        <div className="office-package-heading">
          <div className="office-package-mark"><PackageOpen size={23} strokeWidth={1.8} aria-hidden="true" /></div>
          <div className="office-package-title-copy">
            <h2 id="office-package-title">Office 文档组件</h2>
            <p>为 PDF 文档转换和结构提取提供独立的本地运行环境。</p>
          </div>
          <span className={`office-package-status ${status.installed ? 'is-installed' : status.installing ? 'is-installing' : status.error ? 'has-error' : ''}`}>
            {status.installed ? <CheckCircle2 size={14} /> : status.error ? <CircleAlert size={14} /> : null}
            {statusLabel}
          </span>
        </div>

        <div className="office-package-install-row">
          <div className="office-package-install-info">
            <strong>{status.installing ? status.detail || '正在安装扩展包' : status.installed ? '文档处理能力已就绪' : '安装到 MindPet 独立目录'}</strong>
            <span>{status.installed ? `Python ${status.pythonVersion} · 可在聊天中使用文档工具` : '首次下载约 120–180 MB，安装后约占用 350–500 MB'}</span>
          </div>
          <button
            type="button"
            className="office-package-install-button"
            onClick={() => void install()}
            disabled={isBusy || status.installed || !status.supported || !runtimeApiAvailable}
          >
            {status.installing
              ? <><LoaderCircle className="office-package-spin" size={16} />安装中</>
              : status.installed
                ? <><CheckCircle2 size={16} />已安装</>
                : <><Download size={16} />一键安装</>}
          </button>
        </div>

        {status.installing && (
          <div className="office-package-progress" role="progressbar" aria-label="扩展包安装进度" aria-valuemin={0} aria-valuemax={100} aria-valuenow={status.progress}>
            <span style={{ transform: `scaleX(${Math.max(0.02, Math.min(1, status.progress / 100))})` }} />
          </div>
        )}
        {status.error && <p className="office-package-error" role="alert"><CircleAlert size={15} />{status.error}</p>}
        {!status.supported && <p className="office-package-error" role="status">此安装包当前支持 Windows x64。</p>}
      </section>

      <section className="office-package-section" aria-labelledby="office-package-includes">
        <h3 id="office-package-includes">扩展包包含</h3>
        <div className="office-package-details">
          <div className="office-package-detail-row"><span>独立运行环境</span><strong>嵌入式 Python {status.pythonVersion}</strong></div>
          <div className="office-package-detail-row"><span>PDF 转 Word</span><strong>pdf2docx 0.5.8 · PyMuPDF 1.24.10 · python-docx 1.1.2</strong></div>
          <div className="office-package-detail-row"><span>文档处理辅助库</span><strong>fonttools 4.54.1 · numpy 1.26.4 · OpenCV 4.10.0.84 · fire 0.7.1</strong></div>
        </div>
        <p className="office-package-note">组件仅安装在 MindPet 的应用数据目录，不会安装 Microsoft Office，也不会改动系统 Python。</p>
      </section>

      <section className="office-package-section" aria-labelledby="office-package-guide">
        <h3 id="office-package-guide">使用教程</h3>
        <ol className="office-package-guide-list">
          <li><span>1</span><p>点击“一键安装”，等待下载和依赖配置完成。安装期间请保持 MindPet 运行。</p></li>
          <li><span>2</span><p>回到聊天，上传 PDF 并说明目标，例如“把这份 PDF 转成可编辑的 Word 文档”。</p></li>
          <li><span>3</span><p>也可以要求提取 PDF 表格为 Excel，或将文档内容转换成文本格式；生成文件会出现在当前会话的文件区域。</p></li>
        </ol>
      </section>

      <section className="office-package-related" aria-label="与本地录音的关系">
        <div className="office-package-related-icon"><Mic size={17} aria-hidden="true" /></div>
        <p><strong>本地录音也会复用此 Python 环境。</strong> 麦克风采集依赖需在 AI 回忆录音页面单独安装，语音转写服务也需另行配置。</p>
      </section>

      <div className="office-package-path"><FileText size={14} aria-hidden="true" /><span>安装位置</span><code>{status.installPath || (checking ? '读取中…' : '尚未获取安装目录')}</code></div>
      </div>
    </div>
  )
}
