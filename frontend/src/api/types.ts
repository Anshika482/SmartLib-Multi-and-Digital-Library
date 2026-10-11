export type Role = 'MEMBER' | 'LIBRARIAN' | 'ADMIN' | 'SUPER_ADMIN';
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED';

export interface LibraryRef { id: number; name: string; slug: string }
export interface Me {
  id: number; email: string; fullName: string; phone: string | null; role: Role; emailVerified: boolean;
  library: LibraryRef | null; approvalStatus: ApprovalStatus | null; createdAt: string;
}
export interface LoginResponse { accessToken: string; expiresInSeconds: number; user: Me }
export interface RegistrationResponse { email: string; role: Role; otpExpiresInSeconds: number; resendAvailableInSeconds: number; nextStep: string; message: string }
export interface VerificationResult { status: string; email: string; role: Role; nextStep: 'LOGIN' | 'AWAIT_APPROVAL'; message: string }
export interface ResendResponse { message: string; otpExpiresInSeconds: number; resendAvailableInSeconds: number }
export interface TokenValidity { valid: boolean; reason: string | null; email: string | null; fullName: string | null; libraryName: string | null }
export interface AuthPolicy {
  otpLength: number; otpTtlSeconds: number; linkTtlSeconds: number; resendCooldownSeconds: number; maxResendsPerDay: number;
  maxOtpAttempts: number; passwordMinLength: number; passwordMaxLength: number; librarianRegistrationOpen: boolean;
  adminRegistrationOpen: boolean;
}

/** One-click demo logins for the login page (only while DEMO_LOGIN_ENABLED=true on the server). */
export interface DemoAccount { role: Role; label: string; email: string; description: string }
export interface DemoAccounts { enabled: boolean; password: string | null; accounts: DemoAccount[] }

export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }

export interface CategoryRef { id: number; name: string; slug: string }
export interface CategoryWithCount extends CategoryRef { bookCount: number }
export interface HoldingAvailability {
  holdingId: number; libraryId: number; libraryName: string; librarySlug: string; city: string | null; availableCopies: number; totalCopies: number;
}
export interface CatalogueItem {
  id: number; title: string; subtitle: string | null; authors: string[]; isbn13: string | null; coverUrl: string | null;
  isbnCoverUrl: string | null; category: CategoryRef | null; publishedYear: number | null; language: string | null; publisher: string | null;
  holdings: HoldingAvailability[]; totalAvailable: number; libraryCount: number;
}
export interface BookSummary {
  id: number; title: string; subtitle: string | null; authors: string[]; isbn13: string | null; coverUrl: string | null;
  isbnCoverUrl: string | null; category: string | null;
}
export interface HoldingDetail {
  holdingId: number; libraryId: number; libraryName: string; librarySlug: string; city: string | null; addressLine: string | null;
  openingHours: string | null; shelfLocation: string | null; availableCopies: number; totalCopies: number; loanPeriodDays: number;
  pickupWindowDays: number; finePerDayPaise: number;
}
export type ResourceType = 'PDF' | 'EPUB' | 'VIDEO' | 'LINK';
export type ResourceVisibility = 'PUBLIC' | 'REGISTERED' | 'LIBRARY_MEMBERS';
export interface ResourceSummary { id: number; title: string; type: ResourceType; visibility: ResourceVisibility; libraryId: number; libraryName: string }
export interface BookDetails {
  id: number; title: string; subtitle: string | null; authors: string[]; isbn13: string | null; isbn10: string | null;
  description: string | null; publisher: string | null; publishedYear: number | null; language: string | null; pageCount: number | null;
  category: CategoryRef | null; coverUrl: string | null; isbnCoverUrl: string | null; holdings: HoldingDetail[]; totalAvailable: number;
  digitalResources: ResourceSummary[]; related: CatalogueItem[];
}
export interface LibraryCard {
  id: number; code: string; slug: string; name: string; tagline: string | null; description: string | null; addressLine: string | null;
  city: string | null; state: string | null; postalCode: string | null; publicEmail: string | null; publicPhone: string | null;
  website: string | null; openingHours: string | null; imageUrl: string | null; titles: number; copies: number; availableCopies: number;
  digitalResources: number;
}
export interface PolicySummary { loanPeriodDays: number; maxActiveLoans: number; pickupWindowDays: number; finePerDayPaise: number; graceDays: number }
export interface LibraryDetails { library: LibraryCard; policy: PolicySummary | null }
export interface SiteInfo {
  stats: { libraries: number; titles: number; copies: number; availableCopies: number; digitalResources: number };
  announcement: string; aiEnabled: boolean;
}

