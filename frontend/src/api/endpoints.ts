import { api, qs, apiUrl } from './http';
import type * as T from './types';

export const authApi = {
  policy: () => api.get<T.AuthPolicy>('/api/auth/policy'),
  registerMember: (body: unknown) => api.post<T.RegistrationResponse>('/api/auth/register/member', body),
  registerLibrarian: (body: unknown) => api.post<T.RegistrationResponse>('/api/auth/register/librarian', body),
  registerAdmin: (body: unknown) => api.post<T.RegistrationResponse>('/api/auth/register/admin', body),
  login: (email: string, password: string) => api.post<T.LoginResponse>('/api/auth/login', { email, password }),
  logout: () => fetch(apiUrl('/api/auth/logout'), {
    method: 'POST',
    credentials: 'include',
    headers: { 'X-Requested-With': 'SmartLib' },
  }),
  logoutAll: () => api.post<void>('/api/auth/logout-all'),
  me: () => api.get<T.Me>('/api/auth/me'),
  verifyOtp: (email: string, otp: string) => api.post<T.VerificationResult>('/api/auth/verify-email/otp', { email, otp }),
  verifyLink: (token: string) => api.post<T.VerificationResult>('/api/auth/verify-email/link', { token }),
  resend: (email: string) => api.post<T.ResendResponse>('/api/auth/resend-verification', { email }),
  forgot: (email: string) => api.post<{ message: string }>('/api/auth/forgot-password', { email }),
  validateReset: (token: string) => api.post<T.TokenValidity>('/api/auth/reset-password/validate', { token }),
  reset: (body: { token: string; newPassword: string; confirmPassword: string }) => api.post<{ message: string }>('/api/auth/reset-password', body),
  validateInvite: (token: string) => api.post<T.TokenValidity>('/api/auth/invite/validate', { token }),
  acceptInvite: (body: { token: string; newPassword: string; confirmPassword: string }) => api.post<{ message: string }>('/api/auth/accept-invite', body),
};

export interface CatalogueParams {
  q?: string;
  libraryId?: number | null;
  category?: string | null;
  language?: string | null;
  available?: boolean;
  sort?: string;
  page?: number;
  size?: number;
}

export const publicApi = {
  site: () => api.get<T.SiteInfo>('/api/public/site'),
  demoAccounts: () => api.get<T.DemoAccounts>('/api/public/demo-accounts'),
  catalogue: (p: CatalogueParams, signal?: AbortSignal) =>
    api.get<T.Page<T.CatalogueItem>>('/api/public/catalogue' + qs({ ...p }), signal),
  book: (id: number) => api.get<T.BookDetails>(`/api/public/catalogue/${id}`),
  featured: (limit = 8) => api.get<T.CatalogueItem[]>(`/api/public/featured?limit=${limit}`),
  categories: () => api.get<T.CategoryWithCount[]>('/api/public/categories'),
  languages: () => api.get<string[]>('/api/public/languages'),
  libraries: (q?: string) => api.get<T.LibraryCard[]>('/api/public/libraries' + qs({ q })),
  library: (slug: string) => api.get<T.LibraryDetails>(`/api/public/libraries/${encodeURIComponent(slug)}`),
  resources: (p: { q?: string; type?: string; libraryId?: number; page?: number }) =>
    api.get<T.Page<T.ResourceView>>('/api/public/resources' + qs(p)),
  aiStatus: () => api.get<T.AiStatus>('/api/public/ai/status'),
};

