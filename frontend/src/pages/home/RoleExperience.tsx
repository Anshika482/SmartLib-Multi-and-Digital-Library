import type { CSSProperties } from 'react';
import { Section } from '@/components/ui/Section';
import './RoleExperience.css';

/**
 * What each role sees.
 *
 * <p>The three roles the backend actually defines - ROLE_MEMBER,
 * ROLE_LIBRARIAN, ROLE_ADMIN - and the things each can genuinely do. This is
 * the honest answer to "what will this look like for me", and it doubles as a
 * statement of the access model: the lists differ because the permissions do.</p>
 */
const ROLES = [
  {
    name: 'Member',
    ink: 'var(--success)',
    text: 'Borrow, read and ask.',
    can: ['Search their library’s catalogue', 'See their own loans and due dates', 'Read what is published online', 'Pay a fine they owe'],
  },
  {
    name: 'Librarian',
    ink: 'var(--lamp-400)',
    text: 'Run the desk and the shelves.',
    can: ['Add and edit books and categories', 'Issue and take back copies', 'Record a fine settlement', 'Publish digital resources'],
  },
  {
    name: 'Administrator',
    ink: 'var(--violet-400)',
    text: 'Own the institution.',
    can: ['Everything a librarian can', 'Create and manage accounts', 'Register a library', 'Read the audit trail'],
  },
];

export function RoleExperience() {
  return (
    <Section
      id="roles"
      eyebrow="Role-based experience"
      title="Everyone gets the library they need"
      lead="One system, three views. What you can reach is decided by the account you sign in with, and enforced on every request rather than hidden in the interface."
    >
      <ul className="sl-roles">
        {ROLES.map((role) => (
          <li
            className="sl-role sl-panel"
            key={role.name}
            style={{ '--role-ink': role.ink } as CSSProperties}
          >
            <h3 className="sl-role__name">{role.name}</h3>
            <p className="sl-role__text">{role.text}</p>
            <ul className="sl-role__list">
              {role.can.map((item) => (
                <li key={item}>
                  <span className="sl-role__tick" aria-hidden="true">
                    &#10003;
                  </span>
                  <span>{item}</span>
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </Section>
  );
}
