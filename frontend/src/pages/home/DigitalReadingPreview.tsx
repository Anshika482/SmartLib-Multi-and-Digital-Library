import { Section } from '@/components/ui/Section';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { ResourceCard } from '@/components/digital/ResourceCard';
import { SignInPrompt } from '@/components/ui/SignInPrompt';
import { digitalResourceService } from '@/services/digitalResourceService';
import { useAsync } from '@/hooks/useAsync';
import { isStaff, type Role } from '@/types/api';
import './DigitalReadingPreview.css';

/**
 * The most recently added things that can be read online.
 *
 * <p>Newest first, four of them, from the caller's own library. What comes
 * back already depends on who is asking: a member is sent only the enabled
 * resources, so the empty state here means "your library has published none
 * yet" rather than "there are none".</p>
 *
 * <p>Signed out, the request is not made at all - the endpoint requires a
 * token - and the section offers the way in instead.</p>
 */
export function DigitalReadingPreview({ role }: { role: Role | null }) {
  const signedIn = role !== null;

  const resources = useAsync(
    (signal) => digitalResourceService.list({ page: 0, size: 4, sortBy: 'createdAt', direction: 'desc' }, signal),
    [],
    signedIn,
  );

  const staff = role !== null && isStaff(role);

  return (
    <Section
      id="digital-reading"
      eyebrow="Digital reading"
      title="Read it without leaving the page"
      lead={
        staff
          ? 'The latest resources attached to your books, including any you have turned off.'
          : 'The latest chapters, e-books, recordings and links your library has made available online.'
      }
    >
      {!signedIn ? (
        <SignInPrompt
          title="Sign in to read online"
          detail="Chapters, e-books, recordings and links are published to a library's own members."
        />
      ) : (
      <DataState
        data={resources.data?.content ?? null}
        loading={resources.loading}
        error={resources.error}
        onRetry={resources.reload}
        label="Digital resources recently added"
        emptyTitle="Nothing online yet"
        emptyDetail={
          staff
            ? 'Attach a PDF, e-book, video or link to any book and it will show up here.'
            : 'Your library has not published anything for online reading yet. It will appear here when it does.'
        }
        skeleton={
          <ul className="sl-digital">
            {[0, 1].map((slot) => (
              <li className="sl-panel sl-digital__placeholder" key={slot}>
                <Skeleton height="46px" width="46px" radius="var(--radius-md)" />
                <span className="sl-digital__placeholder-lines">
                  <Skeleton height="1.1rem" width="70%" />
                  <Skeleton height="0.9rem" width="45%" />
                  <Skeleton height="0.9rem" width="90%" />
                </span>
              </li>
            ))}
          </ul>
        }
      >
        {(items) => (
          <ul className="sl-digital">
            {items.map((resource) => (
              <li key={resource.id}>
                <ResourceCard resource={resource} />
              </li>
            ))}
          </ul>
        )}
      </DataState>
      )}
    </Section>
  );
}
