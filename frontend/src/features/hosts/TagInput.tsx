import { useId, useImperativeHandle, useRef, useState, type KeyboardEvent, type Ref } from 'react'
import { X } from 'lucide-react'
import { addTag, normalizeTag } from './tags.ts'
import { useTranslation } from 'react-i18next'
import './TagInput.css'

export interface TagInputHandle {
  /** Adds the pending draft and returns the final tag list, or null when the draft is invalid (message shown). */
  commit(): string[] | null
}

interface TagInputProps {
  tags: string[]
  onChange: (tags: string[]) => void
  /** Accessible name of the text field. */
  label: string
  ref?: Ref<TagInputHandle>
}

/**
 * Chip input: Enter or comma adds the draft, Backspace in an empty field removes the last tag, each chip
 * has a remove button. Rules come from `addTag`; a rejected draft stays in the field with an inline alert.
 */
export function TagInput({ tags, onChange, label, ref }: TagInputProps) {
  const { t: tr } = useTranslation('hosts')
  const [draft, setDraft] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const hintId = useId()
  const problemId = useId()

  /** Returns the list with `raw` added, or null (after showing the problem). */
  function tryAdd(current: string[], raw: string): string[] | null {
    const result = addTag(current, raw)
    if (result.problem) {
      setProblem(result.problem)
      return null
    }
    return result.tags
  }

  function commitDraft(): string[] | null {
    const next = tryAdd(tags, draft)
    if (next === null) return null
    setProblem(null)
    if (next !== tags) onChange(next)
    setDraft('')
    return next
  }

  useImperativeHandle(ref, () => ({ commit: commitDraft }))

  function handleChange(value: string) {
    if (!value.includes(',')) {
      setDraft(value)
      return
    }
    const parts = value.split(',')
    const rest = parts.pop() ?? ''
    let next: string[] | null = tags
    for (const part of parts) {
      next = tryAdd(next, part)
      if (next === null) break
    }
    if (next === null) {
      // Keep the whole text so the user can fix the rejected part.
      setDraft(value)
      return
    }
    setProblem(null)
    if (next !== tags) onChange(next)
    setDraft(rest)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    // While an IME is composing, Enter confirms the composition; it must not add the half-typed draft.
    if (event.nativeEvent.isComposing || event.keyCode === 229) return
    if (event.key === 'Enter') {
      // Enter adds the tag; it must not submit the surrounding form.
      event.preventDefault()
      commitDraft()
    } else if (event.key === 'Backspace' && draft === '' && tags.length > 0) {
      onChange(tags.slice(0, -1))
    }
  }

  return (
    <div className="tag-input">
      <div className="tag-input__field">
        {tags.map((tag) => (
          <span key={tag} className="tag-input__chip">
            {tag}
            <button
              type="button"
              className="tag-input__remove"
              aria-label={tr('TagInput.removeTagTag', { tag })}
              onClick={() => {
                onChange(tags.filter((t) => t !== tag))
                // Keep keyboard users in the field instead of dropping focus to the body.
                inputRef.current?.focus()
              }}
            >
              <X size={14} strokeWidth={1.75} aria-hidden="true" />
            </button>
          </span>
        ))}
        <input
          ref={inputRef}
          className="tag-input__input"
          type="text"
          aria-label={label}
          aria-describedby={problem ? `${hintId} ${problemId}` : hintId}
          aria-invalid={problem ? true : undefined}
          autoComplete="off"
          spellCheck={false}
          placeholder={tags.length === 0 ? tr('TagInput.addATag') : undefined}
          value={draft}
          onChange={(event) => handleChange(event.target.value)}
          onKeyDown={handleKeyDown}
          onBlur={() => {
            // Leaving the field with a valid draft adds it, so nothing typed is lost.
            if (normalizeTag(draft) !== '') commitDraft()
          }}
        />
      </div>
      <p id={hintId} className="ui-hint">
        {tr('TagInput.upTo10TagsLowercase')}
      </p>
      {problem && (
        <p id={problemId} role="alert" className="ui-error">
          {problem}
        </p>
      )}
    </div>
  )
}
