import { Link } from 'react-router-dom';
import { PageHeading } from '../PageHeading';
import { Badge } from '@/components/ui/Badge';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAsync } from '@/hooks/useAsync';
import { digitalResourceService } from '@/services/digitalResourceService';
import { resourceTypeLabel, type DigitalResource, type Page } from '@/types/api';
import './ReadingPage.css';

/** A page of resources. Bounded like every other list in this application. */
const PAGE_SIZE = 50;

/**
 * Everything the library publishes online.
 *
 * <p><b>The list is the server's.</b> A member is sent the enabled resources of
 * their own library and nothing else - not a wider list narrowed here - so
 * there is no filtering in this file that could be got wrong.</p>
 *
 * <p>This screen belongs to members; staff reach the same resources through a
 * book, and manage them from the digital resources screen. The disabled badge
 * below is therefore for the reader, which staff do open.</p>
 */
export function ReadingPage() {
  const resources = useAsync<Page<DigitalResource>>(
    (signal) => digitalResourceService.list({ size: PAGE_SIZE }, signal),
    [],
  );

  const rows = resources.data?.content ?? null;

  return (
    <>
      <PageHeading
        title="Digital reading"
        lead={
          rows === null
            ? 'Chapters, e-books, recordings and links.'
            : `${rows.length} ${rows.length === 1 ? 'item' : 'items'} to read or watch.`
        }
      />

      <DataState
        data={rows}
        loading={resources.loading}
        error={resources.error}
        onRetry={resources.reload}
        label="What your library publishes online"
        emptyTitle="Nothing online yet"
        emptyDetail="Your library has not published anything online yet."
        skeleton={
          <ul className="sl-reading">
            {[0, 1, 2].map((slot) => (
              <li className="sl-panel sl-reading__row" key={slot}>
                <Skeleton height="1.1rem" width="50%" />
                <Skeleton height="0.9rem" width="25%" />
              </li>
            ))}
          </ul>
        }
      >
        {(items) => (
          <ul className="sl-reading">
            {items.map((resource) => (
              <li className="sl-panel sl-reading__row" key={resource.id}>
                <div className="sl-reading__main">
                  {/* Opens the reader rather than the file: the reader is where
                      the type is decided and where the link is made safe. */}
                  <Link className="sl-reading__title" to={`/app/reading/${resource.id}`}>
                    {resource.title}
                  </Link>
                  <p className="sl-reading__book">{resource.bookTitle}</p>
                </div>

                <div className="sl-reading__aside">
                  <Badge tone="accent" plain>
                    {resourceTypeLabel(resource.resourceType)}
                  </Badge>
                  {!resource.enabled && <Badge tone="out">Hidden from members</Badge>}
                </div>
              </li>
            ))}
          </ul>
        )}
      </DataState>
    </>
  );
}
