import { Link, useLocation } from 'react-router-dom';
import { labelFor } from '@/app/navigation';

interface PageHeadingProps {
  title: string;
  lead?: string;

  /**
   * The final crumb, for a page the navigation has no entry for.
   *
   * <p>A detail page is reached from a list rather than the sidebar, so
   * {@code labelFor} cannot name it. Without this its parent would end up as
   * the last crumb and be marked as the current page - a link to somewhere else
   * that is not a link.</p>
   */
  crumb?: string;
}

/**
 * The title block every signed-in page starts with.
 *
 * <p>The breadcrumb is built from the path rather than passed in, so a page
 * cannot disagree with where it actually is. The last crumb is the page itself
 * and is not a link - a link to where you already are is a dead control.</p>
 */
export function PageHeading({ title, lead, crumb }: PageHeadingProps) {
  const { pathname } = useLocation();

  // "/app/books" -> ["/app", "/app/books"]
  const segments = pathname.split('/').filter(Boolean);
  const trail = segments.map((_, index) => '/' + segments.slice(0, index + 1).join('/'));

  const crumbs = trail
    .map((path) => ({
      path,
      // The last segment is this page. Named by the caller when the navigation
      // has no entry for it, which is how a detail page names itself.
      label:
        path === '/app'
          ? 'Overview'
          : (labelFor(path) ?? (path === pathname && crumb !== undefined ? crumb : null)),
    }))
    .filter((entry) => entry.label !== null);

  return (
    <header className="sl-page__head">
      {crumbs.length > 1 && (
        <nav aria-label="Breadcrumb">
          <ol className="sl-page__crumbs">
            {crumbs.map((entry, index) => {
              const last = index === crumbs.length - 1;

              return (
                <li key={entry.path}>
                  {last ? (
                    <span aria-current="page">{entry.label}</span>
                  ) : (
                    <>
                      <Link to={entry.path}>{entry.label}</Link>
                      <span className="sl-page__crumb-sep" aria-hidden="true">
                        {' / '}
                      </span>
                    </>
                  )}
                </li>
              );
            })}
          </ol>
        </nav>
      )}

      <h1 className="sl-page__title">{title}</h1>
      {lead !== undefined && <p className="sl-muted">{lead}</p>}
    </header>
  );
}
