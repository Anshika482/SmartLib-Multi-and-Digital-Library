package com.smartlib.fine;

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

import com.smartlib.common.web.PageResponse;
import com.smartlib.fine.FineDtos.FineView;
import com.smartlib.security.LibraryScope;

@RestController
@RequestMapping("/api/manage/libraries/{libraryId}/fines")
public class ManageFineController {

    private final FineService fines;

    public ManageFineController(FineService fines) {
        this.fines = fines;
    }

    @GetMapping
    public PageResponse<FineView> list(LibraryScope scope, @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return fines.libraryFines(scope, status, q, PageResponse.clampPage(page), PageResponse.clampSize(size, 20, 100));
    }

    @PostMapping
    public ResponseEntity<FineView> create(LibraryScope scope, @Valid @RequestBody FineDtos.CreateFineRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(fines.createManual(scope, req));
    }

    @PostMapping("/{fineId}/waive")
    public FineView waive(LibraryScope scope, @PathVariable long fineId, @Valid @RequestBody FineDtos.WaiveRequest req) {
        return fines.waive(scope, fineId, req.reason());
    }
}
