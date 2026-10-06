import React from 'react'

// An illustrative field, independent of memory content, counts and relationships.
const field = Array.from({ length: 190 }, (_, index) => {
  const fraction = (value: number): number => value - Math.floor(value)
  const seed = (offset: number): number => fraction(Math.sin((index + offset) * 127.1) * 43758.5453)
  return {
    x: seed(1) * 1200,
    y: seed(7) * 760,
    r: 0.4 + seed(13) * 0.8,
    opacity: 0.15 + seed(19) * 0.45
  }
})

export const KnowledgeGraphBackdrop = React.memo(
  function KnowledgeGraphBackdrop(): React.JSX.Element {
    return (
      <svg
        className="kg-space-field"
        viewBox="0 0 1200 760"
        preserveAspectRatio="xMidYMid slice"
        aria-hidden="true"
      >
        <g className="kg-space-field__dust">
          {field.map((point, index) => (
            <circle key={index} cx={point.x} cy={point.y} r={point.r} opacity={point.opacity} />
          ))}
        </g>
        <g className="kg-space-field__contours" fill="none" transform="rotate(-19 600 380)">
          <ellipse cx="600" cy="380" rx="690" ry="232" />
          <ellipse cx="600" cy="380" rx="745" ry="274" />
          <ellipse cx="600" cy="380" rx="810" ry="323" />
          <path d="M-120 414C160 95 850 43 1320 358" strokeDasharray="2 13" />
        </g>
        <g className="kg-space-field__stars" fill="none" strokeLinecap="round">
          <path d="M104 117v8m-4-4h8M1067 620v10m-5-5h10M946 108v6m-3-3h6M210 667v6m-3-3h6M1112 272v6m-3-3h6" />
        </g>
      </svg>
    )
  }
)