export const meApi = {
  profile: () => api.get<T.Me>('/api/me/profile'),
  updateProfile: (body: { fullName: string; phone: string | null }) => api.put<T.Me>('/api/me/profile', body),
  changePassword: (body: { currentPassword: string; newPassword: string; confirmPassword: string }) =>
    api.post<T.LoginResponse>('/api/me/change-password', body),
  dashboard: () => api.get<T.MemberDashboard>('/api/me/dashboard'),
  requests: (p: { status?: string; bookId?: number; page?: number; size?: number }) =>
    api.get<T.Page<T.RequestView>>('/api/me/requests' + qs(p)),
  createRequest: (body: { bookId: number; libraryId: number; note?: string }) =>
    api.post<T.RequestView>('/api/me/requests', body),
  cancelRequest: (id: number) => api.post<void>(`/api/me/requests/${id}/cancel`),
  borrowings: (p: { filter?: string; page?: number; size?: number }) =>
    api.get<T.Page<T.LoanView>>('/api/me/borrowings' + qs(p)),
  fines: (status?: string) => api.get<T.FineView[]>('/api/me/fines' + qs({ status })),
  fineSummary: () => api.get<T.FineSummary>('/api/me/fines/summary'),
  createOrder: (fineId: number) => api.post<T.CheckoutOrder>(`/api/me/fines/${fineId}/payment-order`),
  verifyPayment: (body: { razorpayOrderId: string; razorpayPaymentId: string; razorpaySignature: string }) =>
    api.post<T.PaymentView>('/api/payments/razorpay/verify', body),
  payments: (page = 0) => api.get<T.Page<T.PaymentView>>(`/api/me/payments?page=${page}`),
  demoCheckout: (body: { orderId: string; method: 'UPI' | 'CARD' | 'NETBANKING'; succeed: boolean }) =>
    api.post<T.DemoCheckoutResult>('/api/payments/demo/checkout', body),
  memberships: () => api.get<T.MembershipView[]>('/api/me/memberships'),
  join: (libraryId: number) => api.post<T.MembershipView>('/api/me/memberships', { libraryId }),
  resources: (p: { q?: string; type?: string; libraryId?: number; page?: number }) =>
    api.get<T.Page<T.ResourceView>>('/api/me/resources' + qs(p)),
  access: (id: number) => api.post<T.AccessGrant>(`/api/resources/${id}/access`),
  notifications: (p: { unreadOnly?: boolean; page?: number; size?: number }) =>
    api.get<T.Page<T.NotificationView>>('/api/me/notifications' + qs(p)),
  unreadCount: () => api.get<{ count: number }>('/api/me/notifications/unread-count'),
  markRead: (id: number) => api.post<void>(`/api/me/notifications/${id}/read`),
  markAllRead: () => api.post<{ updated: number }>('/api/me/notifications/read-all'),
};

