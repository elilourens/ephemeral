-- voice messages: store playback duration + a precomputed waveform on the audio
-- attachment (computed once on send, never re-decoded on render).
alter table attachments add column duration_ms int;
alter table attachments add column waveform text;   -- JSON array of 0–100 peak ints
