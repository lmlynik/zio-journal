package pl.mlynik.db

final case class JournalRow(
  persistenceId: String,
  sequenceNumber: Long,
  writeTimestamp: Long,
  payload: Array[Byte]
)

final case class Snapshot(
  persistenceId: String,
  sequenceNumber: Long,
  writeTimestamp: Long,
  payload: Array[Byte]
)
