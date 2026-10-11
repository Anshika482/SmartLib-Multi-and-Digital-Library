package com.smartlib.catalogue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.smartlib.common.config.AppProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Resolves ISBN-based covers once per book (Open Library cover id, then Google Books) and stores the result, so the
 * catalogue never calls third parties while rendering. Open Library cover-ID URLs are not subject to the per-IP
 * rate limit that applies to ISBN-keyed cover URLs.
 */
@Service
public class CoverResolver {

    private static final Logger log = LoggerFactory.getLogger(CoverResolver.class);
    static final String USER_AGENT = "SmartLib/1.0 (educational multi-library platform)";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AppProperties.Covers config;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits = new Semaphore(3);

    public CoverResolver(JdbcTemplate jdbc, ObjectMapper mapper, AppProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.config = properties.covers();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public void resolveLater(long bookId) {
        if (config.enabled()) {
            executor.submit(() -> resolve(bookId));
        }
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 20_000)
    public void sweep() {
        if (!config.enabled()) {
            return;
        }
        List<Long> ids = jdbc.queryForList("""
                SELECT id FROM books WHERE cover_source = 'UNCHECKED'
                   OR (cover_source = 'NONE' AND isbn13 IS NOT NULL AND (cover_checked_at IS NULL OR cover_checked_at < ?))
                ORDER BY id LIMIT 40""", Long.class, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        ids.forEach(this::resolve);
    }

    public void resolve(long bookId) {
        if (!config.enabled()) {
            return;
        }
        String isbn = jdbc.query("SELECT isbn13 FROM books WHERE id = ? AND cover_source <> 'LOCAL'",
                rs -> rs.next() ? Optional.ofNullable(rs.getString(1)).orElse("") : null, bookId);
        if (isbn == null) {
            return;
        }
        if (isbn.isEmpty()) {
            save(bookId, "NONE", null);
            return;
        }
        try {
            permits.acquire();
            try {
                Optional<String> ol = openLibraryCoverId(isbn);
                if (ol.isPresent()) {
                    save(bookId, "OPEN_LIBRARY", ol.get());
                    return;
                }
                Optional<String> gb = googleThumbnail(isbn);
                if (gb.isPresent()) {
                    save(bookId, "GOOGLE_BOOKS", gb.get());
                    return;
                }
                save(bookId, "NONE", null);
            } finally {
                permits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Cover lookup failed for book {}: {}", bookId, e.getMessage());
        }
    }

    Optional<String> openLibraryCoverId(String isbn) throws Exception {
        JsonNode node = getJson(config.openLibraryUrl() + "/isbn/" + isbn + ".json");
        if (node == null) {
            return Optional.empty();
        }
        for (JsonNode c : node.path("covers")) {
            long id = c.asLong(-1);
            if (id > 0) {
                return Optional.of(String.valueOf(id));
            }
        }
        return Optional.empty();
    }

    Optional<String> googleThumbnail(String isbn) throws Exception {
        JsonNode node = getJson(config.googleBooksUrl() + "/books/v1/volumes?maxResults=1&q=" + URLEncoder.encode("isbn:" + isbn, StandardCharsets.UTF_8));
        if (node == null) {
            return Optional.empty();
        }
        JsonNode links = node.path("items").path(0).path("volumeInfo").path("imageLinks");
        String url = links.path("thumbnail").asString(null);
        if (url == null) {
            url = links.path("smallThumbnail").asString(null);
        }
        if (url == null) {
            return Optional.empty();
        }
        return Optional.of(url.replace("http://", "https://").replace("&edge=curl", ""));
    }

    JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .header("User-Agent", USER_AGENT).header("Accept", "application/json").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return mapper.readTree(response.body());
    }

    private void save(long bookId, String source, String ref) {
        jdbc.update("UPDATE books SET cover_source = ?, cover_external_ref = ?, cover_checked_at = ? WHERE id = ? AND cover_source <> 'LOCAL'",
                source, ref, Timestamp.from(Instant.now()), bookId);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
