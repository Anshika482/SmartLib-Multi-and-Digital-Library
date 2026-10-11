package com.smartlib.library;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Locale;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.storage.FileSniffer;
import com.smartlib.common.storage.StorageService;
import com.smartlib.common.util.Texts;

@Service
public class LibraryAdminService {

    private final LibraryRepository libraries;
    private final LibraryPolicyRepository policies;
    private final StorageService storage;
    private final AuditService audit;

    public LibraryAdminService(LibraryRepository libraries, LibraryPolicyRepository policies, StorageService storage, AuditService audit) {
        this.libraries = libraries;
        this.policies = policies;
        this.storage = storage;
        this.audit = audit;
    }

    public record ProfileInput(@NotBlank @Size(max = 160) String name, @Size(max = 160) String tagline, @Size(max = 4000) String description,
                               @Size(max = 255) String addressLine, @Size(max = 100) String city, @Size(max = 100) String state,
                               @Size(max = 20) String postalCode, @Email @Size(max = 254) String publicEmail,
                               @Size(max = 32) String publicPhone, @Size(max = 255) String website, @Size(max = 255) String openingHours,
                               @Size(max = 64) String timezone) {
    }

    public record CreateLibraryInput(@NotBlank @Pattern(regexp = "^[A-Za-z0-9]{2,12}$", message = "Code must be 2-12 letters/digits") String code,
                                     @Size(max = 80) String slug, @NotNull ProfileInput profile) {
    }

    public record PolicyInput(@Min(1) @Max(365) int loanPeriodDays, @Min(1) @Max(50) int maxActiveLoans,
                              @Min(1) @Max(50) int maxPendingRequests, @Min(1) @Max(30) int pickupWindowDays,
                              @Min(0) @Max(100_000) long finePerDayPaise, @Min(0) @Max(60) int graceDays,
                              @Min(0) @Max(10_000_000) long maxFinePerLoanPaise, @Min(0) @Max(10_000_000) long blockThresholdPaise) {
    }

    public record LibraryAdminView(long id, String code, String slug, String name, String tagline, String description, String addressLine,
                                   String city, String state, String postalCode, String publicEmail, String publicPhone, String website,
                                   String openingHours, String timezone, String status, String imageUrl, PolicyInput policy) {
    }

    public LibraryAdminView view(long libraryId) {
        Library l = libraries.findById(libraryId).orElseThrow(() -> ApiException.notFound(ErrorCodes.LIBRARY_NOT_FOUND, "Library not found."));
        LibraryPolicy p = policies.findById(libraryId).orElse(LibraryPolicy.defaultsFor(libraryId));
        return new LibraryAdminView(l.getId(), l.getCode(), l.getSlug(), l.getName(), l.getTagline(), l.getDescription(), l.getAddressLine(),
                l.getCity(), l.getState(), l.getPostalCode(), l.getPublicEmail(), l.getPublicPhone(), l.getWebsite(), l.getOpeningHours(),
                l.getTimezone(), l.getStatus().name(), l.getImageKey() == null ? null : "/api/public/libraries/" + l.getSlug() + "/image",
                new PolicyInput(p.getLoanPeriodDays(), p.getMaxActiveLoans(), p.getMaxPendingRequests(), p.getPickupWindowDays(),
                        p.getFinePerDayPaise(), p.getGraceDays(), p.getMaxFinePerLoanPaise(), p.getBlockThresholdPaise()));
    }

    @Transactional
    public LibraryAdminView create(CreateLibraryInput in) {
        String code = in.code().toUpperCase(Locale.ROOT);
        if (libraries.existsByCode(code)) {
            throw ApiException.conflict(ErrorCodes.CONFLICT, "A library with this code already exists.");
        }
        String slug = Texts.slugify(in.slug() == null || in.slug().isBlank() ? in.profile().name() : in.slug());
        String unique = slug;
        for (int i = 2; libraries.existsBySlug(unique); i++) {
            unique = slug + "-" + i;
        }
        Library l = new Library();
        l.setCode(code);
        l.setSlug(unique);
        l.setStatus(LibraryStatus.ACTIVE);
        apply(l, in.profile());
        libraries.saveAndFlush(l);
        policies.save(LibraryPolicy.defaultsFor(l.getId()));
        audit.record(AuditActions.LIBRARY_CREATED, "LIBRARY", l.getId(), l.getId(), AuditService.details("name", l.getName(), "code", code));
        return view(l.getId());
    }

