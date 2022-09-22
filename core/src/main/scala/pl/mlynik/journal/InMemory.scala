package pl.mlynik.journal

import pl.mlynik.journal.Storage.Offseted
import zio._
import zio.concurrent.ConcurrentMap

import zio.stream.ZStream

class InMemoryJournal[EVENT](storage: Ref.Synchronized[ConcurrentMap[String, List[Offseted[EVENT]]]])
    extends Journal[EVENT] {

  case class LoadIOException(io: Throwable) extends Storage.LoadError
  def persist(id: String, event: EVENT): IO[Storage.PersistError, Unit] =
    storage.updateZIO { mp =>
      mp.compute(
        id,
        {
          case (_, None) | (_, Some(Nil)) => Some(List(Offseted(0, event)))
          case (_, Some(current))         =>
            Some(current :+ Offseted(current.last.offset + 1, event))
        }
      ).as(mp)
    }

  def load(id: String, loadFrom: Int): ZStream[Any, Storage.LoadError, Offseted[EVENT]] =
    ZStream.unwrap(storage.get.flatMap { mp =>
      mp.get(id)
        .map(_.getOrElse(Nil).iterator)
        .map(it => ZStream.fromIterator(it).mapError(ioe => LoadIOException(ioe)))
    })
}

object InMemoryJournal {
  def live[EVENT: Tag]: ZLayer[Any, Nothing, Journal[EVENT]] = ZLayer.fromZIO {
    for {
      mp  <- ConcurrentMap.make[String, List[Offseted[EVENT]]]()
      ref <- Ref.Synchronized.make[ConcurrentMap[String, List[Offseted[EVENT]]]](mp)
    } yield new InMemoryJournal(ref)
  }
}

object NoopSnapshotStorage {
  def live[STATE: Tag]: ZLayer[Any, Nothing, SnapshotStorage[STATE]] = ZLayer.succeed {
    new SnapshotStorage[STATE] {
      def store(id: String, state: Offseted[STATE]) = ZIO.unit

      def loadLast(id: String) = ZIO.none
    }
  }
}