export function manageApi(libraryId: number) {
  const base = `/api/manage/libraries/${libraryId}`;

  return {
    base,
    library: () => api.get<T.LibraryAdminView>(base),
    dashboard: () => api.get<T.LibraryDashboard>(`${base}/dashboard`),
    holdings: (p: { q?: string; status?: string; page?: number; size?: number }) =>
      api.get<T.Page<T.HoldingRow>>(`${base}/holdings` + qs(p)),
    holding: (id: number) => api.get<T.HoldingDetailView>(`${base}/holdings/${id}`),
    addHolding: (body: { bookId?: number; isbn?: string; book?: T.BookInput; shelfLocation?: string; copies?: number }) =>
      api.post<T.HoldingRow>(`${base}/holdings`, body),
    updateHolding: (id: number, shelfLocation: string) =>
      api.put<void>(`${base}/holdings/${id}`, { shelfLocation }),
    withdrawHolding: (id: number) => api.del<void>(`${base}/holdings/${id}`),
    addCopies: (id: number, count: number) =>
      api.post<T.CopyRow[]>(`${base}/holdings/${id}/copies`, { count }),
    updateCopy: (id: number, status: T.CopyStatus, conditionNote?: string) =>
      api.patch<T.CopyRow>(`${base}/copies/${id}`, { status, conditionNote }),
    updateBook: (bookId: number, body: T.BookInput) =>
      api.put<void>(`${base}/books/${bookId}`, body),
    uploadCover: (bookId: number, file: File) => {
      const f = new FormData();
      f.append('file', file);
      return api.upload<void>(`${base}/books/${bookId}/cover`, f);
    },
    isbnLookup: (isbn: string) =>
      api.get<T.IsbnLookup>(`${base}/isbn-lookup?isbn=${encodeURIComponent(isbn)}`),
    requests: (p: { status?: string; q?: string; page?: number; size?: number }) =>
      api.get<T.Page<T.RequestView>>(`${base}/requests` + qs(p)),
    approve: (id: number) => api.post<T.RequestView>(`${base}/requests/${id}/approve`),
    reject: (id: number, reason: string) =>
      api.post<T.RequestView>(`${base}/requests/${id}/reject`, { reason }),
    issue: (id: number) => api.post<T.LoanView>(`${base}/requests/${id}/issue`),
    borrowings: (p: { filter?: string; q?: string; page?: number; size?: number }) =>
      api.get<T.Page<T.LoanView>>(`${base}/borrowings` + qs(p)),
    returnLoan: (id: number, body: {
      condition: 'GOOD' | 'DAMAGED' | 'LOST';
      note?: string;
      damageFeePaise?: number;
      lostFeePaise?: number;
    }) =>
      api.post<{ loan: T.LoanView; fineIds: number[]; finesTotalPaise: number }>(
        `${base}/borrowings/${id}/return`,
        body,
      ),
    fines: (p: { status?: string; q?: string; page?: number; size?: number }) =>
      api.get<T.Page<T.FineView>>(`${base}/fines` + qs(p)),
    createFine: (body: {
      memberId: number;
      borrowingId?: number | null;
      type: T.FineType;
      amountPaise: number;
      reason: string;
    }) => api.post<T.FineView>(`${base}/fines`, body),
    waiveFine: (id: number, reason: string) =>
      api.post<T.FineView>(`${base}/fines/${id}/waive`, { reason }),
    payments: (p: { status?: string; page?: number }) =>
      api.get<T.Page<T.PaymentView>>(`${base}/payments` + qs(p)),
    resources: (p: { q?: string; type?: string; status?: string; page?: number }) =>
      api.get<T.Page<T.ResourceView>>(`${base}/resources` + qs(p)),
    createResource: (form: FormData) => api.upload<T.ResourceView>(`${base}/resources`, form),
    updateResource: (id: number, body: Partial<{
      title: string;
      description: string;
      visibility: T.ResourceVisibility;
      status: string;
      bookId: number;
    }>) => api.patch<T.ResourceView>(`${base}/resources/${id}`, body),
    announce: (body: { title: string; body: string; sendEmail: boolean }) =>
      api.post<{ recipients: number }>(`${base}/announcements`, body),
    summary: () => api.get<T.LibrarySummary>(`${base}/reports/summary`),
    popular: (days = 90) => api.get<T.PopularBook[]>(`${base}/reports/popular-books?days=${days}`),
    trends: (months = 6) => api.get<T.MonthPoint[]>(`${base}/reports/trends?months=${months}`),
    categories: () => api.get<T.CategoryPoint[]>(`${base}/reports/categories`),
    exportUrl: (type: string) => `${base}/reports/export.csv?type=${type}`,
    applications: (p: { status?: string; q?: string; page?: number }) =>
      api.get<T.Page<T.Application>>(`${base}/librarian-applications` + qs(p)),
    approveApplication: (id: number) =>
      api.post<void>(`${base}/librarian-applications/${id}/approve`),
    rejectApplication: (id: number, reason: string) =>
      api.post<void>(`${base}/librarian-applications/${id}/reject`, { reason }),
    staff: () => api.get<T.Page<T.Application>>(`${base}/staff`),
    revokeStaff: (userId: number) => api.post<void>(`${base}/staff/${userId}/revoke`),
    members: (p: { q?: string; status?: string; page?: number }) =>
      api.get<T.Page<T.MemberRow>>(`${base}/members` + qs(p)),
    setMembership: (userId: number, status: 'ACTIVE' | 'SUSPENDED') =>
      api.patch<void>(`${base}/members/${userId}/membership`, { status }),
    audit: (p: { action?: string; q?: string; outcome?: string; from?: string; to?: string; page?: number }) =>
      api.get<T.Page<T.AuditEntry>>(`${base}/audit-logs` + qs(p)),
    updateProfile: (body: T.LibraryProfileInput) =>
      api.put<T.LibraryAdminView>(`${base}/profile`, body),
    updatePolicy: (body: T.PolicyInput) =>
      api.put<T.LibraryAdminView>(`${base}/policy`, body),
    uploadImage: (file: File) => {
      const f = new FormData();
      f.append('file', file);
      return api.upload<T.LibraryAdminView>(`${base}/image`, f);
    },
  };
}

