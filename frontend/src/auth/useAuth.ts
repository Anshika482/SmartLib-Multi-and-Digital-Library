import { useContext } from 'react';
import { AuthContext, type AuthState } from './AuthContext';

/** The signed-in account, or a clear failure if the provider is missing. */
export function useAuth(): AuthState {
  const context = useContext(AuthContext);

  if (context === null) {
    throw new Error('useAuth must be used inside an AuthProvider');
  }

  return context;
}
