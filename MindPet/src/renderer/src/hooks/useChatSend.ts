/* eslint-disable @typescript-eslint/no-explicit-any */
import { useCallback } from 'react'
import type { MutableRefObject } from 'react'
import { formatDateTime } from '../utils/helpers'

interface ChatSendState {
  sessions: any[]
  activeSessionId: string
  inputValue: string
  attachedFiles: any[]
  sendingSessionIds: Record<string, boolean>
  llmConfig: any
  contextRounds: number
  avatarList: any[]
  customModelDir: string
  customModelFile: string
  ttsEnabled: boolean
}

interface ChatSendOptions {
  getState: () => ChatSendState
  workspacePath: string
  setShowApiKeyModal: (show: boolean) => void
  setSessions: (updater: any[] | ((sessions: any[]) => any[])) => void
  setInputValue: (value: string) => void
  setAttachedFiles: (files: any[]) => void
  setSendingSessionIds: (updater: (sending: Record<string, boolean>) => Record<string, boolean>) => void
  abortedReplyIdsRef: MutableRefObject<Set<number>>
  finalizeReply: (replyId: number, text: string, sessionId: string, onComplete: () => void) => void
  failReply: (replyId: number, sessionId: string, error: unknown) => void
  triggerSessionSummary: (sessionId: string, sessions: any[]) => Promise<void>
}

function getAvatar(state: ChatSendState): { name: string; style: string; voice: string } {
  const avatar = state.avatarList.find(item => state.customModelDir ? item.dir === state.customModelDir : item.isDefault)
  return {
    name: avatar?.name || (state.customModelFile ? state.customModelFile.replace(/\.model3\.json$/i, '') : 'MindPet'),
    style: avatar?.languageStyle || 'normal',
    voice: avatar?.voice || 'zh-CN-XiaoxiaoNeural'
  }
}

function toLlmMessage(message: any): { role: string; content: any } {
  let textContent = message.text || ''
  if (message.sender === 'user' && message.isSteering) {
    textContent = `【用户追加指引：请立即优先遵循，并据此调整当前任务】\n${textContent}`
  }
  const imageBlocks: any[] = []

  if (message.fileInfo) {
    const pathNote = message.fileInfo.path ? `\n[源文件路径: ${message.fileInfo.path}]` : ''
    const previewNotice = /\.pdf$/i.test(message.fileInfo.name || '') && message.fileInfo.content
      ? '\n[附件文本预读：仅供检索、总结和理解内容；不包含可靠的字体、段落样式、坐标、分页、表格边界或图片布局，不能作为 PDF→DOCX/PPTX 转换源。]'
      : ''
    textContent = `${textContent}\n\n--- [附带文件: ${message.fileInfo.name}]${pathNote}${previewNotice}\n${message.fileInfo.content}`
  } else if (message.fileInfos?.length) {
    const attachmentsText = message.fileInfos
      .filter((file: any) => file.content || file.path)
      .map((file: any) => {
        const pathNote = file.path ? `\n[源文件路径: ${file.path}]` : ''
        const previewNotice = /\.pdf$/i.test(file.name || '') && file.content
          ? '\n[附件文本预读：仅供检索、总结和理解内容；不包含可靠的字体、段落样式、坐标、分页、表格边界或图片布局，不能作为 PDF→DOCX/PPTX 转换源。]'
          : ''
        const content = file.content ? `\n${file.content}` : ''
        return `--- [附带文件: ${file.name}]${pathNote}${previewNotice}${content}`
      })
      .join('\n\n')
    if (attachmentsText) textContent = `${textContent}\n\n${attachmentsText}`

    for (const file of message.fileInfos) {
      if (!file.content && file.path && (/\.(jpg|jpeg|png|gif|webp)$/i.test(file.name) || file.objectUrl)) {
        imageBlocks.push({
          type: 'image_url',
          image_url: { url: `local-file:///${file.path.replace(/\\/g, '/')}` }
        })
      }
    }
  }

  const role = message.sender === 'user' ? 'user' : 'assistant'
  if (imageBlocks.length === 0) return { role, content: textContent }
  return {
    role,
    content: [
      ...(textContent ? [{ type: 'text', text: textContent }] : []),
      ...imageBlocks
    ]
  }
}

