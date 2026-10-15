import React from 'react'
import { ChevronDown } from 'lucide-react'

export interface KnowledgeGraphSelectOption {
  value: string
  label: string
  disabled?: boolean
}

function lastEnabledIndex(options: KnowledgeGraphSelectOption[]): number {
  for (let index = options.length - 1; index >= 0; index -= 1) {
    if (!options[index].disabled) return index
  }
  return -1
}

interface Props {
  value: string
  options: KnowledgeGraphSelectOption[]
  labelledBy: string
  placeholder?: string
  disabled?: boolean
  required?: boolean
  autoFocus?: boolean
  onChange: (value: string) => void
}

export function KnowledgeGraphSelect({
  value,
  options,
  labelledBy,
  placeholder = '请选择',
  disabled = false,
  required = false,
  autoFocus = false,
  onChange
}: Props): React.JSX.Element {
  const inputRef = React.useRef<HTMLInputElement>(null)
  const listRef = React.useRef<HTMLDivElement>(null)
  const blurCloseTimerRef = React.useRef<number | null>(null)
  const listId = React.useId()
  const [open, setOpen] = React.useState(false)
  const [query, setQuery] = React.useState('')
  const [activeIndex, setActiveIndex] = React.useState(-1)
  const selectedOption = options.find((option) => option.value === value)
  const filteredOptions = React.useMemo(() => {
    const normalizedQuery = query.trim().toLocaleLowerCase()
    if (!normalizedQuery) return options
    return options.filter((option) => option.label.toLocaleLowerCase().includes(normalizedQuery))
  }, [options, query])
  const safeActiveIndex =
    activeIndex >= 0 &&
    activeIndex < filteredOptions.length &&
    !filteredOptions[activeIndex].disabled
      ? activeIndex
      : filteredOptions.findIndex((option) => !option.disabled)
  const activeOption = filteredOptions[safeActiveIndex]

  const openMenu = (direction: 1 | -1 = 1): void => {
    if (disabled) return
    setQuery('')
    setOpen(true)
    const selectedIndex = options.findIndex(
      (option) => option.value === value && !option.disabled
    )
    if (selectedIndex >= 0) {
      setActiveIndex(selectedIndex)
      return
    }
    const fallbackIndex =
      direction === 1
        ? options.findIndex((option) => !option.disabled)
        : lastEnabledIndex(options)
    setActiveIndex(fallbackIndex)
  }

  const closeMenu = (): void => {
    setOpen(false)
    setQuery('')
    setActiveIndex(-1)
  }

  const choose = (option: KnowledgeGraphSelectOption): void => {
    if (option.disabled) return
    onChange(option.value)
    closeMenu()
    inputRef.current?.focus()
  }

  const moveActive = (direction: 1 | -1): void => {
    if (filteredOptions.length === 0) return
    for (let offset = 1; offset <= filteredOptions.length; offset += 1) {
      const nextIndex =
        (safeActiveIndex + direction * offset + filteredOptions.length * 2) %
        filteredOptions.length
      if (!filteredOptions[nextIndex].disabled) {
        setActiveIndex(nextIndex)
        return
      }
    }
  }

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>): void => {
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      if (open) moveActive(1)
      else openMenu(1)
      return
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      if (open) moveActive(-1)
      else openMenu(-1)
      return
    }
    if (event.key === 'Home' && open) {
      event.preventDefault()
      setActiveIndex(filteredOptions.findIndex((option) => !option.disabled))
      return
    }
    if (event.key === 'End' && open) {
      event.preventDefault()
      setActiveIndex(lastEnabledIndex(filteredOptions))
      return
    }
    if (event.key === 'Enter' && open) {
      event.preventDefault()
      if (activeOption) choose(activeOption)
      return
    }
    if (event.key === 'Escape' && open) {
      event.preventDefault()
      closeMenu()
      return
    }
    if (
      !open &&
      event.key.length === 1 &&
      !event.ctrlKey &&
      !event.metaKey &&
      !event.altKey
    ) {
      const nextQuery = event.key.toLocaleLowerCase()
      const matches = options.filter((option) =>
        option.label.toLocaleLowerCase().includes(nextQuery)
      )
      event.preventDefault()
      setOpen(true)
      setQuery(event.key)
      setActiveIndex(matches.findIndex((option) => !option.disabled))
    }
  }

  React.useEffect(() => {
    if (!open || !activeOption) return
    listRef.current
      ?.querySelector<HTMLElement>('[aria-selected="true"].is-active, .is-active')
      ?.scrollIntoView({ block: 'nearest' })
  }, [safeActiveIndex, activeOption, open])

  React.useEffect(
    () => () => {
      if (blurCloseTimerRef.current !== null) {
        window.clearTimeout(blurCloseTimerRef.current)
      }
    },
    []
  )

  return (
    <div
      className={['kg-select', open ? 'is-open' : '', disabled ? 'is-disabled' : '']
        .filter(Boolean)
        .join(' ')}
      onBlur={(event) => {
        const container = event.currentTarget
        if (container.contains(event.relatedTarget as Node | null)) return
        if (blurCloseTimerRef.current !== null) {
          window.clearTimeout(blurCloseTimerRef.current)
        }
        blurCloseTimerRef.current = window.setTimeout(() => {
          blurCloseTimerRef.current = null
          if (!container.contains(document.activeElement)) closeMenu()
        }, 0)
      }}
    >
      <div className="kg-select__control">
        <input
          ref={inputRef}
          autoFocus={autoFocus}
          className="kg-select__input"
          type="text"
          role="combobox"
          aria-labelledby={labelledBy}
          aria-haspopup="listbox"
          aria-expanded={open}
          aria-required={required || undefined}
          aria-controls={open ? listId : undefined}
          aria-activedescendant={
            open && activeOption ? listId + '-option-' + safeActiveIndex : undefined
          }
          aria-autocomplete="list"
          autoComplete="off"
          value={open ? query : selectedOption?.label || ''}
          placeholder={
            open ? '输入关键字筛选' : placeholder
          }
          disabled={disabled}
          onClick={() => {
            if (!open) openMenu()
          }}
          onChange={(event) => {
            const nextQuery = event.target.value
            const normalizedQuery = nextQuery.trim().toLocaleLowerCase()
            const matches = normalizedQuery
              ? options.filter((option) =>
                  option.label.toLocaleLowerCase().includes(normalizedQuery)
                )
              : options
            setQuery(nextQuery)
            setOpen(true)
            setActiveIndex(matches.findIndex((option) => !option.disabled))
          }}
          onKeyDown={handleKeyDown}
        />
        <ChevronDown
          className="kg-select__chevron"
          size={15}
          aria-hidden="true"
        />
      </div>
      {open && (
        <div ref={listRef} className="kg-select__menu" id={listId} role="listbox">
          {filteredOptions.length === 0 ? (
            <div
              className="kg-select__empty"
              role="option"
              aria-selected="false"
              aria-disabled="true"
            >
              没有匹配的选项
            </div>
          ) : (
            filteredOptions.map((option, index) => (
              <button
                id={listId + '-option-' + index}
                key={option.value}
                type="button"
                className={[
                  'kg-select__option',
                  value === option.value ? 'is-selected' : '',
                  safeActiveIndex === index ? 'is-active' : '',
                  option.disabled ? 'is-disabled' : ''
                ]
                  .filter(Boolean)
                  .join(' ')}
                role="option"
                aria-selected={value === option.value}
                aria-disabled={option.disabled || undefined}
                disabled={option.disabled}
                tabIndex={-1}
                onPointerDown={(event) => {
                  if (event.pointerType === 'mouse') event.preventDefault()
                }}
                onClick={() => choose(option)}
              >
                <span>{option.label}</span>
                {value === option.value && <i aria-hidden="true" />}
              </button>
            ))
          )}
        </div>
      )}
    </div>
  )
}
