package com.smartlib.library;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.storage.StorageService;
import com.smartlib.platform.PlatformSettings;

@RestController
@RequestMapping("/api/public")
public class PublicLibraryController {

    private final LibraryQueryService query;
    private final LibraryRepository libraries;
    private final StorageService storage;
    private final PlatformSettings settings;

    public PublicLibraryController(LibraryQueryService query, LibraryRepository libraries, StorageService storage,
                                   PlatformSettings settings) {
        this.query = query;
        this.libraries = libraries;
        this.storage = storage;
        this.settings = settings;
    }

    public record SiteInfo(LibraryQueryService.PlatformStats stats, String announcement, boolean aiEnabled) {
    }

    @GetMapping("/libraries")
    public List<LibraryQueryService.LibraryCard> list(@RequestParam(required = false) String q,
                                                      @RequestParam(required = false) String city) {
        return query.list(q, city);
    }

    @GetMapping("/libraries/{slug}")
    public LibraryQueryService.LibraryDetails bySlug(@PathVariable String slug) {
        return query.bySlug(slug).orElseThrow(() -> ApiException.notFound(ErrorCodes.LIBRARY_NOT_FOUND, "Library not found."));
    }

    @GetMapping("/libraries/{slug}/image")
    public ResponseEntity<Resource> image(@PathVariable String slug) {
        Library library = libraries.findBySlug(slug).filter(l -> l.getImageKey() != null && storage.exists(l.getImageKey()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Image not found."));
        MediaType type = MediaTypeFactory.getMediaType(library.getImageKey()).orElse(MediaType.IMAGE_JPEG);
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(storage.resource(library.getImageKey()));
    }

    @GetMapping("/site")
    public SiteInfo site() {
        return new SiteInfo(query.stats(), settings.get(PlatformSettings.ANNOUNCEMENT, ""),
                settings.isEnabled(PlatformSettings.AI_ENABLED));
    }
}
