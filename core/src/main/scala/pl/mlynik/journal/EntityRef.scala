package pl.mlynik.journal

import zio.*

trait EntityRef[COMMAND, EVENT, STATE] {
  def state: UIO[STATE]

  def send(command: COMMAND): ZIO[SnapshotStorage[STATE] & Journal[EVENT], Storage.PersistError, Unit]

  def ask[E, A](command: COMMAND): ZIO[SnapshotStorage[STATE] & Journal[EVENT], Storage.PersistError | E, A]
}
