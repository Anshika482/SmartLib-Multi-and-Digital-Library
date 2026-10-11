package com.smartlib.circulation;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import com.smartlib.security.CurrentUser;
import com.smartlib.security.SmartLibPrincipal;

@RestController
@RequestMapping("/api/me")
public class MemberCirculationController {

    private final RequestService requests;
    private final CirculationQueries queries;
    private final MembershipService memberships;

    public MemberCirculationController(RequestService requests, CirculationQueries queries, MembershipService memberships) {
        this.requests = requests;
        this.queries = queries;
        this.memberships = memberships;
    }

    @PostMapping("/requests")
    public ResponseEntity<RequestView> create(@Valid @RequestBody CirculationDtos.CreateRequest req) {
        SmartLibPrincipal member = CurrentUser.requireMember();
        return ResponseEntity.status(HttpStatus.CREATED).body(requests.create(member, req));
    }

    @GetMapping("/requests")
    public PageResponse<RequestView> list(@RequestParam(required = false) String status, @RequestParam(required = false) Long bookId,
                                          @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        SmartLibPrincipal member = CurrentUser.requireMember();
        return queries.memberRequests(member.userId(), status, bookId, PageResponse.clampPage(page), PageResponse.clampSize(size, 10, 50));
    }

    @PostMapping("/requests/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable long id) {
        requests.cancel(CurrentUser.requireMember(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/borrowings")
    public PageResponse<LoanView> loans(@RequestParam(required = false) String filter, @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        SmartLibPrincipal member = CurrentUser.requireMember();
        return queries.memberLoans(member.userId(), filter, PageResponse.clampPage(page), PageResponse.clampSize(size, 10, 50));
    }

    @GetMapping("/memberships")
    public List<CirculationDtos.MembershipView> memberships() {
        return memberships.mine(CurrentUser.requireMember().userId());
    }

    @PostMapping("/memberships")
    public CirculationDtos.MembershipView join(@Valid @RequestBody CirculationDtos.JoinLibraryRequest req) {
        return memberships.join(CurrentUser.requireMember().userId(), req.libraryId());
    }
}