export type RequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED' | 'FULFILLED';
export type BorrowingStatus = 'ACTIVE' | 'RETURNED' | 'LOST';
export interface MemberRef { id: number; fullName: string; email: string; phone: string | null }
export interface RequestView {
  id: number; status: RequestStatus; book: BookSummary; library: LibraryRef; createdAt: string; decidedAt: string | null;
  decisionReason: string | null; pickupDeadline: string | null; assignedCopy: string | null; memberNote: string | null;
  borrowingId: number | null; member: MemberRef | null; availableCopies: number | null;
}
export interface LoanView {
  id: number; status: BorrowingStatus; book: BookSummary; library: LibraryRef; issuedAt: string; dueDate: string;
  returnedAt: string | null; returnCondition: 'GOOD' | 'DAMAGED' | 'LOST' | null; overdue: boolean; overdueDays: number;
  dueSoon: boolean; estimatedFinePaise: number; accessionNo: string; member: MemberRef | null;
}
export type FineType = 'OVERDUE' | 'LOST' | 'DAMAGED' | 'MANUAL';
export type FineStatus = 'UNPAID' | 'PAID' | 'WAIVED';
export interface FineView {
  id: number; type: FineType; status: FineStatus; amountPaise: number; overdueDays: number | null; reason: string | null;
  createdAt: string; paidAt: string | null; library: LibraryRef; book: BookSummary | null; borrowingId: number | null;
  dueDate: string | null; returnedAt: string | null; waivedReason: string | null; paymentId: number | null; member: MemberRef | null;
}
export interface FineSummary { unpaidCount: number; unpaidPaise: number; paidPaise: number; waivedPaise: number }
/** provider DEMO = the built-in demo gateway (no Razorpay keys configured; no real money). */
export interface CheckoutOrder {
  paymentId: number; orderId: string; keyId: string | null; amountPaise: number; currency: string; name: string; description: string;
  prefill: { name: string; email: string; contact: string | null }; fineId: number; provider: 'RAZORPAY' | 'DEMO';
}
export interface DemoCheckoutResult { status: 'AUTHORIZED' | 'FAILED'; orderId: string; paymentId: string | null; signature: string | null; message: string | null }
export type PaymentStatus = 'CREATED' | 'PAID' | 'FAILED' | 'EXPIRED';
export interface PaymentView {
  id: number; status: PaymentStatus; amountPaise: number; currency: string; providerOrderId: string; providerPaymentId: string | null;
  method: string | null; provider: 'RAZORPAY' | 'DEMO'; createdAt: string; verifiedAt: string | null; failureReason: string | null; fineId: number; fineType: FineType;
  library: LibraryRef; book: BookSummary | null; member: MemberRef | null;
}
export interface NotificationView { id: number; type: string; title: string; body: string | null; actionUrl: string | null; libraryId: number | null; read: boolean; createdAt: string }
export interface MembershipView { libraryId: number; libraryName: string; librarySlug: string; status: 'ACTIVE' | 'SUSPENDED'; joinedAt: string }
export interface ResourceView {
  id: number; title: string; description: string | null; type: ResourceType; visibility: ResourceVisibility; status: 'ACTIVE' | 'ARCHIVED';
  libraryId: number; libraryName: string; librarySlug: string; bookId: number | null; bookTitle: string | null; mimeType: string | null;
  sizeBytes: number | null; originalFilename: string | null; external: boolean; createdAt: string;
}
export interface AccessGrant { resourceId: number; type: ResourceType; url: string; external: boolean; mimeType: string | null; filename: string | null; expiresAt: string | null }
export interface MemberDashboard {
  activeLoans: number; dueSoon: number; overdue: number; pendingRequests: number; readyForPickup: number; unpaidFinesCount: number;
  unpaidFinesPaise: number; unreadNotifications: number; loans: LoanView[]; requests: RequestView[]; notifications: NotificationView[];
}

