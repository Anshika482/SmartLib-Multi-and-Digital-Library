package com.smartlib.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.smartlib.library.Library;
import com.smartlib.support.IntegrationTest;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.User;
import com.smartlib.user.UserRole;

import tools.jackson.databind.JsonNode;

class ResourceAccessIT extends IntegrationTest {

    @Test
    void membersOnlyResourcesAreProtectedAndFilesUseShortLivedSignedUrls() throws Exception {
        Library lib = data.library("LIBA", "Library A");
        String librarian = data.token(data.staff("la@example.com", UserRole.LIBRARIAN, lib.getId(), ApprovalStatus.APPROVED));
        byte[] pdf = "%PDF-1.4\n% test\n".getBytes(StandardCharsets.US_ASCII);

        // A renamed non-PDF is rejected by content sniffing
        mvc.perform(multipart("/api/manage/libraries/" + lib.getId() + "/resources")
                        .file(new MockMultipartFile("file", "fake.pdf", "application/pdf", "hello".getBytes()))
                        .param("title", "Fake").param("type", "PDF").param("visibility", "PUBLIC")
                        .header("Authorization", "Bearer " + librarian))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE"));

        JsonNode created = json(mvc.perform(multipart("/api/manage/libraries/" + lib.getId() + "/resources")
                        .file(new MockMultipartFile("file", "guide.pdf", "application/pdf", pdf))
                        .param("title", "Members guide").param("type", "PDF").param("visibility", "LIBRARY_MEMBERS")
                        .header("Authorization", "Bearer " + librarian))
                .andExpect(status().isCreated()).andReturn());
        long id = created.get("id").asLong();

        mvc.perform(post("/api/resources/" + id + "/access")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"));
        User outsider = data.member("outsider@example.com");
        mvc.perform(postAuthed("/api/resources/" + id + "/access", data.token(outsider), null))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("RESOURCE_ACCESS_DENIED"));
        assertThat(json(mvc.perform(getAuthed("/api/me/resources", data.token(outsider))).andReturn()).get("totalElements").asLong()).isZero();

        User member = data.member("member@example.com");
        String memberToken = data.token(member);
        mvc.perform(postAuthed("/api/me/memberships", memberToken, java.util.Map.of("libraryId", lib.getId()))).andExpect(status().isOk());
        JsonNode grant = json(mvc.perform(postAuthed("/api/resources/" + id + "/access", memberToken, null)).andExpect(status().isOk()).andReturn());
        String url = grant.get("url").asString();
        assertThat(url).startsWith("/api/files/");
        byte[] served = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(served).isEqualTo(pdf);
        // tampered token
        mvc.perform(get(url.substring(0, url.length() - 2) + "00")).andExpect(status().isForbidden());
        // expired after 5 minutes
        clock.setClock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(6)));
        mvc.perform(get(url)).andExpect(status().isForbidden());
    }

    @Test
    void linkResourcesValidateUrls() throws Exception {
        Library lib = data.library("LIBA", "Library A");
        String librarian = data.token(data.staff("la@example.com", UserRole.LIBRARIAN, lib.getId(), ApprovalStatus.APPROVED));
        mvc.perform(multipart("/api/manage/libraries/" + lib.getId() + "/resources").param("title", "Bad").param("type", "LINK")
                        .param("visibility", "PUBLIC").param("externalUrl", "javascript:alert(1)").header("Authorization", "Bearer " + librarian))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/manage/libraries/" + lib.getId() + "/resources").param("title", "Docs").param("type", "LINK")
                        .param("visibility", "PUBLIC").param("externalUrl", "https://docs.python.org/3/").header("Authorization", "Bearer " + librarian))
                .andExpect(status().isCreated());
        assertThat(json(mvc.perform(get("/api/public/resources")).andReturn()).get("totalElements").asLong()).isEqualTo(1);
    }
}
