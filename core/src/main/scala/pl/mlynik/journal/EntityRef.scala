package pl.mlynik.journal

import zio.*

trait EntityRef[R, CENV, COMMAND, CERR, EVENT, STATE] {
  def state(implicit trace: Trace): UIO[STATE]

  def send(command: COMMAND)(implicit
    trace: Trace
  ): ZIO[CENV & SnapshotStorage[STATE] & Journal[EVENT], Storage.PersistError | CERR, Unit]

  def ask[A](command: COMMAND)(implicit
    trace: Trace
  ): ZIO[CENV & SnapshotStorage[STATE] & Journal[EVENT], Storage.PersistError | CERR, A]
}