/** Owns the complete "create messages -> call LLM -> settle reply" pipeline. */
export function useChatSend({
  getState,
  workspacePath,
  setShowApiKeyModal,
  setSessions,
  setInputValue,
  setAttachedFiles,
  setSendingSessionIds,
  abortedReplyIdsRef,
  finalizeReply,
  failReply,
  triggerSessionSummary
}: ChatSendOptions): { handleSendChat: () => Promise<void> } {
  const handleSendChat = useCallback(async (): Promise<void> => {
    const state = getState() as any
    const sessionId = state.activeSessionId
    const attachedFiles = [...state.attachedFiles]
    const text = state.inputValue.trim()
    if (!text && attachedFiles.length === 0) return
    // A send while the model is working is a steering instruction. The main process
    // replaces the active request for this session with one that includes this message.
    const isSteering = Boolean(state.sendingSessionIds[sessionId])

    const llmConfig = { ...state.llmConfig }
    const avatar = getAvatar(state)
    // Compute active skills from frontend selector state
    const disabled: string[] = state.disabledSkillNames || []
    const allSkills: any[] = state.skillsList || []
    const activeSkills = allSkills
      .filter((s: any) => !disabled.includes(s.name))
      .map((s: any) => s.name.replace(/\.zip$/i, ''))
    // API Key check removed — all LLM calls route to Java backend (小晴)
    // which manages its own API keys

    const time = formatDateTime()
    const fileNames = attachedFiles.map(file => file.name).join(', ')
    const userMessage: any = {
      id: Date.now(),
      sender: 'user',
      text: text || (fileNames ? `📄 上传了附件: ${fileNames}` : ''),
      time,
      isSteering
    }
    if (attachedFiles.length > 0) {
      userMessage.fileInfos = attachedFiles.map(file => ({
        name: file.name,
        path: file.path,
        content: file.content,
        safeName: file.safeName
      }))
    }

    const replyId = Date.now() + 1
    const placeholder: any = {
      id: replyId,
      sender: 'agent',
      text: '',
      isThinking: true,
      toolSteps: [],
      time
    }

    let updatedSessions: any[] = []
    setSessions((previous: any[]) => {
      updatedSessions = previous.map(session => {
        if (session.id !== sessionId) return session
        let name = session.name
        const isFirstUserMessage = !session.messages.some((message: any) => message.sender === 'user')
        if (isFirstUserMessage || name === '(未命名)' || name === '新会话' || name.startsWith('agent:main:dashboard:')) {
          const title = text || attachedFiles[0]?.name || '新会话'
          name = title.length > 15 ? `${title.substring(0, 15)}...` : title
        }
        const messages = session.messages.map((message: any) => {
          if (!message.isThinking) return message
          const cleaned = {
            ...message,
            isThinking: false,
            isSuperseded: isSteering,
            text: message.text || (isSteering ? '↳ 已根据后续指引调整方向。' : '⚠️ 对话生成被中断。'),
            toolSteps: Array.isArray(message.toolSteps)
              ? message.toolSteps.filter(
                  (step: any) =>
                    step?.type !== 'clarification' &&
                    step?.type !== 'credential' &&
                    step?.type !== 'officeRuntime'
                )
              : message.toolSteps
          }
          window.api.saveMessage({ ...cleaned, sessionId }).catch(console.error)
          return cleaned
        })
        return { ...session, name, messages: [...messages, userMessage, placeholder] }
      })
      return updatedSessions
    })

    setInputValue('')
    setAttachedFiles([])
    setSendingSessionIds(previous => ({ ...previous, [sessionId]: true }))

    const activeSession = updatedSessions.find(session => session.id === sessionId)
    ;(async () => {
      await window.api.saveMessage({ ...userMessage, sessionId })
      await window.api.saveMessage({ ...placeholder, sessionId })
      if (activeSession) await window.api.updateSession(sessionId, { name: activeSession.name })
    })().catch(console.error)

    try {
      if (!activeSession) throw new Error(`SessionNotFound: ${sessionId}`)
      const chatMessages = activeSession.messages
        .filter((message: any) => (message.sender === 'user' || message.sender === 'agent') && !message.isThinking && !message.isSuperseded)
        .slice(-state.contextRounds * 2)
        .map(toLlmMessage)

      if (abortedReplyIdsRef.current.has(replyId)) throw new Error('UserAborted')
      const response = await window.api.callLLM(
        { ...llmConfig, sessionId, messageId: replyId, contextRounds: state.contextRounds, activeSkills },
        chatMessages,
        workspacePath
      )

      if (response !== undefined) {
        finalizeReply(replyId, response, sessionId, () => {
          const latestSessions = getState().sessions
          void triggerSessionSummary(sessionId, latestSessions)
        })
      } else {
        throw new Error('LLM returned no response')
      }

      if (state.ttsEnabled && response && avatar.voice) {
        try {
          const audioBuffer = await window.api.synthesizeTts(response, avatar.voice)
          if (audioBuffer) window.api.playTtsAudio(audioBuffer)
        } catch (error) {
          console.error('TTS 播放失败', error)
        }
      }
    } catch (error) {
      console.error(error)
      failReply(replyId, sessionId, error)
    }
  }, [
    abortedReplyIdsRef,
    failReply,
    finalizeReply,
    getState,
    setAttachedFiles,
    setInputValue,
    setSendingSessionIds,
    setSessions,
    setShowApiKeyModal,
    triggerSessionSummary,
    workspacePath
  ])

  return { handleSendChat }
}
