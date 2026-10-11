package com.smartlib.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.smartlib.library.Library;
import com.smartlib.support.IntegrationTest;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.UserRole;

/**
 * Generated security matrix: EVERY /api/manage/libraries/{libraryId}/** endpoint is called by staff of library A
 * against library B, by a member, and anonymously. A new endpoint is covered automatically.
 */
class LibraryIsolationIT extends IntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    record Endpoint(RequestMethod method, String pattern, boolean multipart) {
    }

    private List<Endpoint> endpoints(String prefix) {
        List<Endpoint> out = new ArrayList<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            boolean multipart = info.getConsumesCondition().getConsumableMediaTypes().stream().anyMatch(m -> m.getType().equals("multipart"));
            for (String pattern : info.getPatternValues()) {
                if (pattern.startsWith(prefix)) {
                    for (RequestMethod m : info.getMethodsCondition().getMethods()) {
                        out.add(new Endpoint(m, pattern, multipart));
                    }
                }
            }
        }
        return out;
    }

    private static String fill(String pattern, long libraryId) {
        return pattern.replace("{libraryId}", String.valueOf(libraryId)).replaceAll("\\{[^}]+}", "1");
    }

    private int call(Endpoint e, long libraryId, String token) throws Exception {
        String url = fill(e.pattern(), libraryId);
        var builder = e.multipart() ? multipart(HttpMethod.valueOf(e.method().name()), url).param("title", "x")
                : request(HttpMethod.valueOf(e.method().name()), url).contentType(MediaType.APPLICATION_JSON).content("{}");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(builder).andReturn().getResponse().getStatus();
    }

    @Test
    void everyStaffEndpointIsLibraryScoped() throws Exception {
        Library a = data.library("LIBA", "Library A");
        Library b = data.library("LIBB", "Library B");
        String librarianA = data.token(data.staff("la@example.com", UserRole.LIBRARIAN, a.getId(), ApprovalStatus.APPROVED));
        String adminA = data.token(data.staff("aa@example.com", UserRole.ADMIN, a.getId(), ApprovalStatus.APPROVED));
        String pendingA = data.token(data.staff("pa@example.com", UserRole.LIBRARIAN, a.getId(), ApprovalStatus.PENDING));
        String member = data.token(data.member("m@example.com"));

        List<Endpoint> manage = endpoints("/api/manage/");
        assertThat(manage).hasSizeGreaterThan(30);
        assertThat(manage).allSatisfy(e -> assertThat(e.pattern()).startsWith("/api/manage/libraries/{libraryId}"));

        List<String> failures = new ArrayList<>();
        for (Endpoint e : manage) {
            expect(failures, e, "librarian of A -> B", call(e, b.getId(), librarianA), 403);
            expect(failures, e, "admin of A -> B", call(e, b.getId(), adminA), 403);
            expect(failures, e, "member", call(e, a.getId(), member), 403);
            expect(failures, e, "pending librarian", call(e, a.getId(), pendingA), 401);
            expect(failures, e, "anonymous", call(e, a.getId(), null), 401);
        }
        assertThat(failures).isEmpty();
    }

    @Test
    void platformEndpointsAreSuperAdminOnly() throws Exception {
        Library a = data.library("LIBA", "Library A");
        String adminA = data.token(data.staff("aa@example.com", UserRole.ADMIN, a.getId(), ApprovalStatus.APPROVED));
        String member = data.token(data.member("m@example.com"));
        String superAdmin = data.token(data.superAdmin("root@example.com"));
        List<String> failures = new ArrayList<>();
        for (Endpoint e : endpoints("/api/platform/")) {
            expect(failures, e, "library admin", call(e, a.getId(), adminA), 403);
            expect(failures, e, "member", call(e, a.getId(), member), 403);
            expect(failures, e, "anonymous", call(e, a.getId(), null), 401);
        }
        assertThat(failures).isEmpty();
        assertThat(mvc.perform(getAuthed("/api/platform/analytics/overview", superAdmin)).andReturn().getResponse().getStatus()).isEqualTo(200);
        // super admin may operate any library through the same staff API
        assertThat(mvc.perform(getAuthed("/api/manage/libraries/" + a.getId() + "/dashboard", superAdmin)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    private static void expect(List<String> failures, Endpoint e, String who, int actual, int expected) {
        if (actual != expected) {
            failures.add(e.method() + " " + e.pattern() + " as " + who + ": expected " + expected + " got " + actual);
        }
    }
}
