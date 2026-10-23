import { Link } from 'react-router-dom'

/** Simple stand-ins until the dedicated pages land. */
export function PlaceholderPage({ title }: { title: string }) {
  return (
    <section>
      <h2>{title}</h2>
      <p>Coming soon</p>
    </section>
  )
}

export function NotAllowedPage() {
  return (
    <section>
      <h2>Not allowed</h2>
      <p role="alert">You are not allowed to view this page.</p>
      <Link to="/">Back to environments</Link>
    </section>
  )
}
