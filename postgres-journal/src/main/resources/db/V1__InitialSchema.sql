CREATE TABLE IF NOT EXISTS public.event_journal(
    ordering BIGSERIAL,
    persistence_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    write_timestamp timestamp,

    payload BYTEA NOT NULL,

    PRIMARY KEY(persistence_id, sequence_number)
    );

CREATE UNIQUE INDEX event_journal_ordering_idx ON public.event_journal(ordering);

CREATE TABLE IF NOT EXISTS public.snapshot (
    persistence_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    write_timestamp timestamp,

    payload BYTEA NOT NULL,

    PRIMARY KEY(persistence_id, sequence_number)
    );