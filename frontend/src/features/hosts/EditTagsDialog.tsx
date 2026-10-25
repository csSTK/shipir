import { useId, useRef, useState, type FormEvent } from 'react'
import { Button, Dialog } from '../../shared/ui/index.ts'
import { TagInput, type TagInputHandle } from './TagInput.tsx'
import { useSetHostTags, type HostOverview } from './api.ts'
import { describeTagError, envTags } from './tags.ts'

/** ADMIN dialog that replaces the whole tag set. A pending draft is added on Save. */
export function EditTagsDialog({ env, open, onClose }: { env: HostOverview; open: boolean; onClose: () => void }) {
  const formId = useId()
  const [tags, setTags] = useState(() => envTags(env))
  const [problem, setProblem] = useState<string | null>(null)
  const inputRef = useRef<TagInputHandle>(null)
  const save = useSetHostTags()

  function submit(event: FormEvent) {
    event.preventDefault()
    const next = inputRef.current?.commit()
    // An invalid draft already shows its message inside TagInput.
    if (!next) return
    setProblem(null)
    save.mutate({ id: env.id, tags: next }, { onSuccess: onClose, onError: (err) => setProblem(describeTagError(err)) })
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => !next && onClose()}
      title="Edit tags"
      description={`Tags help you find ${env.name} in the list.`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form={formId} loading={save.isPending}>
            Save
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} className="ui-form">
        <TagInput tags={tags} onChange={setTags} label="Tags" ref={inputRef} />
        {problem && (
          <p role="alert" className="ui-error">
            {problem}
          </p>
        )}
      </form>
    </Dialog>
  )
}
