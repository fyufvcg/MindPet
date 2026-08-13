import React from 'react'
import { Image, MessageCircle, Sparkles } from 'lucide-react'

export function MemoryGalleryPage(): React.JSX.Element {
  return (
    <div className="memory-gallery-page">
      <div className="memory-gallery-hero">
        <div className="memory-gallery-icon">
          <Image size={48} strokeWidth={1} aria-hidden="true" />
        </div>
        <h1 className="memory-gallery-title">记忆回廊</h1>
        <p className="memory-gallery-desc">
          你发送的每一张照片，都会和当时的对话一起被保存在这里。
          <br />
          AI 会为每张照片生成一段摘要，帮你记住那些值得被记住的瞬间。
        </p>

        <div className="memory-gallery-features">
          <div className="memory-gallery-feature">
            <Image size={20} strokeWidth={1.5} aria-hidden="true" />
            <div>
              <strong>照片归档</strong>
              <span>自动保存你分享的每一张图片</span>
            </div>
          </div>
          <div className="memory-gallery-feature">
            <MessageCircle size={20} strokeWidth={1.5} aria-hidden="true" />
            <div>
              <strong>对话关联</strong>
              <span>保留拍摄前后的对话上下文</span>
            </div>
          </div>
          <div className="memory-gallery-feature">
            <Sparkles size={20} strokeWidth={1.5} aria-hidden="true" />
            <div>
              <strong>智能摘要</strong>
              <span>LLM 解读照片背后的故事</span>
            </div>
          </div>
        </div>

        <p className="memory-gallery-coming">此功能即将上线</p>
      </div>
    </div>
  )
}
