package pl.mlynik.journal

import pl.mlynik.journal.Storage.{ Offseted, PersistError }
import pl.mlynik.journal.serde.Serde
import zio.*
import zio.concurrent.ConcurrentMap
import zio.stream.ZStream
import zio.stream.ZPipeline
import pl.mlynik.journal.Storage.LoadError

final class InMemoryJournal[EVENT](
  storage: ConcurrentMap[String, List[Offseted[String]]],
  serde: Serde[EVENT, String]
) extends Journal[EVENT] {

  case class LoadIOException(io: Throwable) extends Storage.LoadError
  def persist(id: String, offset: Long, event: EVENT): IO[Storage.PersistError, Unit] =
    serde.serialize(event).flatMap { payload =>
      storage
        .compute(
          id,
          {
            case (_, None) | (_, Some(Nil)) => Some(List(Offseted(offset, payload)))
            case (_, Some(current))         =>
              Some(current :+ Offseted(offset, payload))
          }
        )
        .unit
    }

  private val deserialize = ZPipeline.mapZIO[Any, Nothing, Offseted[String], Offseted[EVENT]] { r =>
    serde.deserialize(r.value).map { event =>
      r.copy(value = event)
    }
  }

  def load(id: String, loadFrom: Long, loadMode: Storage.LoadMode): ZStream[Any, Storage.LoadError, Offseted[EVENT]] =
    ZStream.unwrap {
      storage
        .get(id)
        .map(_.getOrElse(Nil).iterator)
        .map(it => ZStream.fromIterator(it).mapError(ioe => LoadIOException(ioe)))
    }.filter(_.offset >= loadFrom).via(deserialize)
}

object InMemoryJournal {
  def live[EVENT: Tag]: ZLayer[Serde[EVENT, String], Nothing, Journal[EVENT]] = ZLayer.fromZIO {
    for {
      serde <- ZIO.service[Serde[EVENT, String]]
      mp    <- ConcurrentMap.make[String, List[Offseted[String]]]()
    } yield new InMemoryJournal(mp, serde)
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

final class InMemoryProjectionOffsetStorage(storage: ConcurrentMap[String, Offseted[Unit]])
    extends ProjectionOffsetStorage {

  override def store(
    persistenceId: String,
    projectionId: String,
    offset: Offseted[Unit]
  ): ZIO[Any, PersistError, Unit] =
    storage.put(s"$persistenceId-$projectionId", offset).unit

  override def loadLast(persistenceId: String, projectionId: String): ZIO[Any, LoadError, Option[Offseted[Unit]]] =
    storage.get(s"$persistenceId-$projectionId")
}

object InMemoryProjectionOffsetStorage {
  val live: ZLayer[Any, Nothing, ProjectionOffsetStorage] = ZLayer.fromZIO {
    for {
      ref <- ConcurrentMap.make[String, Offseted[Unit]]()
    } yield new InMemoryProjectionOffsetStorage(ref)
  }
}
