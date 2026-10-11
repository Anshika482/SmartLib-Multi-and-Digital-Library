package com.smartlib.resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.catalogue.HoldingStatus;
import com.smartlib.catalogue.LibraryHoldingRepository;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.storage.FileSniffer;
import com.smartlib.common.storage.StorageService;
import com.smartlib.common.time.AppClock;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.PageResponse;
import com.smartlib.library.LibraryRepository;
import com.smartlib.security.LibraryScope;
import com.smartlib.security.SmartLibPrincipal;
import com.smartlib.user.LibraryMembershipRepository;
import com.smartlib.user.MembershipStatus;

@Service
public class ResourceService {

    private static final long MAX_DOC_BYTES = 50L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 200L * 1024 * 1024;
    private static final Duration GRANT_TTL = Duration.ofMinutes(5);

    private final DigitalResourceRepository resources;
    private final LibraryRepository libraries;
    private final LibraryHoldingRepository holdings;
    private final LibraryMembershipRepository memberships;
    private final StorageService storage;
    private final FileTokens tokens;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final AppClock clock;

    public ResourceService(DigitalResourceRepository resources, LibraryRepository libraries, LibraryHoldingRepository holdings,
                           LibraryMembershipRepository memberships, StorageService storage, FileTokens tokens,
                           NamedParameterJdbcTemplate jdbc, AuditService audit, ApplicationEventPublisher events, AppClock clock) {
        this.resources = resources;
        this.libraries = libraries;
        this.holdings = holdings;
        this.memberships = memberships;
        this.storage = storage;
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    public record ResourceView(long id, String title, String description, ResourceType type, ResourceVisibility visibility,
                               String status, long libraryId, String libraryName, String librarySlug, Long bookId, String bookTitle,
                               String mimeType, Long sizeBytes, String originalFilename, boolean external, Instant createdAt) {
    }

    public record AccessGrant(long resourceId, ResourceType type, String url, boolean external, String mimeType,
                              String filename, Instant expiresAt) {
    }

    public record UpdateRequest(String title, String description, ResourceVisibility visibility, Long bookId, String status) {
    }

    // ---------- staff commands ----------

    @Transactional
    public ResourceView create(LibraryScope scope, String title, String description, ResourceType type, ResourceVisibility visibility,
                               Long bookId, String externalUrl, MultipartFile file) throws IOException {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw ApiException.validation("title", "Title is required (max 200 characters)");
        }
        if (type == null) {
            throw ApiException.validation("type", "Choose a resource type");
        }
        if (visibility == null) {
            throw ApiException.validation("visibility", "Choose who can access this resource");
        }
        checkBook(scope, bookId);
        DigitalResource r = new DigitalResource();
        r.setLibraryId(scope.libraryId());
        r.setBookId(bookId);
        r.setTitle(title.trim());
        r.setDescription(Texts.truncate(Texts.trimToNull(description), 2000));
        r.setType(type);
        r.setVisibility(visibility);
        r.setUploadedBy(scope.actorId());
        boolean hasFile = file != null && !file.isEmpty();
        String url = Texts.trimToNull(externalUrl);
        switch (type) {
            case LINK -> {
                r.setExternalUrl(validUrl(url));
            }
            case VIDEO -> {
                if (hasFile) {
                    storeFile(r, file, MAX_VIDEO_BYTES, List.of("video/mp4", "video/webm"));
                } else {
                    r.setExternalUrl(validUrl(url));
                }
            }
            case PDF -> storeFile(r, requireFile(file), MAX_DOC_BYTES, List.of("application/pdf"));
            case EPUB -> storeFile(r, requireFile(file), MAX_DOC_BYTES, List.of("application/epub+zip"));
        }
        resources.saveAndFlush(r);
        audit.record(AuditActions.RESOURCE_CREATED, "RESOURCE", r.getId(), scope.libraryId(),
                AuditService.details("type", type.name(), "visibility", visibility.name(), "title", r.getTitle()));
        events.publishEvent(new DomainEvents.ResourcePublished(r.getId()));
        return view(r.getId());
    }

