package pl.mlynik

import pl.mlynik.journal.{ EntityRef, Journal, SnapshotStorage, Storage }
import zio.concurrent.*
import zio.*

trait EntityManager[R, COMMAND, CERR, EVENT, STATE] {
  def getOrCreate(persistenceId: String)(
    entityRef: ZIO[
      R & Journal[R, EVENT] & SnapshotStorage[R, STATE],
      Storage.LoadError,
      EntityRef[R, COMMAND, CERR, EVENT, STATE]
    ]
  )(implicit
    trace: Trace
  ): ZIO[R & Journal[R, EVENT] & SnapshotStorage[R, STATE], Storage.LoadError, EntityRef[
    R,
    COMMAND,
    CERR,
    EVENT,
    STATE
  ]]
}

object EntityManager {
  class Impl[R, COMMAND, CERR, EVENT, STATE](ref: ConcurrentMap[String, EntityRef[R, COMMAND, CERR, EVENT, STATE]])
      extends EntityManager[R, COMMAND, CERR, EVENT, STATE] {
    def getOrCreate(persistenceId: String)(
      entityRef: ZIO[
        R & Journal[R, EVENT] & SnapshotStorage[R, STATE],
        Storage.LoadError,
        EntityRef[R, COMMAND, CERR, EVENT, STATE]
      ]
    )(implicit trace: Trace): ZIO[
      R & Journal[R, EVENT] & SnapshotStorage[R, STATE],
      Storage.LoadError,
      EntityRef[R, COMMAND, CERR, EVENT, STATE]
    ] =
      ref.get(persistenceId).flatMap {
        case Some(value) => ZIO.succeed(value)
        case None        => entityRef.flatMap(r => ref.put(persistenceId, r)) *> getOrCreate(persistenceId)(entityRef)
      }
  }

  def live[R: Tag, COMMAND: Tag, CERR: Tag, EVENT: Tag, STATE: Tag]
    : ZLayer[Any, Nothing, EntityManager[R, COMMAND, CERR, EVENT, STATE]] =
    ZLayer.fromZIO(for {
      mp <- ConcurrentMap.make[String, EntityRef[R, COMMAND, CERR, EVENT, STATE]]()
    } yield new Impl(mp))
}
