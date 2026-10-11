package com.smartlib.library;

import java.io.IOException;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.smartlib.security.LibraryScope;

@RestController
@RequestMapping("/api/manage/libraries/{libraryId}")
public class ManageLibraryController {

    private final LibraryAdminService admin;

    public ManageLibraryController(LibraryAdminService admin) {
        this.admin = admin;
    }

    @GetMapping
    public LibraryAdminService.LibraryAdminView get(LibraryScope scope) {
        return admin.view(scope.libraryId());
    }

    @PutMapping("/profile")
    public LibraryAdminService.LibraryAdminView profile(LibraryScope scope, @Valid @RequestBody LibraryAdminService.ProfileInput in) {
        scope.requireAdmin();
        return admin.updateProfile(scope.libraryId(), in);
    }

    @PutMapping("/policy")
    public LibraryAdminService.LibraryAdminView policy(LibraryScope scope, @Valid @RequestBody LibraryAdminService.PolicyInput in) {
        scope.requireAdmin();
        return admin.updatePolicy(scope.libraryId(), in);
    }

    @PostMapping(path = "/image", consumes = "multipart/form-data")
    public LibraryAdminService.LibraryAdminView image(LibraryScope scope, @RequestPart("file") MultipartFile file) throws IOException {
        scope.requireAdmin();
        return admin.uploadImage(scope.libraryId(), file);
    }
}
