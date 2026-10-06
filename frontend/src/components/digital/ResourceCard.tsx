import { resourceTypeLabel, type DigitalResource } from '@/types/api';
import { Badge } from '@/components/ui/Badge';
import { ReaderIcon } from '@/components/ui/icons';
import './ResourceCard.css';

/**
 * One digital resource, as a card.
 *
 * <p><b>The resource URL is not rendered here.</b> Opening a resource belongs
 * to the reading screen, which can check that it is still enabled at the
 * moment it is opened; a link on a preview card would be a stale one as soon
 * as a librarian turned the resource off.</p>
 *
 * <p>Staff see disabled resources in their listing - the backend sends them -
 * so a disabled one is marked as such rather than shown as if it were live.</p>
 */
export function ResourceCard({ resource }: { resource: DigitalResource }) {
  return (
    <article className="sl-resource sl-panel" aria-labelledby={`resource-${resource.id}-title`}>
      <span className="sl-resource__glyph" aria-hidden="true">
        <ReaderIcon />
      </span>

      <div className="sl-resource__body">
        <h3 className="sl-resource__title" id={`resource-${resource.id}-title`}>
          {resource.title}
        </h3>

        <p className="sl-resource__book">
          From <strong>{resource.bookTitle}</strong>
        </p>

        {resource.description !== null && resource.description.length > 0 && (
          <p className="sl-resource__description">{resource.description}</p>
        )}

        <div className="sl-resource__tags">
          <Badge tone="accent" plain>
            {resourceTypeLabel(resource.resourceType)}
          </Badge>
          {!resource.enabled && (
            <Badge tone="out" srLabel="This resource is turned off and members cannot see it">
              Hidden
            </Badge>
          )}
        </div>
      </div>
    </article>
  );
}
