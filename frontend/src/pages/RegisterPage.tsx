import { useId, useState, type FormEvent } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { AuthLayout } from '@/layouts/AuthLayout';
import { Button } from '@/components/ui/Button';
import { TextField } from '@/components/ui/TextField';
import { LinkButton } from '@/components/ui/AnchorButton';
import { DataState } from '@/components/ui/DataState';
import { Skeleton } from '@/components/ui/Skeleton';
import { useAuth } from '@/auth/useAuth';
import { useAsync } from '@/hooks/useAsync';
import { ApiError } from '@/services/apiClient';
import { publicCatalogueService } from '@/services/publicCatalogueService';
import {
  registrationService,
  PASSWORD_MIN_LENGTH,
  PASSWORD_MAX_LENGTH,
  USERNAME_MIN_LENGTH,
} from '@/services/registrationService';
import type { PublicLibrary, RegistrationResponse, RegistrationType } from '@/types/api';
import './RegisterPage.css';

interface Choice {
  type: RegistrationType;
  name: string;
  text: string;
}

/**
 * What a visitor can apply to be, and what each one means for them.
 *
 * <p>There is no fourth card. A super administrator is created by the system
 * and never registered, so offering it - even disabled - would describe a door
 * that is not there.</p>
 */
const CHOICES: Choice[] = [
  {
    type: 'MEMBER',
    name: 'Member',
    text: 'Borrow books and read online at a library you belong to. Your account works straight away.',
  },
  {
    type: 'LIBRARIAN',
    name: 'Librarian',
    text: 'Work at an existing library. An administrator of that library approves your application first.',
  },
  {
    type: 'ADMIN',
    name: 'Library administrator',
    text: 'Open a new library on SmartLib and run it. Your application is reviewed before the library opens.',
  },
];

/**
 * Registering.
 *
 * <p>One form whose shape follows the choice: a member and a librarian pick an
 * existing library, an administrator names a new one. The fields that do not
 * apply are not rendered, rather than shown disabled - a field nobody can fill
 * is a question the form should not be asking.</p>
 *
 * <p><b>Nothing here chooses a role.</b> The request carries the type chosen
 * above and the backend decides what it is worth. A modified client gains
 * nothing: there is no role field to set, and the server would ignore one.</p>
 */