    @Transactional
    public LibraryAdminView updateProfile(long libraryId, ProfileInput in) {
        Library l = libraries.findById(libraryId).orElseThrow();
        apply(l, in);
        libraries.save(l);
        audit.record(AuditActions.LIBRARY_UPDATED, "LIBRARY", libraryId, libraryId, null);
        return view(libraryId);
    }

    @Transactional
    public LibraryAdminView updatePolicy(long libraryId, PolicyInput in) {
        LibraryPolicy p = policies.findById(libraryId).orElse(LibraryPolicy.defaultsFor(libraryId));
        p.setLoanPeriodDays(in.loanPeriodDays());
        p.setMaxActiveLoans(in.maxActiveLoans());
        p.setMaxPendingRequests(in.maxPendingRequests());
        p.setPickupWindowDays(in.pickupWindowDays());
        p.setFinePerDayPaise(in.finePerDayPaise());
        p.setGraceDays(in.graceDays());
        p.setMaxFinePerLoanPaise(in.maxFinePerLoanPaise());
        p.setBlockThresholdPaise(in.blockThresholdPaise());
        policies.save(p);
        audit.record(AuditActions.POLICY_UPDATED, "LIBRARY", libraryId, libraryId, AuditService.details("loanPeriodDays", in.loanPeriodDays(),
                "finePerDayPaise", in.finePerDayPaise()));
        return view(libraryId);
    }

    @Transactional
    public LibraryAdminView setStatus(long libraryId, LibraryStatus status) {
        Library l = libraries.findById(libraryId).orElseThrow(() -> ApiException.notFound(ErrorCodes.LIBRARY_NOT_FOUND, "Library not found."));
        l.setStatus(status);
        libraries.save(l);
        audit.record(AuditActions.LIBRARY_STATUS_CHANGED, "LIBRARY", libraryId, libraryId, AuditService.details("status", status.name()));
        return view(libraryId);
    }

    @Transactional
    public LibraryAdminView uploadImage(long libraryId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty() || file.getSize() > 3L * 1024 * 1024) {
            throw ApiException.badRequest(ErrorCodes.UNSUPPORTED_FILE, "Upload a JPEG, PNG or WebP image up to 3 MB.");
        }
        Library l = libraries.findById(libraryId).orElseThrow();
        try (InputStream in = file.getInputStream()) {
            byte[] head = FileSniffer.head(in, 64);
            FileSniffer.Sniffed type = FileSniffer.detect(head).filter(FileSniffer::isImage)
                    .orElseThrow(() -> ApiException.badRequest(ErrorCodes.UNSUPPORTED_FILE, "Upload a JPEG, PNG or WebP image."));
            String old = l.getImageKey();
            l.setImageKey(storage.store("libraries", new SequenceInputStream(new ByteArrayInputStream(head), in), type.extension()));
            libraries.save(l);
            storage.delete(old);
        }
        audit.record(AuditActions.LIBRARY_UPDATED, "LIBRARY", libraryId, libraryId, AuditService.details("field", "image"));
        return view(libraryId);
    }

    private static void apply(Library l, ProfileInput in) {
        l.setName(in.name().trim());
        l.setTagline(Texts.trimToNull(in.tagline()));
        l.setDescription(Texts.trimToNull(in.description()));
        l.setAddressLine(Texts.trimToNull(in.addressLine()));
        l.setCity(Texts.trimToNull(in.city()));
        l.setState(Texts.trimToNull(in.state()));
        l.setPostalCode(Texts.trimToNull(in.postalCode()));
        l.setPublicEmail(Texts.trimToNull(in.publicEmail()));
        l.setPublicPhone(Texts.trimToNull(in.publicPhone()));
        l.setWebsite(Texts.trimToNull(in.website()));
        l.setOpeningHours(Texts.trimToNull(in.openingHours()));
        if (in.timezone() != null && !in.timezone().isBlank()) {
            try {
                java.time.ZoneId.of(in.timezone().trim());
                l.setTimezone(in.timezone().trim());
            } catch (RuntimeException e) {
                throw ApiException.validation("timezone", "Unknown time zone");
            }
        }
    }
}
