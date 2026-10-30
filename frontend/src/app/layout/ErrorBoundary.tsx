import { TriangleAlert } from 'lucide-react'
import { Component, type ErrorInfo, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Button, EmptyState } from '../../shared/ui/index.ts'
import './ErrorBoundary.css'

interface Props {
  /** When this value changes (e.g. the route path) a shown error is cleared and the children render again. */
  resetKey?: string
  children: ReactNode
}

interface State {
  error: Error | null
  prevKey?: string
  /** Bumped by Retry so the children remount with fresh state. */
  attempt: number
}

/** Catches render errors of the routed page so one broken page does not blank the whole app. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null, prevKey: this.props.resetKey, attempt: 0 }

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { error }
  }

  static getDerivedStateFromProps(props: Props, state: State): Partial<State> | null {
    if (props.resetKey !== state.prevKey) return { prevKey: props.resetKey, error: null }
    return null
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(error, info.componentStack)
  }

  private reset = () => this.setState((s) => ({ error: null, attempt: s.attempt + 1 }))

  render() {
    if (this.state.error) return <ErrorFallback onReset={this.reset} />
    return <div key={this.state.attempt} className="error-boundary__content">{this.props.children}</div>
  }
}

function ErrorFallback({ onReset }: { onReset: () => void }) {
  const { t } = useTranslation('common')
  return (
    <div className="error-boundary" role="alert">
      <EmptyState
        icon={TriangleAlert}
        title={t('ErrorBoundary.title')}
        description={t('ErrorBoundary.description')}
        action={
          <div className="error-boundary__actions">
            <Button variant="primary" onClick={onReset}>
              {t('ErrorBoundary.retry')}
            </Button>
            <Button asChild variant="secondary" onClick={onReset}>
              <Link to="/">{t('ErrorBoundary.home')}</Link>
            </Button>
          </div>
        }
      />
    </div>
  )
}
