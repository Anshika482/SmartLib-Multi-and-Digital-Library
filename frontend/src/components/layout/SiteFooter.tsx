import { SparkIcon } from '@/components/ui/icons';
import './SiteFooter.css';

/**
 * The foot of every signed-in page.
 *
 * <p>Its links point at sections of the current page, which is what exists
 * today. A footer column of links to screens that have not been built would
 * look complete and lead nowhere, so each entry here is added when the thing
 * it names is.</p>
 */
const SECTIONS = [
  { href: '#platform', label: 'Platform' },
  { href: '#catalogue', label: 'Catalogue' },
  { href: '#digital-reading', label: 'Digital reading' },
  { href: '#assistant', label: 'AI assistant' },
];

export function SiteFooter() {
  return (
    <footer className="sl-footer">
      <div className="sl-shell">
        <div className="sl-footer__inner">
          <div className="sl-footer__brand">
            <span className="sl-footer__lockup">
              <span className="sl-footer__mark" aria-hidden="true">
                <SparkIcon width="18" height="18" />
              </span>
              <span className="sl-footer__wordmark">SMARTLIB</span>
            </span>
            <p className="sl-footer__blurb">
              One platform for many libraries - their shelves, their members and everything they have put
              online, each kept to itself.
            </p>
          </div>

          <nav className="sl-footer__nav" aria-label="Sections of this page">
            <div>
              <h2 className="sl-footer__group-title">On this page</h2>
              <ul className="sl-footer__list">
                {SECTIONS.map((section) => (
                  <li key={section.href}>
                    <a href={section.href}>{section.label}</a>
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <h2 className="sl-footer__group-title">Your account</h2>
              <ul className="sl-footer__list">
                <li>
                  <a href="#top">Back to top</a>
                </li>
              </ul>
            </div>
          </nav>
        </div>

        <div className="sl-footer__base">
          <p>A B.Tech final-year project.</p>
          <p>Built with Spring Boot and React.</p>
        </div>
      </div>
    </footer>
  );
}
