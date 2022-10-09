package pl.mlynik

import pl.mlynik.journal.Journal
import zio._
import pl.mlynik.journal.ProjectionOffsetStorage
import pl.mlynik.journal.Storage.Offseted
import pl.mlynik.journal.Storage.LoadError
import pl.mlynik.journal.Storage.PersistError
import pl.mlynik.journal.Storage.LoadMode
import zio.stream.ZStream

object EventProjection {

  val batchSize = 4096

  private def loadOffset(
    persistenceId: String,
    projectionId: String
  ): ZIO[ProjectionOffsetStorage, LoadError, Offseted[? >: Unit]] =
    ZIO
      .serviceWithZIO[ProjectionOffsetStorage](_.loadLast(persistenceId, projectionId))
      .flatMap(offset => ZIO.fromOption(offset).orElseSucceed(Offseted.zero))

  private def saveOffset(
    persistenceId: String,
    projectionId: String,
    offset: Offseted[Unit]
  ): ZIO[ProjectionOffsetStorage, PersistError, Unit] =
    ZIO
      .serviceWithZIO[ProjectionOffsetStorage](_.store(persistenceId, projectionId, offset))

  def events[EVENT: Tag](
    persistenceId: String,
    projectionId: String
  ): ZStream[Journal[EVENT] & ProjectionOffsetStorage, PersistError | LoadError, Offseted[EVENT]] = {
    val pullEvents: ZIO[Journal[EVENT] & ProjectionOffsetStorage, PersistError | LoadError, Chunk[Offseted[EVENT]]] =
      for {
        offset <- loadOffset(persistenceId, projectionId)
        events <-
          ZIO.serviceWithZIO[Journal[EVENT]](
            _.load(persistenceId, offset.offset, LoadMode.Batch(batchSize)).take(batchSize).runCollect
          )
        offsets = events.map(_.offset)

        _ <- events.lastOption.fold(ZIO.unit)(no =>
               saveOffset(persistenceId, projectionId, no.copy(offset = no.offset + 1))
             )

      } yield events

    ZStream.repeatZIOChunk(pullEvents)
  }
}
