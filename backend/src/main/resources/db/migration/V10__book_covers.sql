-- Book covers.
--
-- One nullable column, and deliberately only that. The image bytes are not
-- stored in the database: the column holds an opaque storage key, and the file
-- lives wherever the deployment's cover storage puts it. That keeps rows small,
-- keeps backups from carrying binaries, and means moving to object storage or a
-- CDN later is a change of storage implementation with no migration at all.
--
-- The key is never returned by the API. A caller is given the endpoint
-- /api/books/{id}/cover, which says nothing about how or where the file is
-- kept.
--
-- Nullable with no default: every existing book has no cover, and a book
-- without one behaves exactly as it did before this migration. Nothing is
-- backfilled and no existing row is touched.
--
-- Unlike V9 this adds a plain column rather than widening an ENUM, so
-- Hibernate's ddl-auto=update applies it to a development database on its own.
ALTER TABLE books
    ADD COLUMN cover_image_key VARCHAR(255) NULL AFTER isbn;
