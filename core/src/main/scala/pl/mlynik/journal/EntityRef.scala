package pl.mlynik.journal

import zio.*

trait EntityRef[R, COMMAND, EVENT, STATE] {
  def state: UIO[STATE]

  def send(command: COMMAND): ZIO[R & SnapshotStorage[STATE] & Journal[R, EVENT], Storage.PersistError, Unit]

  def ask[E, A](command: COMMAND): ZIO[R & SnapshotStorage[STATE] & Journal[R, EVENT], Storage.PersistError | E, A]
}
