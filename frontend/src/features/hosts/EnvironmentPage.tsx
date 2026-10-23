import { Link } from 'react-router-dom'
import './EnvironmentsOverview.css'

/** Placeholder for the environment detail view. */
export function EnvironmentPage() {
  return (
    <section className="envs">
      <Link className="envs__back" to="/">
        ‹ Environments
      </Link>
      <h2 className="envs__title">Environment</h2>
      <p className="envs__message">Coming soon</p>
    </section>
  )
}
