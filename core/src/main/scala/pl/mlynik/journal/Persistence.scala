package pl.mlynik.journal

import pl.mlynik.journal.Storage.*
import zio.*
import zio.stream.*

object Storage {
  trait LoadError
  trait PersistError

  final case class Offseted[T](offset: Long, value: T)
  object Offseted {
    val zero = Offseted[Any](0, null)
  }

  enum LoadMode {
    case NoBatch
    case Batch(batchSize: Int)
  }
}

trait Journal[EVENT] {
  def persist(persistenceId: String, offset: Long, event: EVENT): ZIO[Any, PersistError, Unit]

  def load(persistenceId: String, loadFrom: Long, loadMode: LoadMode): ZStream[Any, LoadError, Offseted[EVENT]]

  def load(persistenceId: String, loadFrom: Long): ZStream[Any, LoadError, Offseted[EVENT]] =
    load(persistenceId, loadFrom, LoadMode.NoBatch)
}

trait SnapshotStorage[STATE] {
  def store(persistenceId: String, state: Offseted[STATE]): ZIO[Any, PersistError, Unit]

  def loadLast(persistenceId: String): ZIO[Any, LoadError, Option[Offseted[STATE]]]
}

trait ProjectionOffsetStorage {
  def store(persistenceId: String, projectionId: String, offset: Offseted[Unit]): ZIO[Any, PersistError, Unit]

  def loadLast(persistenceId: String, projectionId: String): ZIO[Any, LoadError, Option[Offseted[Unit]]]
}
