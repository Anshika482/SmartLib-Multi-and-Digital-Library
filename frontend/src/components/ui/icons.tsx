import type { SVGProps } from 'react';

/**
 * The icons this interface uses, drawn here.
 *
 * <p>A handful of line drawings is not worth an icon library: these weigh
 * nothing, inherit `currentColor` so they take the colour of whatever they sit
 * in, and are decorative - every one is hidden from assistive technology
 * because the text beside it already says what it means.</p>
 */
type IconProps = SVGProps<SVGSVGElement>;

function Icon({ children, ...rest }: IconProps) {
  return (
    <svg
      width="24"
      height="24"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      {children}
    </svg>
  );
}

/** Several buildings: more than one library. */
export function LibrariesIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M3 21h18" />
      <path d="M5 21V9l4-2.5V21" />
      <path d="M9 21V4.5L15 8v13" />
      <path d="M15 21v-8l4 2.5V21" />
      <path d="M11.5 11.5h1M11.5 15h1" />
    </Icon>
  );
}

/** A screen with a page on it: reading online. */
export function ReaderIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <rect x="2.5" y="4" width="19" height="13" rx="2" />
      <path d="M8 21h8" />
      <path d="M12 8v6" />
      <path d="M12 8c-.9-.8-2-1.2-3.2-1.2H7.5v6.4h1.3c1.2 0 2.3.4 3.2 1.2" />
      <path d="M12 8c.9-.8 2-1.2 3.2-1.2h1.3v6.4h-1.3c-1.2 0-2.3.4-3.2 1.2" />
    </Icon>
  );
}

/** A spark: the assistant. */
export function SparkIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M12 3.5 13.7 9l5.5 1.7-5.5 1.7L12 18l-1.7-5.6L4.8 10.7 10.3 9 12 3.5Z" />
      <path d="M18.5 3.5v3M20 5h-3" />
      <path d="M6 17.5v2.5M7.2 18.8H4.8" />
    </Icon>
  );
}

/** A magnifier over a shelf: the catalogue. */
export function CatalogueIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M4 5.5A1.5 1.5 0 0 1 5.5 4h3A1.5 1.5 0 0 1 10 5.5V14" />
      <path d="M4 14V5.5" />
      <path d="M7 4v10" />
      <circle cx="15" cy="12" r="4.5" />
      <path d="m18.4 15.4 2.6 2.6" />
      <path d="M4 17.5h6" />
    </Icon>
  );
}

/** Shelved books: used where a list is empty. */
export function BookshelfIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M4 20h16" />
      <rect x="5" y="8" width="3" height="12" rx="1" />
      <rect x="10" y="5" width="3" height="15" rx="1" />
      <rect x="15" y="10" width="3" height="10" rx="1" />
    </Icon>
  );
}

/** Something went wrong. */
export function WarningIcon(props: IconProps) {
  return (
    <Icon {...props}>
      <path d="M12 4.5 21 19.5H3L12 4.5Z" />
      <path d="M12 10v4" />
      <path d="M12 17h.01" />
    </Icon>
  );
}

/** Onward. */
export function ArrowRightIcon(props: IconProps) {
  return (
    <Icon width="18" height="18" {...props}>
      <path d="M4 12h15" />
      <path d="m13 6 6 6-6 6" />
    </Icon>
  );
}
