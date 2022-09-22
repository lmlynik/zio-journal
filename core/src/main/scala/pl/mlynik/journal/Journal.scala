package pl.mlynik.journal

import pl.mlynik.journal.Storage.*
import zio.*
import zio.stream.*

object Storage {
  trait LoadError
  trait PersistError

  final case class Offseted[T](offset: Int, value: T)
}

trait Journal[EVENT] {
  def persist(id: String, event: EVENT): IO[PersistError, Unit]

  def load(id: String, loadFrom: Int): ZStream[Any, LoadError, Offseted[EVENT]]
}

trait SnapshotStorage[STATE] {
  def store(id: String, state: Offseted[STATE]): IO[PersistError, Unit]

  def loadLast(id: String): IO[LoadError, Option[Offseted[STATE]]]
}
