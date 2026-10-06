import './Skeleton.css';

interface SkeletonProps {
  /** Any CSS length. A line of text is about 1em. */
  height?: string;
  width?: string;
  radius?: string;
}

/**
 * A placeholder in the shape of the thing that is coming.
 *
 * <p>Hidden from assistive technology: a screen reader is told the region is
 * busy by the live region around it, and a row of empty boxes is noise.</p>
 */
export function Skeleton({ height = '1em', width = '100%', radius }: SkeletonProps) {
  return (
    <span
      className="sl-skeleton"
      aria-hidden="true"
      style={{ display: 'block', height, width, borderRadius: radius }}
    />
  );
}