export type ManageApi = ReturnType<typeof manageApi>;

export const platformApi = {
  overview: () => api.get<T.PlatformOverview>('/api/platform/analytics/overview'),
  libraries: () => api.get<T.LibraryRow[]>('/api/platform/libraries'),
  library: (id: number) =>
    api.get<{ library: T.LibraryAdminView; summary: T.LibrarySummary; staff: T.UserRow[] }>(
      `/api/platform/libraries/${id}`,
    ),
  createLibrary: (body: { code: string; slug?: string; profile: T.LibraryProfileInput }) =>
    api.post<T.LibraryAdminView>('/api/platform/libraries', body),
  updateLibrary: (id: number, body: T.LibraryProfileInput) =>
    api.put<T.LibraryAdminView>(`/api/platform/libraries/${id}`, body),
  setLibraryStatus: (id: number, status: 'ACTIVE' | 'INACTIVE') =>
    api.patch<T.LibraryAdminView>(`/api/platform/libraries/${id}/status`, { status }),
  inviteAdmin: (id: number, body: { email: string; fullName: string }) =>
    api.post<{ userId: number }>(`/api/platform/libraries/${id}/admins`, body),
  users: (p: { q?: string; role?: string; status?: string; page?: number }) =>
    api.get<T.Page<T.UserRow>>('/api/platform/users' + qs(p)),
  setUserStatus: (id: number, status: string) =>
    api.patch<T.UserRow>(`/api/platform/users/${id}/status`, { status }),
  applications: (p: { role?: string; status?: string; q?: string; page?: number }) =>
    api.get<T.Page<T.AdminApplication>>('/api/platform/applications' + qs(p)),
  approveApplication: (id: number) => api.post<void>(`/api/platform/applications/${id}/approve`),
  rejectApplication: (id: number, reason: string) =>
    api.post<void>(`/api/platform/applications/${id}/reject`, { reason }),
  trends: (months = 12) =>
    api.get<T.MonthPoint[]>(`/api/platform/analytics/trends?months=${months}`),
  popular: (days = 90) =>
    api.get<T.PopularBook[]>(`/api/platform/analytics/popular-books?days=${days}`),
  categories: () => api.get<T.CategoryPoint[]>('/api/platform/analytics/categories'),
  audit: (p: { libraryId?: number; action?: string; q?: string; outcome?: string; from?: string; to?: string; page?: number }) =>
    api.get<T.Page<T.AuditEntry>>('/api/platform/audit-logs' + qs(p)),
  config: () =>
    api.get<{ settings: Record<string, string>; integrations: Record<string, string | boolean> }>(
      '/api/platform/config',
    ),
  updateConfig: (changes: Record<string, string>) =>
    api.put<{ settings: Record<string, string>; integrations: Record<string, string | boolean> }>(
      '/api/platform/config',
      changes,
    ),
};

export const aiApi = {
  conversations: () => api.get<T.ConversationSummary[]>('/api/ai/conversations'),
  messages: (id: string) => api.get<T.TranscriptMessage[]>(`/api/ai/conversations/${id}/messages`),
  remove: (id: string) => api.del<void>(`/api/ai/conversations/${id}`),
};
