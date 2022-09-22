package pl.mlynik.journal

import pl.mlynik.journal.Storage.{ Offseted, PersistError }
import zio.*
import zio.concurrent.ConcurrentMap
import zio.stream.ZStream

final class InMemoryJournal[EVENT](storage: ConcurrentMap[String, List[Offseted[EVENT]]]) extends Journal[EVENT] {

  case class LoadIOException(io: Throwable) extends Storage.LoadError
  def persist(id: String, event: EVENT): IO[Storage.PersistError, Unit] =
    storage
      .compute(
        id,
        {
          case (_, None) | (_, Some(Nil)) => Some(List(Offseted(0, event)))
          case (_, Some(current))         =>
            Some(current :+ Offseted(current.last.offset + 1, event))
        }
      )
      .unit

  def load(id: String, loadFrom: Int): ZStream[Any, Storage.LoadError, Offseted[EVENT]] =
    ZStream.unwrap {
      storage
        .get(id)
        .map(_.getOrElse(Nil).iterator)
        .map(it => ZStream.fromIterator(it).mapError(ioe => LoadIOException(ioe)))
    }.filter(_.offset >= loadFrom)
}

object InMemoryJournal {
  def live[EVENT: Tag]: ZLayer[Any, Nothing, Journal[EVENT]] = ZLayer.fromZIO {
    for {
      mp <- ConcurrentMap.make[String, List[Offseted[EVENT]]]()
    } yield new InMemoryJournal(mp)
  }
}

final class InSnapshotStorage[STATE](storage: ConcurrentMap[String, Offseted[STATE]]) extends SnapshotStorage[STATE] {
  def store(id: String, state: Offseted[STATE]): IO[PersistError, Unit] =
    storage.put(id, state).unit

  def loadLast(id: String): UIO[Option[Offseted[STATE]]] = storage.get(id)
}

object InSnapshotStorage {
  def live[STATE: Tag]: ZLayer[Any, Nothing, SnapshotStorage[STATE]] = ZLayer.fromZIO {
    for {
      ref <- ConcurrentMap.make[String, Offseted[STATE]]()
    } yield new InSnapshotStorage(ref)
  }
}
