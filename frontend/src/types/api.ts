/**
 * The shapes the backend actually returns.
 *
 * Written from the Java DTOs rather than guessed, and kept narrow on purpose:
 * a type here is a claim about the API, and a wrong claim is worse than no
 * claim. Anything not yet needed by a built page is not declared yet.
 */

/** Authority strings as the backend issues them, prefix included. */
export type Role = 'ROLE_SUPER_ADMIN' | 'ROLE_ADMIN' | 'ROLE_LIBRARIAN' | 'ROLE_MEMBER';

/** POST /api/auth/login */
export interface LoginResponse {
  token: string;
  refreshToken: string;
}

/** GET /api/users/me - never carries a password or a hash. */
export interface UserProfile {
  id: number;
  username: string;
  email: string;
  role: Role;
  enabled: boolean;
  accountNonLocked: boolean;
}

/** Every error the API produces has this shape - see GlobalExceptionHandler. */
export interface ApiErrorBody {
  status: number;
  message: string;
  timestamp: string;
}

/** Every paged list the API produces has this shape - see PagedResponse. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Whether an account may manage a library rather than only use it. */
export function isStaff(role: Role): boolean {
  return role === 'ROLE_SUPER_ADMIN' || role === 'ROLE_ADMIN' || role === 'ROLE_LIBRARIAN';
}

/** How a role should be named to a person. */
export function roleLabel(role: Role): string {
  switch (role) {
    case 'ROLE_SUPER_ADMIN':
      return 'System administrator';
    case 'ROLE_ADMIN':
      return 'Administrator';
    case 'ROLE_LIBRARIAN':
      return 'Librarian';
    case 'ROLE_MEMBER':
      return 'Member';
  }
}

/** GET /api/books - see BookResponse. */
export interface BookSummary {
  id: number;
  title: string;
  author: string;
  isbn: string;
  categoryId: number | null;
  categoryName: string | null;
  totalCopies: number;
  availableCopies: number;

  /** Whether this book has a cover image, so a card can decide without fetching. */
  hasCover: boolean;

  /**
   * Where to read the cover, or null when there is none.
   *
   * An API path the backend supplies - never a storage key or a filesystem
   * path. It needs the caller's token like any other endpoint, so it is used
   * on signed-in screens only.
   */
  coverUrl: string | null;
}

/**
 * One report - see ReportResponse.
 *
 * <p>Every figure was counted by the database. Nothing here is added up in the
 * browser: a total worked out from one page of rows would be wrong the moment
 * there were two pages, and these numbers are the library's accounts.</p>
 */
export interface Report {
  scope: Role;
  /** True only for a super administrator, and then libraryName is null. */
  systemWide: boolean;
  libraryName: string | null;
  from: string;
  to: string;
  totals: ReportTotals;
  mostIssued: TitleCount[];
  categories: CategoryCount[];
  circulation: MonthPoint[];
  overdueTrend: MonthCount[];
}

/**
 * The headline figures.
 *
 * Some describe the range and some describe today - see ReportResponse.Totals,
 * which explains which and why.
 */
export interface ReportTotals {
  totalBooks: number;
  totalMembers: number;
  issues: number;
  returns: number;
  activeLoans: number;
  overdueLoans: number;
  finesRaised: number;
  finesPaid: number;
  finesOutstanding: number;
  paymentsTaken: number;
}

export interface TitleCount {
  title: string;
  author: string;
  issues: number;
}

export interface CategoryCount {
  category: string;
  issues: number;
}

export interface MonthPoint {
  year: number;
  month: number;
  issues: number;
  returns: number;
}

export interface MonthCount {
  year: number;
  month: number;
  count: number;
}

/** A month named the way a person reads it, from the year and month the API sends. */
export function monthLabel(year: number, month: number): string {
  const names = [
    'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
    'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
  ];
  return `${names[month - 1] ?? String(month)} ${year}`;
}

/** How a payment attempt ended - see PaymentStatus. */
export type PaymentStatus = 'CREATED' | 'SUCCEEDED' | 'FAILED';

/**
 * An order opened with the payment provider - see PaymentOrderResponse.
 *
 * <p><b>The amount is the server's.</b> It comes from the fine on the loan and
 * is never sent by this client; the provider is told it by the server when the
 * order is opened. Nothing in the browser can change what is charged.</p>
 *
 * <p>{@code keyId} is the provider's <i>publishable</i> key, which is meant to
 * be in a page. The secret that signs and verifies never leaves the server.</p>
 */
