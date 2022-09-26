package pl.mlynik.journal

import zio.*

trait EntityRef[R, COMMAND, EVENT, STATE] {
  def state(implicit trace: Trace): UIO[STATE]

  def send[E](command: COMMAND)(implicit
    trace: Trace
  ): ZIO[R & SnapshotStorage[R, STATE] & Journal[R, EVENT], Storage.PersistError | E, Unit]

  def ask[E, A](command: COMMAND)(implicit
    trace: Trace
  ): ZIO[R & SnapshotStorage[R, STATE] & Journal[R, EVENT], Storage.PersistError | E, A]
}
