package pl.mlynik

import pl.mlynik.journal.{ EntityRef, Journal, SnapshotStorage, Storage }
import zio.concurrent.*
import zio.*

trait EntityManager[R, CENV, COMMAND, CERR, EVENT, STATE] {
  def getOrCreate(persistenceId: String)(
    entityRef: ZIO[
      CENV & Journal[EVENT] & SnapshotStorage[STATE],
      Storage.LoadError,
      EntityRef[R, CENV, COMMAND, CERR, EVENT, STATE]
    ]
  )(implicit
    trace: Trace
  ): ZIO[CENV & Journal[EVENT] & SnapshotStorage[STATE], Storage.LoadError, EntityRef[
    R,
    CENV,
    COMMAND,
    CERR,
    EVENT,
    STATE
  ]]
}

object EntityManager {
  class Impl[R, CENV, COMMAND, CERR, EVENT, STATE](
    ref: ConcurrentMap[String, EntityRef[R, CENV, COMMAND, CERR, EVENT, STATE]]
  ) extends EntityManager[R, CENV, COMMAND, CERR, EVENT, STATE] {
    def getOrCreate(persistenceId: String)(
      entityRef: ZIO[
        CENV & Journal[EVENT] & SnapshotStorage[STATE],
        Storage.LoadError,
        EntityRef[R, CENV, COMMAND, CERR, EVENT, STATE]
      ]
    )(implicit trace: Trace): ZIO[
      CENV & Journal[EVENT] & SnapshotStorage[STATE],
      Storage.LoadError,
      EntityRef[R, CENV, COMMAND, CERR, EVENT, STATE]
    ] =
      ref.get(persistenceId).flatMap {
        case Some(value) => ZIO.succeed(value)
        case None        => entityRef.flatMap(r => ref.put(persistenceId, r)) *> getOrCreate(persistenceId)(entityRef)
      }
  }

  def live[R: Tag, CENV: Tag, COMMAND: Tag, CERR: Tag, EVENT: Tag, STATE: Tag]
    : ZLayer[Any, Nothing, EntityManager[R, CENV, COMMAND, CERR, EVENT, STATE]] =
    ZLayer.fromZIO(for {
      mp <- ConcurrentMap.make[String, EntityRef[R, CENV, COMMAND, CERR, EVENT, STATE]]()
    } yield new Impl(mp))
}