export function RegisterPage() {
  const { user, loading } = useAuth();
  const formId = useId();

  const [type, setType] = useState<RegistrationType>('MEMBER');
  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');
  const [password, setPassword] = useState('');
  const [libraryId, setLibraryId] = useState('');
  const [libraryName, setLibraryName] = useState('');

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<RegistrationResponse | null>(null);

  // Only the two kinds that join an existing library need the list.
  const joinsExisting = type === 'MEMBER' || type === 'LIBRARIAN';

  const libraries = useAsync<PublicLibrary[]>(
    (signal) => publicCatalogueService.libraries(signal),
    [],
    joinsExisting,
  );

  // Somebody already signed in has no use for this form.
  if (!loading && user !== null) {
    return <Navigate to="/" replace />;
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setBusy(true);

    try {
      const response = await registrationService.register({
        type,
        username: username.trim(),
        email: email.trim(),
        fullName: fullName.trim(),
        password,
        libraryId: joinsExisting && libraryId !== '' ? Number(libraryId) : undefined,
        libraryName: type === 'ADMIN' ? libraryName.trim() : undefined,
      });

      setOutcome(response);
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 429) {
        setError('That is a lot of registrations from here. Give it a little while and try again.');
      } else if (failure instanceof ApiError) {
        // The API's own sentence: it says the same thing for a taken username
        // and a taken email on purpose, and repeating it keeps that property.
        setError(failure.message);
      } else {
        setError('Something went wrong creating your account. Please try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  if (outcome !== null) {
    const pending = outcome.status === 'PENDING';

    return (
      <AuthLayout>
        <div className={`sl-register__outcome${pending ? ' sl-register__outcome--pending' : ''}`}>
          <span
            className={`sl-register__status sl-register__status--${pending ? 'pending' : 'approved'}`}
          >
            {pending ? 'Awaiting approval' : 'Ready'}
          </span>

          <h2 className="sl-register__outcome-title">
            {pending ? 'Your application has been sent' : 'Your account is ready'}
          </h2>

          <p className="sl-muted">{outcome.message}</p>

          {pending ? (
            <p className="sl-muted">
              You will not be able to sign in until it is approved. There is nothing else to do for now.
            </p>
          ) : (
            <LinkButton to="/sign-in">Sign in</LinkButton>
          )}

          <Link to="/">Back to SmartLib</Link>
        </div>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout>
      <div className="sl-register">
        <header className="sl-register__head">
          <h2 className="sl-register__title">Join SmartLib</h2>
          <p className="sl-muted">Choose what you are registering as. The form changes to match.</p>
        </header>

        <fieldset className="sl-register__roles-wrap">
          <legend className="sl-register__select-label">I am registering as</legend>
          <ul className="sl-register__roles">
            {CHOICES.map((choice) => {
              const chosen = choice.type === type;
              return (
                <li key={choice.type}>
                  <button
                    type="button"
                    className={`sl-role-choice${chosen ? ' is-chosen' : ''}`}
                    aria-pressed={chosen}
                    onClick={() => setType(choice.type)}
                  >
                    <span className="sl-role-choice__name">
                      {choice.name}
                      {chosen && (
                        <span className="sl-role-choice__tick" aria-hidden="true">
                          &#10003;
                        </span>
                      )}
                    </span>
                    <span className="sl-role-choice__text">{choice.text}</span>
                  </button>
                </li>
              );
            })}
          </ul>
        </fieldset>

        {type === 'ADMIN' && (
          <p className="sl-register__note">
            This opens a <strong>new library</strong> on SmartLib with you as its administrator. It is an
            application, not an instant account: a SmartLib administrator reviews it, and the library is not
            listed and cannot be joined until then.
          </p>
        )}

        {type === 'LIBRARIAN' && (
          <p className="sl-register__note">
            This is an application to work at a library that already exists. An administrator of the library
            you choose decides, and you can sign in once they approve it.
          </p>
        )}

        <form className="sl-register__form" onSubmit={onSubmit} noValidate>
          <div className="sl-register__pair">
            <TextField
              label="Full name"
              name="fullName"
              autoComplete="name"
              required
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
            />
            <TextField
              label="Email"
              name="email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </div>

          <div className="sl-register__pair">
            <TextField
              label="Username"
              name="username"
              autoComplete="username"
              required
              minLength={USERNAME_MIN_LENGTH}
              value={username}
              onChange={(event) => setUsername(event.target.value)}
            />
            <TextField
              label="Password"
              name="password"
              type="password"
              autoComplete="new-password"
              required
              minLength={PASSWORD_MIN_LENGTH}
              maxLength={PASSWORD_MAX_LENGTH}
              hint={`At least ${PASSWORD_MIN_LENGTH} characters.`}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </div>

          {joinsExisting && (
            <div>
              <label className="sl-register__select-label" htmlFor={`${formId}-library`}>
                Library
              </label>

              <DataState
                data={libraries.data}
                loading={libraries.loading}
                error={libraries.error}
                onRetry={libraries.reload}
                label="Libraries you can join"
                emptyTitle="No libraries yet"
                emptyDetail="No library on SmartLib is open to join at the moment."
                skeleton={<Skeleton height="44px" radius="var(--radius-md)" />}
              >
                {(items) => (
                  <>
                    <select
                      id={`${formId}-library`}
                      className="sl-register__select"
                      required
                      value={libraryId}
                      onChange={(event) => setLibraryId(event.target.value)}
                    >
                      <option value="">Choose a library</option>
                      {items.map((library) => (
                        <option key={library.id} value={library.id}>
                          {library.name}
                        </option>
                      ))}
                    </select>
                    <p className="sl-register__hint">
                      Only libraries that are open to join are listed.
                    </p>
                  </>
                )}
              </DataState>
            </div>
          )}

          {type === 'ADMIN' && (
            <TextField
              label="Name of the library you want to open"
              name="libraryName"
              required
              maxLength={100}
              hint="This is how it will appear to members."
              value={libraryName}
              onChange={(event) => setLibraryName(event.target.value)}
            />
          )}

          {error !== null && (
            <p className="sl-register__error" role="alert">
              {error}
            </p>
          )}

          <Button type="submit" busy={busy} fullWidth>
            {busy ? 'Sending...' : type === 'MEMBER' ? 'Create my account' : 'Send application'}
          </Button>
        </form>

        <p className="sl-register__foot">
          Already have an account? <Link to="/sign-in">Sign in</Link>
        </p>
      </div>
    </AuthLayout>
  );
}