    @Transactional
    public ResourceView update(LibraryScope scope, long id, UpdateRequest req) {
        DigitalResource r = resources.findByIdAndLibraryId(id, scope.libraryId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Resource not found."));
        if (req.title() != null) {
            if (req.title().isBlank() || req.title().length() > 200) {
                throw ApiException.validation("title", "Title is required (max 200 characters)");
            }
            r.setTitle(req.title().trim());
        }
        if (req.description() != null) {
            r.setDescription(Texts.truncate(Texts.trimToNull(req.description()), 2000));
        }
        if (req.visibility() != null) {
            r.setVisibility(req.visibility());
        }
        if (req.bookId() != null) {
            checkBook(scope, req.bookId() == 0 ? null : req.bookId());
            r.setBookId(req.bookId() == 0 ? null : req.bookId());
        }
        if (req.status() != null) {
            String s = req.status().toUpperCase(Locale.ROOT);
            if (!s.equals("ACTIVE") && !s.equals("ARCHIVED")) {
                throw ApiException.validation("status", "Status must be ACTIVE or ARCHIVED");
            }
            r.setStatus(s);
        }
        resources.saveAndFlush(r);
        audit.record("ARCHIVED".equals(r.getStatus()) ? AuditActions.RESOURCE_ARCHIVED : AuditActions.RESOURCE_UPDATED, "RESOURCE", id,
                scope.libraryId(), null);
        return view(id);
    }

    // ---------- access ----------

    /** Decides access server-side for every request; a file is only reachable through a signed URL valid for 5 minutes. */
    public AccessGrant access(long id, SmartLibPrincipal principal) {
        ResourceRow row = row(id);
        if (row == null || !"ACTIVE".equals(row.status()) || !row.libraryActive()) {
            throw ApiException.notFound(ErrorCodes.NOT_FOUND, "Resource not found.");
        }
        if (!canAccess(row.visibility(), row.libraryId(), principal)) {
            if (principal == null) {
                throw ApiException.unauthorized(ErrorCodes.LOGIN_REQUIRED, "Please log in to open this resource.");
            }
            throw ApiException.forbidden(ErrorCodes.RESOURCE_ACCESS_DENIED,
                    "This resource is available to members of " + row.libraryName() + " only. Join the library to access it.")
                    .with("libraryId", row.libraryId());
        }
        if (row.storageKey() == null) {
            return new AccessGrant(id, row.type(), row.externalUrl(), true, null, null, null);
        }
        Instant expires = clock.now().plus(GRANT_TTL);
        return new AccessGrant(id, row.type(), "/api/files/" + tokens.sign(id, expires), false, row.mimeType(), row.originalFilename(), expires);
    }

    public boolean canAccess(ResourceVisibility visibility, long libraryId, SmartLibPrincipal p) {
        return switch (visibility) {
            case PUBLIC -> true;
            case REGISTERED -> p != null;
            case LIBRARY_MEMBERS -> p != null && (p.operatesLibrary(libraryId) || memberships.findByUserIdAndLibraryId(p.userId(), libraryId)
                    .map(m -> m.getStatus() == MembershipStatus.ACTIVE).orElse(false));
        };
    }

    public DigitalResource fileFor(String token) {
        long id = tokens.verify(token, clock.now())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.RESOURCE_ACCESS_DENIED, "This file link has expired. Please open the resource again."));
        return resources.findById(id).filter(r -> "ACTIVE".equals(r.getStatus()) && r.getStorageKey() != null && storage.exists(r.getStorageKey()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "File not found."));
    }

    // ---------- listings ----------

    private static final String SELECT = """
            SELECT r.*, l.name AS lib_name, l.slug AS lib_slug, l.status AS lib_status, b.title AS book_title
            FROM digital_resources r JOIN libraries l ON l.id = r.library_id LEFT JOIN books b ON b.id = r.book_id""";

    public PageResponse<ResourceView> libraryResources(LibraryScope scope, String q, String type, String status, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource("lib", scope.libraryId());
        StringBuilder where = new StringBuilder(" WHERE r.library_id = :lib");
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where.append(" AND r.status = :status");
            p.addValue("status", status.toUpperCase(Locale.ROOT));
        }
        filters(where, p, q, type, null);
        return page(where.toString(), p, page, size);
    }

    /** Everything the caller may open: PUBLIC, REGISTERED, and LIBRARY_MEMBERS of libraries they belong to or staff. */
    public PageResponse<ResourceView> accessible(SmartLibPrincipal p, String q, String type, Long libraryId, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource("uid", p.userId());
        StringBuilder where = new StringBuilder(" WHERE r.status = 'ACTIVE' AND l.status = 'ACTIVE' AND (r.visibility IN ('PUBLIC','REGISTERED')");
        if (p.isSuperAdmin()) {
            where.append(" OR 1=1");
        } else {
            where.append(" OR EXISTS (SELECT 1 FROM library_memberships m WHERE m.user_id = :uid AND m.library_id = r.library_id AND m.status = 'ACTIVE')");
            if (p.isStaff() && p.libraryId() != null) {
                where.append(" OR r.library_id = :staffLib");
                params.addValue("staffLib", p.libraryId());
            }
        }
        where.append(')');
        filters(where, params, q, type, libraryId);
        return page(where.toString(), params, page, size);
    }

    public PageResponse<ResourceView> publicResources(String q, String type, Long libraryId, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE r.status = 'ACTIVE' AND l.status = 'ACTIVE'");
        filters(where, p, q, type, libraryId);
        return page(where.toString(), p, page, size);
    }

    private static void filters(StringBuilder where, MapSqlParameterSource p, String q, String type, Long libraryId) {
        if (q != null && !q.isBlank()) {
            where.append(" AND (r.title LIKE :q OR r.description LIKE :q OR b.title LIKE :q)");
            p.addValue("q", Texts.likeContains(q.trim()));
        }
        if (type != null && !type.isBlank() && !"ALL".equalsIgnoreCase(type)) {
            where.append(" AND r.type = :type");
            p.addValue("type", ResourceType.valueOf(type.toUpperCase(Locale.ROOT)).name());
        }
        if (libraryId != null) {
            where.append(" AND r.library_id = :libraryId");
            p.addValue("libraryId", libraryId);
        }
    }

    public ResourceView view(long id) {
        return page(" WHERE r.id = :id", new MapSqlParameterSource("id", id), 0, 1).content().get(0);
    }

    private PageResponse<ResourceView> page(String where, MapSqlParameterSource p, int page, int size) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM digital_resources r JOIN libraries l ON l.id = r.library_id"
                + " LEFT JOIN books b ON b.id = r.book_id" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<ResourceView> rows = jdbc.query(SELECT + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT :limit OFFSET :offset", p,
                (rs, i) -> {
                    Timestamp created = rs.getTimestamp("created_at");
                    long size1 = rs.getLong("size_bytes");
                    Long sizeBytes = rs.wasNull() ? null : size1;
                    return new ResourceView(rs.getLong("id"), rs.getString("title"), rs.getString("description"),
                            ResourceType.valueOf(rs.getString("type")), ResourceVisibility.valueOf(rs.getString("visibility")),
                            rs.getString("status"), rs.getLong("library_id"), rs.getString("lib_name"), rs.getString("lib_slug"),
                            (Long) rs.getObject("book_id", Long.class), rs.getString("book_title"), rs.getString("mime_type"), sizeBytes,
                            rs.getString("original_filename"), rs.getString("storage_key") == null, created.toInstant());
                });
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    // ---------- internals ----------

    record ResourceRow(long id, long libraryId, String libraryName, boolean libraryActive, ResourceType type,
                       ResourceVisibility visibility, String status, String storageKey, String externalUrl, String mimeType,
                       String originalFilename) {
    }

    private ResourceRow row(long id) {
        List<ResourceRow> rows = jdbc.query("SELECT r.*, l.name AS lib_name, l.status AS lib_status FROM digital_resources r"
                        + " JOIN libraries l ON l.id = r.library_id WHERE r.id = :id", new MapSqlParameterSource("id", id),
                (rs, i) -> new ResourceRow(rs.getLong("id"), rs.getLong("library_id"), rs.getString("lib_name"),
                        "ACTIVE".equals(rs.getString("lib_status")), ResourceType.valueOf(rs.getString("type")),
                        ResourceVisibility.valueOf(rs.getString("visibility")), rs.getString("status"), rs.getString("storage_key"),
                        rs.getString("external_url"), rs.getString("mime_type"), rs.getString("original_filename")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void checkBook(LibraryScope scope, Long bookId) {
        if (bookId == null) {
            return;
        }
        holdings.findByLibraryIdAndBookId(scope.libraryId(), bookId).filter(h -> h.getStatus() == HoldingStatus.ACTIVE)
                .orElseThrow(() -> ApiException.validation("bookId", "Link resources only to titles in your library's catalogue"));
    }

    private static MultipartFile requireFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("file", "Please choose a file to upload");
        }
        return file;
    }

    private void storeFile(DigitalResource r, MultipartFile file, long maxBytes, List<String> allowed) throws IOException {
        if (file.getSize() > maxBytes) {
            throw ApiException.badRequest(ErrorCodes.FILE_TOO_LARGE, "File is too large (max " + maxBytes / (1024 * 1024) + " MB).");
        }
        try (InputStream in = file.getInputStream()) {
            byte[] head = FileSniffer.head(in, 64);
            FileSniffer.Sniffed sniffed = FileSniffer.detect(head).filter(s -> allowed.contains(s.mime()))
                    .orElseThrow(() -> ApiException.badRequest(ErrorCodes.UNSUPPORTED_FILE,
                            "The file content does not match the selected type (" + r.getType() + ")."));
            String key = storage.store("resources", new SequenceInputStream(new ByteArrayInputStream(head), in), sniffed.extension());
            r.setStorageKey(key);
            r.setMimeType(sniffed.mime());
            r.setSizeBytes(file.getSize());
            String name = file.getOriginalFilename();
            r.setOriginalFilename(name == null ? null : Texts.truncate(name.replaceAll("[\\r\\n\"]", "_"), 255));
        }
    }

    private static String validUrl(String url) {
        if (url == null) {
            throw ApiException.validation("externalUrl", "Enter the resource URL");
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null || url.length() > 1000) {
                throw new IllegalArgumentException();
            }
            return url;
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("externalUrl", "Enter a valid http(s) URL");
        }
    }
}
