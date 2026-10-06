import { Link } from 'react-router-dom';
import './NotFoundPage.css';

/** A page that is not there. Says so, and offers the way back. */
export function NotFoundPage() {
  return (
    <div className="sl-notfound">
      <p className="sl-notfound__code">404</p>
      <h1 className="sl-notfound__title">That shelf is empty</h1>
      <p className="sl-muted">The page you were looking for is not here.</p>
      <Link to="/">Back to your library</Link>
    </div>
  );
}
