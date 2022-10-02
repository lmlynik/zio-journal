package pl.mlynik

import pl.mlynik.journal.{ EntityRef, Journal, SnapshotStorage, Storage }
import pl.mlynik.journal.Storage.Offseted
import pl.mlynik.journal.serde.Serde
import zio.*

import scala.annotation.targetName

object EventSourcedEntity {

  type EntityEnvironment[R, CENV, COMMAND, CERR, EVENT, STATE] = R & SnapshotStorage[STATE] & Journal[EVENT] &
    EntityManager[R, CENV, COMMAND, CERR, EVENT, STATE]

  def apply[R: Tag, CENV: Tag, COMMAND: Tag, CERR: Tag, EVENT: Tag, STATE: Tag](
    persistenceId: String,
    emptyState: STATE,
    commandHandler: (STATE, COMMAND) => Trace ?=> ZIO[CENV & Journal[EVENT], CERR, Effect[EVENT]],
    eventHandler: (STATE, EVENT) => Trace ?=> UIO[STATE]
  )(implicit trace: Trace): ZIO[
    CENV & SnapshotStorage[STATE] & Journal[EVENT] & EntityManager[R, CENV, COMMAND, CERR, EVENT, STATE],
    Storage.LoadError,
    EntityRef[R, CENV, COMMAND, CERR, EVENT, STATE]
  ] = {

    final case class State(offset: Long, entity: STATE) {
      def updateState(entity: STATE): State = this.copy(offset = offset + 1, entity = entity)

      def updateState(offset: Long, entity: STATE): State = this.copy(offset = offset, entity = entity)
    }

    inline def journalPlayback(
      currentState: Ref.Synchronized[State]
    )(implicit trace: Trace) =
      currentState.updateAndGetZIO { st =>
        for {
          snapshot    <- ZIO.serviceWith[SnapshotStorage[STATE]](_.loadLast(persistenceId)).flatten
          eventStream <-
            ZIO.serviceWith[Journal[EVENT]](_.load(persistenceId, snapshot.map(_.offset).getOrElse(st.offset)))
          state       <- eventStream.runFoldZIO(st) { case (state, Offseted(offset, event)) =>
                           eventHandler(state.entity, event).map(stateD => st.updateState(offset, stateD))
                         }
        } yield state
      }

    def handleEffect[A](
      state: State,
      command: COMMAND,
      resultPromise: Promise[CERR, A],
      stash: Queue[COMMAND],
      effect: Effect[EVENT]
    )(implicit trace: Trace): ZIO[CENV & Journal[EVENT] & SnapshotStorage[STATE], Storage.PersistError | CERR, State] =
      for {
        journal         <- ZIO.service[Journal[EVENT]]
        snapshotStorage <- ZIO.service[SnapshotStorage[STATE]]
        newState        <- effect match
                             case Effect.Persist(event)   =>
                               journal.persist(persistenceId, state.offset, event)
                                 *> eventHandler(state.entity, event).map(state.updateState)
                             case Effect.Snapshot         =>
                               snapshotStorage.store(persistenceId, Offseted(state.offset, state.entity)).as(state)
                             case Effect.Complex(effects) =>
                               ZIO.foldLeft(effects)(state) { case (state, effect) =>
                                 ZIO.logDebug(s"Effect $effect") *>
                                   ZIO.suspendSucceed(handleEffect(state, command, resultPromise, stash, effect))
                               }

                             case Effect.Reply(value) =>
                               // TODO providing wrong type causes a runtime error!
                               resultPromise.completeWith(value.mapBoth(_.asInstanceOf[CERR], _.asInstanceOf[A])).as(state)

                             case Effect.Run[CENV](z) =>
                               ZIO.suspendSucceed(z).as(state)

                             case Effect.Stash =>
                               stash.offer(command).as(state)

                             case Effect.Unstash =>
                               stash.takeAll.flatMap { commands =>
                                 ZIO.foldLeft(commands)(state) { case (state, command) =>
                                   ZIO.logDebug(s"Unstashing command $command") *>
                                     handleCmd(state, command, resultPromise, stash)
                                 }
                               }

                             case Effect.None =>
                               ZIO.succeed(state)
      } yield newState

    def handleCmd[A](st: State, cmd: COMMAND, resultPromise: Promise[CERR, A], stash: Queue[COMMAND]) =
      commandHandler(st.entity, cmd).flatMap(eff => handleEffect(st, cmd, resultPromise, stash, eff))

    def handleCommand[A](
      cmd: COMMAND,
      currentState: Ref.Synchronized[State],
      resultPromise: Promise[CERR, A],
      stash: Queue[COMMAND]
    )(implicit trace: Trace): ZIO[CENV & Journal[EVENT] & SnapshotStorage[STATE], Storage.PersistError | CERR, State] =
      currentState.updateAndGetZIO { st =>
        handleCmd(st, cmd, resultPromise, stash)
      } <* resultPromise.isDone.flatMap(done => ZIO.unless(done)(resultPromise.succeed(().asInstanceOf[A])))

    def entity: ZIO[CENV & Journal[EVENT] & SnapshotStorage[STATE], Storage.LoadError, EntityRef[
      R,
      CENV,
      COMMAND,
      CERR,
      EVENT,
      STATE
    ]] =
      for {
        currentState <- Ref.Synchronized.make(State(0, emptyState))
        _            <- journalPlayback(currentState)
        stash        <- Queue.unbounded[COMMAND]
        _            <- ZIO.logInfo(s"Loaded $persistenceId")

      } yield new EntityRef[R, CENV, COMMAND, CERR, EVENT, STATE] {
        override def state(implicit trace: Trace): UIO[STATE] = currentState.get.map(_._2)

        override def send(
          command: COMMAND
        )(implicit
          trace: Trace
        ): ZIO[CENV & Journal[EVENT] & SnapshotStorage[STATE], Storage.PersistError | CERR, Unit] =
          for {
            promise <- Promise.make[CERR, Unit]
            _       <- handleCommand(command, currentState, promise, stash)
            result  <- promise.await
          } yield result

        def ask[A](
          command: COMMAND
        )(implicit trace: Trace): ZIO[CENV & Journal[EVENT] & SnapshotStorage[STATE], Storage.PersistError | CERR, A] =
          for {
            promise <- Promise.make[CERR, A]
            _       <- handleCommand(command, currentState, promise, stash)
            result  <- promise.await
          } yield result
      }

    ZIO.serviceWithZIO[EntityManager[R, CENV, COMMAND, CERR, EVENT, STATE]] { mgr =>
      mgr.getOrCreate(persistenceId)(entity)
    }
  }
}
