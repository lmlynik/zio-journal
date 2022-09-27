package pl.mlynik.journal

import zio.*

trait EntityRef[R, COMMAND, CERR, EVENT, STATE] {
  def state(implicit trace: Trace): UIO[STATE]

  def send(command: COMMAND)(implicit
    trace: Trace
  ): ZIO[R & SnapshotStorage[R, STATE] & Journal[R, EVENT], Storage.PersistError | CERR, Unit]

  def ask[A](command: COMMAND)(implicit
    trace: Trace
  ): ZIO[R & SnapshotStorage[R, STATE] & Journal[R, EVENT], Storage.PersistError | CERR, A]
}
