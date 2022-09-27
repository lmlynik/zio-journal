package pl.mlynik

import pl.mlynik.journal.{ EntityRef, Journal, SnapshotStorage, Storage }
import pl.mlynik.journal.Storage.Offseted
import pl.mlynik.journal.serde.Serde
import zio.*

import scala.annotation.targetName

object EventSourcedEntity {

  enum Effect[+EVENT] {
    case Persist(event: EVENT)
    case Snapshot
    case None
    case Reply[E, A](result: IO[E, A]) extends Effect[Nothing]
    case Complex(effects: List[Effect[EVENT]])
  }

  object Effect {

    type EffectIO[Err, EVENT] = IO[Err, Effect[EVENT]]
    def persistZIO[EVENT](event: EVENT): UIO[Effect[EVENT]] = ZIO.succeed(Persist(event))

    def snapshotZIO[EVENT]: UIO[Effect[EVENT]] = ZIO.succeed(Snapshot)

    val none: UIO[Effect[Nothing]] = ZIO.succeed(None)

    def complexZIO[Err, EVENT](effects: EffectIO[Err, EVENT]*): EffectIO[Err, EVENT] =
      ZIO.collectAll(effects).map(f => Complex(f.toList))

    def reply[EVENT, A](result: A): UIO[Effect[EVENT]] =
      ZIO.succeed(Reply(ZIO.succeed(result)))

    def reply[EVENT, E, A](result: IO[E, A]): UIO[Effect[EVENT]] =
      ZIO.succeed(Reply(result))

    def fail[EVENT, E, A](result: IO[E, A]): UIO[Effect[EVENT]] =
      ZIO.succeed(Reply(result))

    extension [EVENT, Err](ef1: EffectIO[Err, EVENT]) {
      @targetName("andThen")
      def >>>(ef2: EffectIO[Err, EVENT]) = complexZIO(ef1, ef2)
    }
  }

  def apply[R: Tag, COMMAND: Tag, CERR: Tag, EVENT: Tag, STATE: Tag](
    persistenceId: String,
    emptyState: STATE,
    commandHandler: (STATE, COMMAND) => Trace ?=> ZIO[R & Journal[R, EVENT], CERR, Effect[EVENT]],
    eventHandler: (STATE, EVENT) => Trace ?=> UIO[STATE]
  )(implicit trace: Trace): ZIO[
    R & SnapshotStorage[R, STATE] & Journal[R, EVENT] & EntityManager[R, COMMAND, CERR, EVENT, STATE],
    Storage.LoadError,
    EntityRef[R, COMMAND, CERR, EVENT, STATE]
  ] = {

    type Persistence = R & SnapshotStorage[R, STATE] & Journal[R, EVENT]

    final case class State(offset: Long, entity: STATE) {
      def updateState(entity: STATE): State = this.copy(offset = offset + 1, entity = entity)

      def updateState(offset: Long, entity: STATE): State = this.copy(offset = offset, entity = entity)
    }

    inline def journalPlayback(
      currentState: Ref.Synchronized[State]
    )(implicit trace: Trace) =
      currentState.updateAndGetZIO { st =>
        for {
          snapshot    <- ZIO.serviceWith[SnapshotStorage[R, STATE]](_.loadLast(persistenceId)).flatten
          eventStream <-
            ZIO.serviceWith[Journal[R, EVENT]](_.load(persistenceId, snapshot.map(_.offset).getOrElse(st.offset)))
          state       <- eventStream.runFoldZIO(st) { case (state, Offseted(offset, event)) =>
                           eventHandler(state.entity, event).map(stateD => st.updateState(offset, stateD))
                         }
        } yield state
      }

    def handleEffect[A](
      state: State,
      resultPromise: Promise[CERR, A],
      effect: Effect[EVENT]
    )(implicit trace: Trace): ZIO[Persistence, Storage.PersistError | CERR, State] =
      for {
        journal         <- ZIO.service[Journal[R, EVENT]]
        snapshotStorage <- ZIO.service[SnapshotStorage[R, STATE]]
        newState        <- effect match
                             case Effect.Persist(event)   =>
                               (journal.persist(persistenceId, state.offset, event) *> eventHandler(state.entity, event))
                                 .map(state.updateState)
                             case Effect.Snapshot         =>
                               snapshotStorage.store(persistenceId, Offseted(state.offset, state.entity)).as(state)
                             case Effect.Complex(effects) =>
                               ZIO.foldLeft(effects)(state) { case (state, effect) =>
                                 handleEffect(state, resultPromise, effect)
                               }
                             case Effect.Reply(value)     =>
                               // TODO providing wrong type causes a runtime error!
                               resultPromise.completeWith(value.mapBoth(_.asInstanceOf[CERR], _.asInstanceOf[A])).as(state)
                             case Effect.None             => ZIO.succeed(state)
      } yield newState

    def handleCommand[A](
      cmd: COMMAND,
      currentState: Ref.Synchronized[State],
      resultPromise: Promise[CERR, A]
    )(implicit trace: Trace): ZIO[Persistence, Storage.PersistError | CERR, State] =
      currentState.updateAndGetZIO { st =>
        commandHandler(st.entity, cmd).flatMap(eff => handleEffect(st, resultPromise, eff))
      } <* resultPromise.isDone.flatMap(done => ZIO.unless(done)(resultPromise.succeed(().asInstanceOf[A])))

    def entity: ZIO[Persistence, Storage.LoadError, EntityRef[R, COMMAND, CERR, EVENT, STATE]] =
      for {
        currentState <- Ref.Synchronized.make(State(0, emptyState))
        _            <- journalPlayback(currentState)
        _            <- ZIO.logInfo(s"Loaded $persistenceId")

      } yield new EntityRef[R, COMMAND, CERR, EVENT, STATE] {
        override def state(implicit trace: Trace): UIO[STATE] = currentState.get.map(_._2)

        override def send(
          command: COMMAND
        )(implicit trace: Trace): ZIO[Persistence, Storage.PersistError | CERR, Unit] =
          for {
            promise <- Promise.make[CERR, Unit]
            _       <- handleCommand(command, currentState, promise)
            result  <- promise.await
          } yield result

        def ask[A](
          command: COMMAND
        )(implicit trace: Trace): ZIO[Persistence, Storage.PersistError | CERR, A] =
          for {
            promise <- Promise.make[CERR, A]
            _       <- handleCommand(command, currentState, promise)
            result  <- promise.await
          } yield result
      }

    ZIO.serviceWithZIO[EntityManager[R, COMMAND, CERR, EVENT, STATE]] { mgr =>
      mgr.getOrCreate(persistenceId)(entity)
    }
  }
}