export interface LibrarySummary {
  titles: number; copies: number; availableCopies: number; reservedCopies: number; borrowedCopies: number; lostOrDamaged: number;
  activeLoans: number; overdueLoans: number; dueSoon: number; pendingRequests: number; readyForPickup: number; totalMembers: number;
  activeMembers90d: number; finesOutstandingPaise: number; finesCollected30dPaise: number; finesCollectedTotalPaise: number;
  digitalResources: number; pendingLibrarianApplications: number; loansThisMonth: number;
}
export interface MonthPoint { month: string; issued: number; returned: number; newMembers: number; finesCollectedPaise: number }
export interface AuditEntry {
  id: number; occurredAt: string; actorUserId: number | null; actorRole: string | null; actorEmail: string | null; libraryId: number | null;
  libraryName: string | null; action: string; entityType: string | null; entityId: string | null; outcome: string; ip: string | null;
  requestId: string | null; details: string | null;
}
export interface LibraryDashboard {
  library: { id: number; name: string; slug: string; city: string | null; code: string };
  summary: LibrarySummary; pendingRequests: RequestView[]; overdue: LoanView[]; recentActivity: AuditEntry[]; trends: MonthPoint[];
}
export interface HoldingRow {
  holdingId: number; status: 'ACTIVE' | 'WITHDRAWN'; shelfLocation: string | null; book: BookSummary; total: number; available: number;
  reserved: number; borrowed: number; unavailable: number; pendingRequests: number; addedAt: string;
}
export type CopyStatus = 'AVAILABLE' | 'RESERVED' | 'BORROWED' | 'LOST' | 'DAMAGED' | 'WITHDRAWN';
export interface CopyRow { id: number; accessionNo: string; status: CopyStatus; conditionNote: string | null; acquiredAt: string; updatedAt: string }
export interface HoldingDetailView {
  holding: HoldingRow; book: BookDetails | null; categoryId: number | null; description: string | null; copies: CopyRow[];
  canEditBook: boolean; librariesHoldingBook: number;
}
export interface BookMetadata {
  isbn13: string; title: string; subtitle: string | null; authors: string[]; publisher: string | null; publishedYear: number | null;
  pageCount: number | null; description: string | null; language: string | null; coverUrl: string | null; source: string | null;
}
export interface IsbnLookup { isbn13: string; isbn10: string | null; existingBookId: number | null; alreadyInLibrary: boolean; metadata: BookMetadata | null }
export interface BookInput {
  title: string; subtitle?: string | null; authors: string[]; categoryId?: number | null; publisher?: string | null;
  publishedYear?: number | null; language?: string | null; pageCount?: number | null; description?: string | null; isbn?: string | null;
}
export interface Application {
  id: number; userId: number; fullName: string; email: string; phone: string | null; emailVerified: boolean; role: Role;
  status: ApprovalStatus; employeeRef: string | null; applicationNote: string | null; appliedAt: string; reviewedAt: string | null;
  reviewedByName: string | null; rejectionReason: string | null;
}
export interface MemberRow {
  userId: number; fullName: string; email: string; phone: string | null; membershipStatus: 'ACTIVE' | 'SUSPENDED'; joinedAt: string;
  activeLoans: number; overdueLoans: number; unpaidFinesPaise: number; accountStatus: string;
}
export interface PolicyInput {
  loanPeriodDays: number; maxActiveLoans: number; maxPendingRequests: number; pickupWindowDays: number; finePerDayPaise: number;
  graceDays: number; maxFinePerLoanPaise: number; blockThresholdPaise: number;
}
export interface LibraryProfileInput {
  name: string; tagline?: string | null; description?: string | null; addressLine?: string | null; city?: string | null;
  state?: string | null; postalCode?: string | null; publicEmail?: string | null; publicPhone?: string | null; website?: string | null;
  openingHours?: string | null; timezone?: string | null;
}
export interface LibraryAdminView extends Required<LibraryProfileInput> {
  id: number; code: string; slug: string; status: 'ACTIVE' | 'INACTIVE'; imageUrl: string | null; policy: PolicyInput;
}
export interface PopularBook { book: BookSummary; loans: number }
export interface CategoryPoint { category: string; titles: number; loans90d: number }
export interface LibraryRow {
  id: number; code: string; name: string; city: string | null; status: string; titles: number; copies: number; activeLoans: number;
  overdueLoans: number; members: number; pendingRequests: number; finesCollectedPaise: number; staff: number;
}
export interface PlatformOverview {
  libraries: number; activeLibraries: number; members: number; librarians: number; admins: number; superAdmins: number; titles: number;
  copies: number; availableCopies: number; activeLoans: number; overdueLoans: number; totalLoans: number; finesCollectedPaise: number;
  finesOutstandingPaise: number; digitalResources: number; pendingLibrarianApplications: number; aiMessages30d: number;
  aiAvgLatencyMs: number | null; aiAvgFirstTokenMs: number | null; newMembers30d: number; pendingAdminApplications: number;
}
/** A self-registered ADMIN (libraryId set) or SUPER_ADMIN (no library) application, reviewed by a Super Admin. */
export interface AdminApplication {
  id: number; userId: number; fullName: string; email: string; phone: string | null; emailVerified: boolean; role: 'ADMIN' | 'SUPER_ADMIN';
  libraryId: number | null; libraryName: string | null; designation: string | null; employeeRef: string | null; applicationNote: string | null;
  status: ApprovalStatus; appliedAt: string; reviewedAt: string | null; reviewedByName: string | null; rejectionReason: string | null;
}
export interface UserRow {
  id: number; fullName: string; email: string; phone: string | null; role: Role; status: string; emailVerified: boolean;
  libraryId: number | null; libraryName: string | null; approvalStatus: ApprovalStatus | null; createdAt: string; lastLoginAt: string | null;
}
export interface ConversationSummary { id: string; title: string | null; createdAt: string; lastMessageAt: string }
export interface TranscriptMessage { id: number; role: 'USER' | 'MODEL'; content: string; books: CatalogueItem[] | null; latencyMs: number | null; createdAt: string }
export interface AiStatus { enabled: boolean; configured: boolean; model: string | null }
