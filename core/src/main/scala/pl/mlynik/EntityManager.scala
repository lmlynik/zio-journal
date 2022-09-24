package pl.mlynik

import pl.mlynik.journal.{ EntityRef, Journal, SnapshotStorage, Storage }
import zio.concurrent.*
import zio.*

trait EntityManager[COMMAND, EVENT, STATE] {

  def getOrCreate(persistenceId: String)(
    entityRef: ZIO[Journal[EVENT] & SnapshotStorage[STATE], Storage.LoadError, EntityRef[COMMAND, EVENT, STATE]]
  ): ZIO[Journal[EVENT] & SnapshotStorage[STATE], Storage.LoadError, EntityRef[COMMAND, EVENT, STATE]]
}

object EntityManager {
  class Impl[COMMAND, EVENT, STATE](ref: ConcurrentMap[String, EntityRef[COMMAND, EVENT, STATE]])
      extends EntityManager[COMMAND, EVENT, STATE] {
    def getOrCreate(persistenceId: String)(
      entityRef: ZIO[Journal[EVENT] & SnapshotStorage[STATE], Storage.LoadError, EntityRef[COMMAND, EVENT, STATE]]
    ): ZIO[Journal[EVENT] & SnapshotStorage[STATE], Storage.LoadError, EntityRef[COMMAND, EVENT, STATE]] = {
      val w = ref.get(persistenceId).flatMap {
        case Some(value) => ZIO.succeed(value)
        case None        => entityRef.flatMap(r => ref.put(persistenceId, r)) *> getOrCreate(persistenceId)(entityRef)
      }

      w
    }
  }

  def live[COMMAND: Tag, EVENT: Tag, STATE: Tag]: ZLayer[Any, Nothing, EntityManager[COMMAND, EVENT, STATE]] =
    ZLayer.fromZIO(for {
      mp <- ConcurrentMap.make[String, EntityRef[COMMAND, EVENT, STATE]]()
    } yield new Impl(mp))
}
