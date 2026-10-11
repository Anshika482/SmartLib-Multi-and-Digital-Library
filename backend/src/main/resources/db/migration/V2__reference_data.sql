-- Reference data required by the application (this is NOT demo data).
INSERT INTO categories (name, slug, sort_order) VALUES
    ('Fiction', 'fiction', 1),
    ('Non-Fiction', 'non-fiction', 2),
    ('Technology', 'technology', 3),
    ('Science', 'science', 4),
    ('Business', 'business', 5),
    ('Health & Wellness', 'health-wellness', 6),
    ('History', 'history', 7),
    ('Arts & Culture', 'arts-culture', 8);

INSERT INTO platform_settings (setting_key, setting_value, updated_at) VALUES
    ('ai.enabled', 'true', UTC_TIMESTAMP(6)),
    ('registration.librarian.open', 'true', UTC_TIMESTAMP(6)),
    ('platform.announcement', '', UTC_TIMESTAMP(6));
