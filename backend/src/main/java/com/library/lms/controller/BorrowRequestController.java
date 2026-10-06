package com.library.lms.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.BorrowRequestResponse;
import com.library.lms.dto.CreateBorrowRequest;
import com.library.lms.dto.IssueRequestedBookRequest;
import com.library.lms.dto.PagedResponse;
import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.BorrowRequestStatus;
import com.library.lms.service.BorrowRequestService;

import jakarta.validation.Valid;

/**
 * Asking for a book, and what the desk does about it.
 *
 * <p>The caller is always taken from {@link Authentication} and never from a
 * path or a body, which is why no method here accepts a member id. A request is
 * acted on by its own id, and the service reads the borrower off the row.
 *
 * <p>Nothing in this class decides authorization. The filter chain refuses a
 * member on the staff paths and {@link BorrowRequestService} checks the role
 * again before it reads anything - two independent locks, of which this
 * controller is neither.
 */
@RestController
@RequestMapping("/api/borrow-requests")
public class BorrowRequestController {

    private final BorrowRequestService borrowRequestService;

    public BorrowRequestController(BorrowRequestService borrowRequestService) {
        this.borrowRequestService = borrowRequestService;
    }

    // ---------- the member ----------

    /**
     * POST /api/borrow-requests - a member asks for a book.
     *
     * <p>201, because a request now exists that did not before. The body names
     * only the book; who is asking comes from the token.</p>
     */
    @PostMapping
    public ResponseEntity<BorrowRequestResponse> request(@Valid @RequestBody CreateBorrowRequest request,
            Authentication authentication) {
        BorrowRequestResponse created = borrowRequestService.request(request.getBookId(), authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * GET /api/borrow-requests/mine - the caller's own requests.
     *
     * <p>An exact path with no id in it, so there is nothing to substitute: the
     * only list it can return is the caller's.</p>
     */
    @GetMapping("/mine")
    public ResponseEntity<PagedResponse<BorrowRequestResponse>> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "requestedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction,
            Authentication authentication) {
        return ResponseEntity.ok(
                borrowRequestService.mine(page, size, sortBy, direction, authentication.getName()));
    }

    /** POST /api/borrow-requests/{id}/cancel - a member withdraws their own request. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<BorrowRequestResponse> cancel(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(borrowRequestService.cancel(id, authentication.getName()));
    }

    // ---------- the desk ----------

    /**
     * GET /api/borrow-requests - the library's queue.
     *
     * <p>{@code status} is optional and defaults to what is waiting, which is
     * the working list. Naming one reviews what was decided.</p>
     */
    @GetMapping
    public ResponseEntity<PagedResponse<BorrowRequestResponse>> queue(
            @RequestParam(required = false, defaultValue = "REQUESTED") BorrowRequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "requestedAt") String sortBy,
            @RequestParam(defaultValue = "asc") String direction,
            Authentication authentication) {
        return ResponseEntity.ok(
                borrowRequestService.queue(status, page, size, sortBy, direction, authentication.getName()));
    }

    /** GET /api/borrow-requests/{id} - one request: the caller's own, or any in a staff caller's library. */
    @GetMapping("/{id}")
    public ResponseEntity<BorrowRequestResponse> getById(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(borrowRequestService.getById(id, authentication.getName()));
    }

    /** POST /api/borrow-requests/{id}/approve - staff agree. This does not issue the book. */
    @PostMapping("/{id}/approve")
    public ResponseEntity<BorrowRequestResponse> approve(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(borrowRequestService.approve(id, authentication.getName()));
    }

    /** POST /api/borrow-requests/{id}/reject - staff refuse. */
    @PostMapping("/{id}/reject")
    public ResponseEntity<BorrowRequestResponse> reject(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(borrowRequestService.reject(id, authentication.getName()));
    }

    /**
     * POST /api/borrow-requests/{id}/issue - the copy is handed over.
     *
     * <p>Answers with the <b>loan</b>, not the request: from here the
     * transaction is what matters, and it is what the return path will need.
     * The loan itself is written by the existing issue service, unchanged.</p>
     */
    @PostMapping("/{id}/issue")
    public ResponseEntity<TransactionResponse> issue(@PathVariable Long id,
            @Valid @RequestBody IssueRequestedBookRequest request,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                borrowRequestService.issue(id, request.getDueDate(), authentication.getName()));
    }
}
