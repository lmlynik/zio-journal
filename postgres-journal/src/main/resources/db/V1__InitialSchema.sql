CREATE TABLE IF NOT EXISTS public.journal_row(
    ordering BIGSERIAL,
    persistence_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    write_timestamp BIGINT NOT NULL,

    payload BYTEA NOT NULL,

    PRIMARY KEY(persistence_id, sequence_number)
    );

CREATE UNIQUE INDEX journal_row_ordering_idx ON public.journal_row(ordering);

CREATE TABLE IF NOT EXISTS public.snapshot (
    persistence_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    write_timestamp BIGINT NOT NULL,

    payload BYTEA NOT NULL,

    PRIMARY KEY(persistence_id, sequence_number)
    );