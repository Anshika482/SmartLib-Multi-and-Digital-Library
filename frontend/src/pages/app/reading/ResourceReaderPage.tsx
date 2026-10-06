import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { PageHeading } from '../PageHeading';
import { Badge } from '@/components/ui/Badge';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAsync } from '@/hooks/useAsync';
import { digitalResourceService } from '@/services/digitalResourceService';
import { resourceTypeLabel, type DigitalResource } from '@/types/api';
import './ReadingPage.css';

/**
 * One resource, opened.
 *
 * <p><b>Nothing is downloaded into this application.</b> A PDF is handed to the
 * browser's own viewer and a video to the browser's own player, each by URL, so
 * the bytes go straight from wherever the library keeps them to the part of the
 * browser built to render them. No resource is ever read into React state,
 * which is what keeps a three-hundred-megabyte recording from becoming a
 * three-hundred-megabyte string.</p>
 *
 * <p><b>Whether this resource may be opened is the server's decision.</b> It is
 * fetched by id and the answer is rendered; a disabled one, one belonging to
 * another library, and one that never existed all arrive as the same 404, which
 * is deliberate on the server's part and is preserved here by treating them
 * identically. There is no check in this file that could be got wrong, because
 * there is no check in this file.</p>
 */
export function ResourceReaderPage() {
  const { resourceId } = useParams<{ resourceId: string }>();

  const id = Number(resourceId);
  const valid = Number.isInteger(id) && id > 0;

  const resource = useAsync<DigitalResource>(
    (signal) => digitalResourceService.get(id, signal),
    [id],
    valid,
  );

  if (!valid) {
    return (
      <>
        <PageHeading title="Not available" crumb="Reading" />
        <div className="sl-panel sl-reader__missing">
          <p>That is not something this library publishes.</p>
          <Link to="/app/reading">Back to digital reading</Link>
        </div>
      </>
    );
  }

  const found = resource.data;

  return (
    <>
      <PageHeading
        title={found?.title ?? 'Reading'}
        lead={found?.bookTitle ?? undefined}
        crumb={found?.title ?? 'Reading'}
      />

      <DataState
        data={found === null ? null : [found]}
        loading={resource.loading}
        error={resource.error}
        onRetry={resource.reload}
        label="This resource"
        emptyTitle="Nothing to open"
        emptyDetail="This resource could not be read."
        skeleton={
          <div className="sl-panel sl-reader__frame">
            <Skeleton height="24rem" radius="var(--radius-md)" />
          </div>
        }
      >
        {([open]) => <Viewer resource={open} />}
      </DataState>
    </>
  );
}

/** Whichever player the type calls for, plus what the library said about it. */
function Viewer({ resource }: { resource: DigitalResource }) {
  return (
    <>
      <div className="sl-reader__meta">
        <Badge tone="accent" plain>
          {resourceTypeLabel(resource.resourceType)}
        </Badge>

        {/* Staff are sent disabled resources; a member never is. Saying so here
            keeps a member of staff from wondering why nobody else can see it. */}
        {!resource.enabled && <Badge tone="out">Hidden from members</Badge>}

        <Link className="sl-reader__book" to={`/app/catalogue/${resource.bookId}`}>
          Go to the book
        </Link>
      </div>

      {resource.description !== null && resource.description.length > 0 && (
        <p className="sl-reader__description">{resource.description}</p>
      )}

      <ResourceBody resource={resource} />
    </>
  );
}

/**
 * The resource itself, by type.
 *
 * <p>Each type gets the thing the browser already does well, rather than a
 * renderer written here: the PDF viewer, the media player, or the reader
 * application the person already has.</p>
 *
 * <p>{@code failed} is set by the media player alone, which does report a
 * broken source reliably. The framed viewer cannot - see the comment on the
 * frame - so for a PDF the way out is always on screen instead of appearing
 * after a failure nobody can detect.</p>
 */
function ResourceBody({ resource }: { resource: DigitalResource }) {
  const [failed, setFailed] = useState(false);

  if (failed) {
    return <Unopenable resource={resource} />;
  }

  switch (resource.resourceType) {
    case 'PDF':
      return (
        <div className="sl-panel sl-reader__frame">
          {/*
            The browser's own PDF viewer, given the address rather than the
            bytes. An <iframe> keeps the document in its own origin, so nothing
            it contains can read this page or the token it holds.
          */}
          <iframe
            className="sl-reader__pdf"
            src={resource.resourceUrl}
            title={`${resource.title}, as a PDF`}
            sandbox="allow-scripts allow-same-origin allow-popups allow-forms"
          />
          {/*
            No onError here, deliberately. A cross-origin frame does not report
            failure the way an image does: a host that refuses to be framed, or
            answers 404, usually fires load rather than error, or fires nothing
            at all. Wiring a handler would promise a fallback that never
            appears, so the way out is simply always present below.
          */}
          <OpenDirectly resource={resource} label="Open the PDF in a new tab" />
        </div>
      );

    case 'VIDEO':
      return (
        <div className="sl-panel sl-reader__frame">
          {/*
            The browser's own player: controls, keyboard, captions and picture
            in picture all come free and behave the way the person expects.
            Nothing about any other resource is on this page to leak.
          */}
          <video
            className="sl-reader__video"
            src={resource.resourceUrl}
            controls
            preload="metadata"
            onError={() => setFailed(true)}
          >
            Your browser cannot play this recording.
          </video>
          <OpenDirectly resource={resource} label="Open the recording in a new tab" />
        </div>
      );

    case 'EPUB':
      return (
        <div className="sl-panel sl-reader__frame sl-reader__handoff">
          <p className="sl-reader__handoff-title">This is an e-book</p>
          <p className="sl-reader__handoff-detail">
            EPUB files open in a reading app rather than in a browser tab. Opening it will hand it
            to whichever e-reader this device uses, where you can change the type size and read
            offline.
          </p>
          <OpenDirectly resource={resource} label="Open the e-book" prominent />
        </div>
      );

    case 'LINK':
      return (
        <div className="sl-panel sl-reader__frame sl-reader__handoff">
          <p className="sl-reader__handoff-title">This is a link to another site</p>
          <p className="sl-reader__handoff-detail">
            Your library has pointed at something it does not host. It opens in a new tab, and that
            site&rsquo;s own terms apply once you are there.
          </p>
          <OpenDirectly resource={resource} label="Open the link" prominent />
        </div>
      );
  }
}

/**
 * A plain way out to the resource itself.
 *
 * <p>{@code noopener} so the opened page cannot reach back through
 * {@code window.opener} and navigate this one; {@code noreferrer} so it is not
 * told which library sent the reader. Both on every outward link, regardless of
 * type.</p>
 */
function OpenDirectly({
  resource,
  label,
  prominent,
}: {
  resource: DigitalResource;
  label: string;
  prominent?: boolean;
}) {
  return (
    <a
      className={prominent === true ? 'sl-reader__open sl-reader__open--strong' : 'sl-reader__open'}
      href={resource.resourceUrl}
      target="_blank"
      rel="noopener noreferrer"
    >
      {label}
    </a>
  );
}

/** What to say when the browser could not render it after all. */
function Unopenable({ resource }: { resource: DigitalResource }) {
  return (
    <div className="sl-panel sl-reader__frame sl-reader__handoff">
      <p className="sl-reader__handoff-title">This would not open here</p>
      <p className="sl-reader__handoff-detail">
        The file may have moved, or the site holding it may not allow it to be shown inside another
        page. Opening it directly usually still works.
      </p>
      <OpenDirectly resource={resource} label="Open it in a new tab" prominent />
    </div>
  );
}
