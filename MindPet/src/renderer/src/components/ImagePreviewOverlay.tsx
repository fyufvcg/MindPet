import React, { useEffect } from 'react'

interface ImagePreviewOverlayProps {
  src: string
  onClose: () => void
}

export function ImagePreviewOverlay({ src, onClose }: ImagePreviewOverlayProps): React.JSX.Element {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  const handleContextMenu = (event: React.MouseEvent) => {
    event.preventDefault()
    event.stopPropagation()
    if (window.api && typeof window.api.showImageContextMenu === 'function') {
      window.api.showImageContextMenu(src)
    }
  }

  return (
    <div
      className="chat-image-preview-overlay"
      role="dialog"
      aria-modal="true"
      aria-label="Image preview"
      tabIndex={-1}
      onClick={onClose}
      onContextMenu={handleContextMenu}
    >
      <img
        src={src}
        alt=""
        onClick={event => event.stopPropagation()}
        onContextMenu={handleContextMenu}
      />
    </div>
  )
}
