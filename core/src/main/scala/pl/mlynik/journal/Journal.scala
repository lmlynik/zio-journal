package pl.mlynik.journal

import pl.mlynik.journal.Storage.*
import zio.*
import zio.stream.*

object Storage {
  trait LoadError
  trait PersistError

  final case class Offseted[T](offset: Long, value: T)
}

trait Journal[R, EVENT] {
  def persist(id: String, offset: Long, event: EVENT): ZIO[R, PersistError, Unit]

  def load(id: String, loadFrom: Long): ZStream[R, LoadError, Offseted[EVENT]]
}

trait SnapshotStorage[R, STATE] {
  def store(id: String, state: Offseted[STATE]): ZIO[R, PersistError, Unit]

  def loadLast(id: String): ZIO[R, LoadError, Option[Offseted[STATE]]]
}
