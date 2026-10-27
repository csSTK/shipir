import { useState, type FormEvent } from 'react'
import { Button, Dialog, TextField } from '../../shared/ui/index.ts'
import { describeTagError, useTagImage, type Image } from './api.ts'
import { isValidRepository, isValidTag, splitReference } from './buildRules.ts'
import { useTranslation } from 'react-i18next'
import './ImagesPage.css'

interface Props {
  hostId: string
  image: Image
  onClose: () => void
}

/** Adds another repository:tag name to an image; prefilled from its first tag. */
export function TagImageDialog({ hostId, image, onClose }: Props) {
  const { t } = useTranslation('images')
  const initial = image.repoTags[0] ? splitReference(image.repoTags[0]) : { repository: '', tag: '' }
  const [repository, setRepository] = useState(initial.repository)
  const [tag, setTag] = useState(initial.tag)
  const [error, setError] = useState<string | null>(null)
  const tagImage = useTagImage(hostId)

  const repositoryError =
    repository.trim() && !isValidRepository(repository.trim())
      ? t('TagImageDialog.lowercaseLettersDigitsAndOnly')
      : null
  const tagError = tag.trim() && !isValidTag(tag.trim()) ? t('TagImageDialog.lettersDigitsOnlyUpTo') : null
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
    <Dialog size="lg" open onOpenChange={(open) => !open && onClose()} title={t('TagImageDialog.tagImage')}>
      <form className="ui-form" onSubmit={submit}>
        <TextField
          label={t('TagImageDialog.repository')}
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
          label={t('TagImageDialog.tag')}
          value={tag}
          onChange={(e) => setTag(e.target.value)}
          placeholder={t('TagImageDialog.latest')}
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
            {t('TagImageDialog.cancel')}
          </Button>
          <Button variant="primary" type="submit" loading={tagImage.isPending} disabled={!canSubmit}>
            {t('TagImageDialog.tag')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
