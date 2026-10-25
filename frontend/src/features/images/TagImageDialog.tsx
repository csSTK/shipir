import { useState, type FormEvent } from 'react'
import { Button, Dialog, TextField } from '../../shared/ui/index.ts'
import { describeTagError, useTagImage, type Image } from './api.ts'
import { isValidRepository, isValidTag, splitReference } from './buildRules.ts'
import './ImagesPage.css'

interface Props {
  hostId: string
  image: Image
  onClose: () => void
}

/** Adds another repository:tag name to an image; prefilled from its first tag. */
export function TagImageDialog({ hostId, image, onClose }: Props) {
  const initial = image.repoTags[0] ? splitReference(image.repoTags[0]) : { repository: '', tag: '' }
  const [repository, setRepository] = useState(initial.repository)
  const [tag, setTag] = useState(initial.tag)
  const [error, setError] = useState<string | null>(null)
  const tagImage = useTagImage(hostId)

  const repositoryError =
    repository.trim() && !isValidRepository(repository.trim())
      ? 'Lowercase letters, digits, . _ - and / only; an optional registry host first.'
      : null
  const tagError = tag.trim() && !isValidTag(tag.trim()) ? 'Letters, digits, . _ - only, up to 128 characters.' : null
  const canSubmit = !!repository.trim() && !!tag.trim() && !repositoryError && !tagError

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!canSubmit) return
    setError(null)
    tagImage.mutate(
      { imageId: image.id, repository: repository.trim(), tag: tag.trim() },
      { onSuccess: onClose, onError: (err) => setError(describeTagError(err)) },
    )
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()} title="Tag image">
      <form className="ui-form" onSubmit={submit}>
        <TextField
          label="Repository"
          value={repository}
          onChange={(e) => setRepository(e.target.value)}
          placeholder="registry.example.com/team/app"
          error={repositoryError}
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          autoFocus
        />
        <TextField
          label="Tag"
          value={tag}
          onChange={(e) => setTag(e.target.value)}
          placeholder="latest"
          error={tagError}
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
        />
        {error && (
          <p role="alert" className="ui-error">
            {error}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" loading={tagImage.isPending} disabled={!canSubmit}>
            Tag
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
