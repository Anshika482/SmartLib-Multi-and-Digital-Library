package com.smartlib.circulation;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.circulation.CirculationDtos.LoanView;
import com.smartlib.circulation.CirculationDtos.RequestView;
import com.smartlib.common.web.PageResponse;
import com.smartlib.security.LibraryScope;

@RestController
@RequestMapping("/api/manage/libraries/{libraryId}")
public class ManageCirculationController {

    private final CirculationService circulation;
    private final CirculationQueries queries;

    public ManageCirculationController(CirculationService circulation, CirculationQueries queries) {
        this.circulation = circulation;
        this.queries = queries;
    }

    @GetMapping("/requests")
    public PageResponse<RequestView> requests(LibraryScope scope, @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return queries.libraryRequests(scope.libraryId(), status, q, PageResponse.clampPage(page), PageResponse.clampSize(size, 20, 100));
    }

    @PostMapping("/requests/{requestId}/approve")
    public RequestView approve(LibraryScope scope, @PathVariable long requestId,
                               @RequestBody(required = false) CirculationDtos.ApproveRequest req) {
        return circulation.approve(scope, requestId, req == null ? null : req.copyId());
    }

    @PostMapping("/requests/{requestId}/reject")
    public RequestView reject(LibraryScope scope, @PathVariable long requestId, @Valid @RequestBody CirculationDtos.RejectRequest req) {
        return circulation.reject(scope, requestId, req.reason());
    }

    @PostMapping("/requests/{requestId}/issue")
    public LoanView issue(LibraryScope scope, @PathVariable long requestId) {
        return circulation.issue(scope, requestId);
    }

    @GetMapping("/borrowings")
    public PageResponse<LoanView> borrowings(LibraryScope scope, @RequestParam(required = false) String filter,
                                             @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return queries.libraryLoans(scope.libraryId(), filter, q, PageResponse.clampPage(page), PageResponse.clampSize(size, 20, 100));
    }

    @PostMapping("/borrowings/{borrowingId}/return")
    public CirculationService.ReturnResult processReturn(LibraryScope scope, @PathVariable long borrowingId,
                                                         @Valid @RequestBody CirculationDtos.ReturnRequest req) {
        return circulation.processReturn(scope, borrowingId, req);
    }
}
