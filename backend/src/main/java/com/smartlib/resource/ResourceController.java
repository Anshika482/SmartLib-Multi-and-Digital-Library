package com.smartlib.resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.smartlib.common.storage.StorageService;
import com.smartlib.common.web.PageResponse;
import com.smartlib.security.CurrentUser;
import com.smartlib.security.LibraryScope;

@RestController
public class ResourceController {

    private final ResourceService resources;
    private final StorageService storage;

    public ResourceController(ResourceService resources, StorageService storage) {
        this.resources = resources;
        this.storage = storage;
    }

    // ----- public & member -----

    @GetMapping("/api/public/resources")
    public PageResponse<ResourceService.ResourceView> publicList(@RequestParam(required = false) String q,
                                                                 @RequestParam(required = false) String type,
                                                                 @RequestParam(required = false) Long libraryId,
                                                                 @RequestParam(required = false) Integer page,
                                                                 @RequestParam(required = false) Integer size) {
        return resources.publicResources(q, type, libraryId, PageResponse.clampPage(page), PageResponse.clampSize(size, 12, 48));
    }

    @GetMapping("/api/me/resources")
    public PageResponse<ResourceService.ResourceView> mine(@RequestParam(required = false) String q,
                                                           @RequestParam(required = false) String type,
                                                           @RequestParam(required = false) Long libraryId,
                                                           @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size) {
        return resources.accessible(CurrentUser.require(), q, type, libraryId, PageResponse.clampPage(page), PageResponse.clampSize(size, 12, 48));
    }

    @PostMapping("/api/resources/{id}/access")
    public ResourceService.AccessGrant access(@PathVariable long id) {
        return resources.access(id, CurrentUser.optional().orElse(null));
    }

    @GetMapping("/api/files/{token}")
    public ResponseEntity<Resource> file(@PathVariable String token, @RequestParam(required = false, defaultValue = "false") boolean download) {
        DigitalResource r = resources.fileFor(token);
        boolean attachment = download || r.getType() == ResourceType.EPUB;
        String filename = r.getOriginalFilename() == null ? "resource-" + r.getId() : r.getOriginalFilename();
        ContentDisposition disposition = (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(filename, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(r.getMimeType() == null ? "application/octet-stream" : r.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(storage.resource(r.getStorageKey()));
    }

    // ----- staff -----

    @GetMapping("/api/manage/libraries/{libraryId}/resources")
    public PageResponse<ResourceService.ResourceView> libraryList(LibraryScope scope, @RequestParam(required = false) String q,
                                                                  @RequestParam(required = false) String type,
                                                                  @RequestParam(required = false) String status,
                                                                  @RequestParam(required = false) Integer page,
                                                                  @RequestParam(required = false) Integer size) {
        return resources.libraryResources(scope, q, type, status, PageResponse.clampPage(page), PageResponse.clampSize(size, 20, 100));
    }

    @PostMapping(path = "/api/manage/libraries/{libraryId}/resources", consumes = "multipart/form-data")
    public ResponseEntity<ResourceService.ResourceView> create(LibraryScope scope, @RequestParam String title,
                                                               @RequestParam(required = false) String description,
                                                               @RequestParam ResourceType type,
                                                               @RequestParam ResourceVisibility visibility,
                                                               @RequestParam(required = false) Long bookId,
                                                               @RequestParam(required = false) String externalUrl,
                                                               @RequestPart(name = "file", required = false) MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(resources.create(scope, title, description, type, visibility, bookId, externalUrl, file));
    }

    @PatchMapping("/api/manage/libraries/{libraryId}/resources/{id}")
    public ResourceService.ResourceView update(LibraryScope scope, @PathVariable long id, @RequestBody ResourceService.UpdateRequest req) {
        return resources.update(scope, id, req);
    }
}
