package com.smartlib.catalogue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.smartlib.common.config.AppProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Prefills the staff "add book by ISBN" form from Open Library, then Google Books. */
@Service
public class BookMetadataClient {

    private static final Pattern YEAR = Pattern.compile("(1[4-9]\\d\\d|20\\d\\d)");

    private final ObjectMapper mapper;
    private final AppProperties.Covers config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public BookMetadataClient(ObjectMapper mapper, AppProperties properties) {
        this.mapper = mapper;
        this.config = properties.covers();
    }

    public record BookMetadata(String isbn13, String title, String subtitle, List<String> authors, String publisher,
                               Integer publishedYear, Integer pageCount, String description, String language, String coverUrl,
                               String source) {
    }

    public Optional<BookMetadata> lookup(String isbn13) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        String title = null, subtitle = null, publisher = null, description = null, language = null, cover = null, source = null;
        Integer year = null, pages = null;
        List<String> authors = new ArrayList<>();
        try {
            JsonNode ol = get(config.openLibraryUrl() + "/api/books?format=json&jscmd=data&bibkeys=ISBN:" + isbn13);
            JsonNode d = ol == null ? null : ol.path("ISBN:" + isbn13);
            if (d != null && !d.isMissingNode() && d.has("title")) {
                source = "Open Library";
                title = d.path("title").asString(null);
                subtitle = d.path("subtitle").asString(null);
                for (JsonNode a : d.path("authors")) {
                    authors.add(a.path("name").asString());
                }
                publisher = d.path("publishers").path(0).path("name").asString(null);
                year = year(d.path("publish_date").asString(null));
                pages = d.path("number_of_pages").isNumber() ? d.path("number_of_pages").asInt() : null;
                cover = d.path("cover").path("medium").asString(null);
            }
        } catch (Exception ignored) {
            // fall through to Google Books
        }
        try {
            JsonNode gb = get(config.googleBooksUrl() + "/books/v1/volumes?maxResults=1&q=" + URLEncoder.encode("isbn:" + isbn13, StandardCharsets.UTF_8));
            JsonNode v = gb == null ? null : gb.path("items").path(0).path("volumeInfo");
            if (v != null && !v.isMissingNode() && v.has("title")) {
                source = source == null ? "Google Books" : source + " + Google Books";
                title = title != null ? title : v.path("title").asString(null);
                subtitle = subtitle != null ? subtitle : v.path("subtitle").asString(null);
                if (authors.isEmpty()) {
                    for (JsonNode a : v.path("authors")) {
                        authors.add(a.asString());
                    }
                }
                publisher = publisher != null ? publisher : v.path("publisher").asString(null);
                year = year != null ? year : year(v.path("publishedDate").asString(null));
                pages = pages != null ? pages : (v.path("pageCount").isNumber() ? v.path("pageCount").asInt() : null);
                description = v.path("description").asString(null);
                language = languageName(v.path("language").asString(null));
                if (cover == null) {
                    String t = v.path("imageLinks").path("thumbnail").asString(null);
                    cover = t == null ? null : t.replace("http://", "https://");
                }
            }
        } catch (Exception ignored) {
            // metadata is optional
        }
        if (title == null) {
            return Optional.empty();
        }
        return Optional.of(new BookMetadata(isbn13, title, subtitle, authors, publisher, year, pages, description, language, cover, source));
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                .header("User-Agent", CoverResolver.USER_AGENT).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() == 200 ? mapper.readTree(res.body()) : null;
    }

    private static Integer year(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = YEAR.matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String languageName(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH);
        return name.isBlank() ? code : name;
    }
}