export interface PaymentOrder {
  paymentId: number;
  provider: string;
  providerOrderId: string;
  keyId: string;
  amount: number;
  currency: string;
  loanId: number;
}

/** What the server says after checking the provider's signature - see PaymentResponse. */
export interface PaymentResult {
  paymentId: number;
  status: PaymentStatus;
  providerPaymentId: string | null;
  loanId: number;
  finePaymentStatus: FinePaymentStatus;
  paidAt: string | null;
}

/** The states a loan can be in - see TransactionStatus. */
export type TransactionStatus = 'ISSUED' | 'RETURNED' | 'OVERDUE';

/** Whether a fine has been settled - see FinePaymentStatus. */
export type FinePaymentStatus = 'NOT_REQUIRED' | 'UNPAID' | 'PAID';

/** One borrowing - see TransactionResponse. */
export interface Transaction {
  id: number;
  bookId: number;

  /** Flattened off the book, so a loans screen is one request rather than one per row. */
  bookTitle: string | null;
  bookAuthor: string | null;

  userId: number;
  issueDate: string;
  dueDate: string;
  returnDate: string | null;

  /** What is owed: growing for an open overdue loan, fixed once returned. */
  fineAmount: number | null;

  /**
   * How many days past due, counted by the server.
   *
   * Sent rather than worked out here on purpose: subtracting two dates in the
   * browser would be a second copy of the overdue rule, and the browser's idea
   * of today is not the server's.
   */
  daysOverdue: number;

  status: TransactionStatus;
  finePaymentStatus: FinePaymentStatus;
  finePaidAt: string | null;
}

/** Whether a loan is still out. Mirrors OverduePolicy.OPEN_STATUSES. */
export function isLoanOpen(status: TransactionStatus): boolean {
  return status === 'ISSUED' || status === 'OVERDUE';
}

/** How a loan's state should be named to a person. */
export function loanStatusLabel(status: TransactionStatus): string {
  switch (status) {
    case 'ISSUED':
      return 'On loan';
    case 'OVERDUE':
      return 'Overdue';
    case 'RETURNED':
      return 'Returned';
  }
}

/** Plain words for how late something is. Never a bare number on its own. */
export function overdueLabel(days: number): string {
  return days === 1 ? '1 day late' : `${days} days late`;
}

/**
 * Where a member's request for a book has got to - see BorrowRequestStatus.
 *
 * REQUESTED and APPROVED are the live states; the other three are terminal.
 * Approval is not issue: a request becomes FULFILLED only when a copy is
 * actually handed over, and the loan takes over from there.
 */
export type BorrowRequestStatus = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'FULFILLED';

/** GET /api/borrow-requests - see BorrowRequestResponse. */
export interface BorrowRequest {
  id: number;
  bookId: number;
  bookTitle: string;
  bookAuthor: string;

  /** Who asked. Null on a member's own list, where the only name it could be is theirs. */
  memberName: string | null;

  status: BorrowRequestStatus;
  requestedAt: string;
  decidedAt: string | null;

  /** The loan it became, once a copy was handed over. */
  transactionId: number | null;
}

/** Whether a request is still going somewhere, and so can be acted on. */
export function isRequestActive(status: BorrowRequestStatus): boolean {
  return status === 'REQUESTED' || status === 'APPROVED';
}

/** How a request's state should be named to a person. */
export function requestStatusLabel(status: BorrowRequestStatus): string {
  switch (status) {
    case 'REQUESTED':
      return 'Waiting';
    case 'APPROVED':
      return 'Ready to collect';
    case 'REJECTED':
      return 'Declined';
    case 'CANCELLED':
      return 'Withdrawn';
    case 'FULFILLED':
      return 'Collected';
  }
}

/** The badge colour each state reads as. Never the only carrier - the label says it too. */
export function requestStatusTone(status: BorrowRequestStatus): 'available' | 'low' | 'out' | 'accent' {
  switch (status) {
    case 'APPROVED':
      return 'available';
    case 'REQUESTED':
      return 'low';
    case 'REJECTED':
      return 'out';
    default:
      return 'accent';
  }
}

/** GET /api/categories - see CategoryResponse. Two fields, and that is all it has. */
export interface Category {
  id: number;
  name: string;
}

/** The kinds of digital resource the backend stores - see ResourceType. */
export type ResourceType = 'PDF' | 'EPUB' | 'VIDEO' | 'LINK';

