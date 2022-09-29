package pl.mlynik.journal

import pl.mlynik.journal.Storage.*
import zio.*
import zio.stream.*

object Storage {
  trait LoadError
  trait PersistError

  final case class Offseted[T](offset: Long, value: T)
}

trait Journal[EVENT] {
  def persist(id: String, offset: Long, event: EVENT): ZIO[Any, PersistError, Unit]

  def load(id: String, loadFrom: Long): ZStream[Any, LoadError, Offseted[EVENT]]
}

trait SnapshotStorage[STATE] {
  def store(id: String, state: Offseted[STATE]): ZIO[Any, PersistError, Unit]

  def loadLast(id: String): ZIO[Any, LoadError, Option[Offseted[STATE]]]
}
