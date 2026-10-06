/**
 * Where the session's tokens live, in one place.
 *
 * <b>This is the file to change if the storage decision changes.</b> The
 * backend returns both tokens in a JSON body, so a browser client has to keep
 * them somewhere JavaScript can read - which means script running on this
 * origin could read them too. `localStorage` is chosen so a reload or a second
 * tab keeps the session; `sessionStorage` would narrow the exposure to one tab
 * at the cost of signing people out constantly, and an httpOnly cookie is not
 * available without a backend change.
 *
 * Nothing else in the application touches `localStorage` directly, so that
 * trade-off is reconsidered here and nowhere else.
 */

const ACCESS_TOKEN_KEY = 'smartlib.accessToken';
const REFRESH_TOKEN_KEY = 'smartlib.refreshToken';

export interface StoredTokens {
  token: string;
  refreshToken: string;
}

/**
 * Storage can throw - a private window, blocked site data, an embedded
 * browser - and a session that cannot be remembered is not a crash. Every read
 * and write goes through these two so a failure means "no session" instead of
 * a blank page.
 */
function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string | null): void {
  try {
    if (value === null) {
      window.localStorage.removeItem(key);
    } else {
      window.localStorage.setItem(key, value);
    }
  } catch {
    // A session that lasts only as long as this page is still a session.
  }
}

export const tokenStorage = {
  accessToken: (): string | null => read(ACCESS_TOKEN_KEY),

  refreshToken: (): string | null => read(REFRESH_TOKEN_KEY),

  save(tokens: StoredTokens): void {
    write(ACCESS_TOKEN_KEY, tokens.token);
    write(REFRESH_TOKEN_KEY, tokens.refreshToken);
  },

  /** Replaces only the access token, which is what a refresh produces. */
  saveAccessToken(token: string): void {
    write(ACCESS_TOKEN_KEY, token);
  },

  clear(): void {
    write(ACCESS_TOKEN_KEY, null);
    write(REFRESH_TOKEN_KEY, null);
  },

  has(): boolean {
    return read(ACCESS_TOKEN_KEY) !== null;
  },
};
