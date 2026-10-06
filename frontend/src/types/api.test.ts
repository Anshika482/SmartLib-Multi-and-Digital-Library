import { describe, expect, it } from 'vitest';
import { isStaff, resourceTypeLabel, roleLabel, type ResourceType, type Role } from './api';

const ROLES: Role[] = ['ROLE_ADMIN', 'ROLE_LIBRARIAN', 'ROLE_MEMBER'];

describe('roles', () => {
  it('counts administrators and librarians as staff, and members as not', () => {
    expect(isStaff('ROLE_ADMIN')).toBe(true);
    expect(isStaff('ROLE_LIBRARIAN')).toBe(true);
    expect(isStaff('ROLE_MEMBER')).toBe(false);
  });

  it('names every role without showing the ROLE_ prefix to a person', () => {
    for (const role of ROLES) {
      expect(roleLabel(role)).not.toContain('ROLE_');
      expect(roleLabel(role).length).toBeGreaterThan(0);
    }
  });
});

describe('resource types', () => {
  it('names every type the backend can send', () => {
    // Mirrors the ResourceType enum. A type added there without being added
    // here would fall through the switch and return undefined on screen.
    const types: ResourceType[] = ['PDF', 'EPUB', 'VIDEO', 'LINK'];

    for (const type of types) {
      expect(typeof resourceTypeLabel(type)).toBe('string');
      expect(resourceTypeLabel(type).length).toBeGreaterThan(0);
    }
  });

  it('spells out the ones an abbreviation would not help with', () => {
    expect(resourceTypeLabel('EPUB')).toBe('E-book');
    expect(resourceTypeLabel('LINK')).toBe('Web link');
    expect(resourceTypeLabel('PDF')).toBe('PDF');
  });
});
