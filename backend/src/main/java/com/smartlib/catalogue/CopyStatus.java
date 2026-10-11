package com.smartlib.catalogue;

/**
 * Physical copy state. A PENDING request does not hold a copy (prevents hoarding); a copy becomes RESERVED only when
 * a librarian approves a request, then BORROWED when issued.
 */
public enum CopyStatus {
    AVAILABLE, RESERVED, BORROWED, LOST, DAMAGED, WITHDRAWN
}
