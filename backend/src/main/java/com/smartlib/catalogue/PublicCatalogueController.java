package com.smartlib.catalogue;

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

import com.smartlib.catalogue.CatalogueDtos.BookDetails;
import com.smartlib.catalogue.CatalogueDtos.CatalogueItem;
import com.smartlib.catalogue.CatalogueDtos.CatalogueQuery;
import com.smartlib.catalogue.CatalogueDtos.CategoryWithCount;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.storage.StorageService;
import com.smartlib.common.web.PageResponse;

/** Public, unauthenticated, cross-library catalogue. Only bibliographic data and availability counts are exposed. */
@RestController
@RequestMapping("/api/public")
public class PublicCatalogueController {

    private final CatalogueQueryService catalogue;
    private final BookRepository books;
    private final StorageService storage;

    public PublicCatalogueController(CatalogueQueryService catalogue, BookRepository books, StorageService storage) {
        this.catalogue = catalogue;
        this.books = books;
        this.storage = storage;
    }

    @GetMapping("/catalogue")
    public PageResponse<CatalogueItem> search(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) Long libraryId,
                                              @RequestParam(required = false) Long categoryId,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String language,
                                              @RequestParam(required = false, defaultValue = "false") boolean available,
                                              @RequestParam(required = false) String sort,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        String query = q == null ? null : q.trim();
        if (query != null && query.length() > 100) {
            query = query.substring(0, 100);
        }
        return catalogue.search(new CatalogueQuery(query, libraryId, categoryId, category, language, available, sort,
                PageResponse.clampPage(page), PageResponse.clampSize(size, 12, 48)));
    }

    @GetMapping("/catalogue/{bookId}")
    public BookDetails details(@PathVariable long bookId) {
        return catalogue.details(bookId).orElseThrow(() -> ApiException.notFound(ErrorCodes.BOOK_NOT_FOUND, "Book not found."));
    }

    @GetMapping("/featured")
    public List<CatalogueItem> featured(@RequestParam(required = false) Integer limit) {
        return catalogue.featured(PageResponse.clampSize(limit, 8, 24));
    }

    @GetMapping("/categories")
    public List<CategoryWithCount> categories() {
        return catalogue.categories();
    }

    @GetMapping("/languages")
    public List<String> languages() {
        return catalogue.languages();
    }

    @GetMapping("/covers/{bookId}")
    public ResponseEntity<Resource> cover(@PathVariable long bookId) {
        Book book = books.findById(bookId).filter(b -> b.getCoverSource() == CoverSource.LOCAL && storage.exists(b.getCoverKey()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Cover not found."));
        Resource resource = storage.resource(book.getCoverKey());
        MediaType type = MediaTypeFactory.getMediaType(book.getCoverKey()).orElse(MediaType.IMAGE_JPEG);
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(resource);
    }
}
