import { Section } from '@/components/ui/Section';
import { Skeleton } from '@/components/ui/Skeleton';
import { LibrariesIcon } from '@/components/ui/icons';
import { publicCatalogueService } from '@/services/publicCatalogueService';
import { useAsync } from '@/hooks/useAsync';
import type { PublicLibrary } from '@/types/api';
import './MultiLibrary.css';

/**
 * The multi-library idea, shown with the real ones.
 *
 * <p>The panel lists the libraries actually on this deployment and how many
 * titles each has catalogued - both from `GET /api/public/libraries`, both
 * real. If a deployment has one library it shows one; there is no padding the
 * list out to look busier, and no invented branch names.</p>
 *
 * <p>When the call fails the panel simply does not appear. The point the
 * section makes is in the prose beside it, and a broken list would undercut a
 * claim about reliability more than a missing one does.</p>
 */
const POINTS = [
  {
    title: 'One deployment, many libraries',
    text: 'A university, its departments and its branches all run on the same installation without sharing a shelf.',
  },
  {
    title: 'Nothing crosses between them',
    text: 'Every query is scoped to the library the account belongs to. A librarian cannot see another branch’s members, loans or fines - not by accident, and not by editing a URL.',
  },
  {
    title: 'Administered separately',
    text: 'Each library has its own staff and its own audit trail, so who did what stays answerable within the institution that owns the record.',
  },
];

export function MultiLibrary() {
  const libraries = useAsync<PublicLibrary[]>((signal) => publicCatalogueService.libraries(signal), []);

  return (
    <Section
      id="multi-library"
      eyebrow="Multi-library"
      title="Built for more than one library from the first line"
      lead="Most systems bolt multi-tenancy on later. SmartLib scopes every query by library at the point the data is read."
    >
      <div className="sl-multi">
        <div className="sl-multi__copy">
          <ul className="sl-multi__points">
            {POINTS.map((point) => (
              <li key={point.title}>
                <span className="sl-multi__dot" aria-hidden="true" />
                <span>
                  <strong>{point.title}</strong>
                  {point.text}
                </span>
              </li>
            ))}
          </ul>
        </div>

        {libraries.error === null && (
          <div className="sl-multi__panel">
            <p className="sl-multi__panel-title">On this deployment</p>

            {libraries.loading ? (
              <div className="sl-multi__branches">
                <Skeleton height="3rem" radius="var(--radius-md)" />
                <Skeleton height="3rem" radius="var(--radius-md)" />
              </div>
            ) : (
              <ul className="sl-multi__branches">
                {(libraries.data ?? []).map((library) => (
                  <li className="sl-branch" key={library.name}>
                    <span className="sl-branch__name">
                      <LibrariesIcon className="sl-branch__icon" width="18" height="18" />
                      <span>{library.name}</span>
                    </span>
                    <span className="sl-branch__count">
                      {library.titleCount.toLocaleString()} {library.titleCount === 1 ? 'title' : 'titles'}
                    </span>
                  </li>
                ))}
              </ul>
            )}

            <p className="sl-multi__sealed">
              Names and title counts are public. Members, loans and fines are not.
            </p>
          </div>
        )}
      </div>
    </Section>
  );
}
