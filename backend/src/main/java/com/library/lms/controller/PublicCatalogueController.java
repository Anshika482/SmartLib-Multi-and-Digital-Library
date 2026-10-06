package com.library.lms.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.PagedResponse;
import com.library.lms.dto.PublicBookResponse;
import com.library.lms.dto.PublicLibraryResponse;
import com.library.lms.service.PublicCatalogueService;

/**
 * The only endpoints an unauthenticated visitor may call for data.
 *
 * <p>Everything here is under {@code /api/public} so the boundary is visible
 * in the URL, in the security configuration and in a server log. A reader
 * checking what a stranger can reach looks in one place.</p>
 *
 * <p><b>Read-only, and narrow by construction.</b> Two GETs, no path variables,
 * no ids in or out, and responses built from records that carry bibliographic
 * fields only. There is no public endpoint for a single book: a stranger may
 * search the catalogue, not walk it.</p>
 *
 * <p>These methods take no {@code Authentication}. They behave identically for
 * a signed-in caller, which is why the signed-in screens keep using the
 * authenticated catalogue - that one is scoped to the caller's own library and
 * carries the copy counts this one deliberately omits.</p>
 */
@RestController
@RequestMapping("/api/public")
public class PublicCatalogueController {

    private final PublicCatalogueService publicCatalogueService;

    public PublicCatalogueController(PublicCatalogueService publicCatalogueService) {
        this.publicCatalogueService = publicCatalogueService;
    }

    /**
     * GET /api/public/catalogue - bibliographic search across every library.
     *
     * @param keyword matched against title, author and ISBN; omitted means all
     */
    @GetMapping("/catalogue")
    public ResponseEntity<PagedResponse<PublicBookResponse>> catalogue(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ResponseEntity.ok(publicCatalogueService.search(keyword, page, size));
    }

    /** GET /api/public/libraries - the libraries on the platform, by name. */
    @GetMapping("/libraries")
    public ResponseEntity<List<PublicLibraryResponse>> libraries() {
        return ResponseEntity.ok(publicCatalogueService.libraries());
    }
}
