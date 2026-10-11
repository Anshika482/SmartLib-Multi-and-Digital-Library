import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { toast } from 'sonner';
import { authApi } from '@/api/endpoints';
import { useSession } from '@/stores/session';

export function useLogout() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  return async () => {
    try {
      await authApi.logout();
    } catch {
      // even if the network call fails, drop the local session
    }
    useSession.getState().clear();
    qc.clear();
    toast.success('You have been logged out.');
    navigate('/', { replace: true });
  };
}