/** GET /api/digital-resources - see DigitalResourceResponse. */
export interface DigitalResource {
  id: number;
  bookId: number;
  bookTitle: string;
  title: string;
  description: string | null;
  resourceType: ResourceType;
  resourceUrl: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/** How a resource type should be named to a person. */
export function resourceTypeLabel(type: ResourceType): string {
  switch (type) {
    case 'PDF':
      return 'PDF';
    case 'EPUB':
      return 'E-book';
    case 'VIDEO':
      return 'Video';
    case 'LINK':
      return 'Web link';
  }
}

/**
 * GET /api/public/catalogue - what a visitor who has not signed in may see.
 *
 * Bibliographic facts only. There is no id, no copy count and no library id:
 * the backend's PublicBookResponse does not carry them, and this type says so
 * loudly enough that nobody writes a screen expecting one.
 */
export interface PublicBook {
  title: string;
  author: string;
  isbn: string;
  categoryName: string | null;
  libraryName: string | null;
}

/**
 * GET /api/public/libraries - a library, by name, with a real title count.
 *
 * The id is what registration sends to say which library is being joined. Only
 * libraries with an approved administrator are listed, so everything here is a
 * library somebody can actually join.
 */
export interface PublicLibrary {
  id: number;
  name: string;
  titleCount: number;
}

/** POST /api/chat - the assistant's answer. */
export interface ChatReply {
  reply: string;
  assistant: string;
  answeredAt: string;
}

/** One turn in the on-screen conversation. Local only; the API is stateless. */
export interface ChatTurn {
  id: number;
  role: 'you' | 'assistant';
  text: string;
}

/**
 * What somebody is applying to be.
 *
 * Mirrors the backend RegistrationType, which is deliberately not a role: the
 * server maps these to roles itself, and there is no value here - or anywhere
 * in the request - that names a privileged one.
 */
export type RegistrationType = 'MEMBER' | 'LIBRARIAN' | 'ADMIN';

/** Whether an account is usable, or waiting on somebody. */
export type RegistrationStatus = 'APPROVED' | 'PENDING' | 'REJECTED';

/** POST /api/auth/register */
export interface RegistrationRequest {
  type: RegistrationType;
  username: string;
  email: string;
  fullName: string;
  password: string;
  /** Required for MEMBER and LIBRARIAN. */
  libraryId?: number;
  /** Required for ADMIN, which opens a new library. */
  libraryName?: string;
}

/** What registering produced. Never a token - registering is not signing in. */
export interface RegistrationResponse {
  status: RegistrationStatus;
  message: string;
}

/** How a registration type should be named to a person. */
export function registrationTypeLabel(type: RegistrationType): string {
  switch (type) {
    case 'MEMBER':
      return 'Member';
    case 'LIBRARIAN':
      return 'Librarian';
    case 'ADMIN':
      return 'Library administrator';
  }
}

/**
 * GET /api/dashboard - what this account sees when it opens the application.
 *
 * Every section is null unless the caller's role is entitled to it. That is
 * decided on the server, which never computes a section it will not send, so a
 * null here means the figure was never gathered rather than hidden.
 *
 * Every number is real: a count or a sum the database answered, scoped to the
 * caller's own library.
 */
export interface Dashboard {
  role: Role;
  libraryName: string | null;
  catalogue: CatalogueCounts | null;
  member: MemberCounts | null;
  circulation: CirculationCounts | null;
  people: PeopleCounts | null;
  system: SystemCounts | null;
  recentActivity: ActivityEntry[];
}

export interface CatalogueCounts {
  titles: number;
  categories: number;
  digitalResources: number;
}

export interface MemberCounts {
  currentLoans: number;
  overdueLoans: number;
  unpaidFines: number;
  amountOwed: number;
}

export interface CirculationCounts {
  activeLoans: number;
  overdueLoans: number;
  unpaidFines: number;
  finesOutstanding: number;
}

export interface PeopleCounts {
  members: number;
  librarians: number;
  administrators: number;
  pendingRegistrations: number;
}

export interface SystemCounts {
  libraries: number;
  accounts: number;
  pendingLibraryApplications: number;
}

/** One audit line as a dashboard shows it: what happened and when, never who. */
export interface ActivityEntry {
  action: string;
  occurredAt: string;
}

/** An audit action name as a person should read it. */
export function activityLabel(action: string): string {
  return action
    .toLowerCase()
    .split('_')
    .join(' ')
    .replace(/^./, (first) => first.toUpperCase());
}
