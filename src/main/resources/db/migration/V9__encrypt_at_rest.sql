-- Encryption at rest: messages.content now stores AES-GCM ciphertext, so the
-- search vector can no longer be a generated column (it would index ciphertext).
-- The app computes it from plaintext at write time instead. Likewise the
-- has:link search filter needs a flag computed before encryption.
alter table messages drop column content_tsv;
alter table messages add column content_tsv tsvector;
create index idx_messages_tsv on messages using gin (content_tsv);

alter table messages add column has_link boolean not null default false;
