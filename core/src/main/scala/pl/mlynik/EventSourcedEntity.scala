package pl.mlynik

import pl.mlynik.journal.{ Journal, SnapshotStorage, Storage }
import pl.mlynik.journal.Storage.Offseted
import pl.mlynik.journal.serde.Serde
import zio.*

object EventSourcedEntity {

  trait EntityRef[COMMAND, EVENT, STATE] {
    def state: UIO[STATE]

    def send(command: COMMAND): ZIO[SnapshotStorage[STATE] & Journal[EVENT], Storage.PersistError, Unit]

    def ask[E, A](command: COMMAND): ZIO[SnapshotStorage[STATE] & Journal[EVENT], Storage.PersistError | E, A]
  }

  enum Effect[+EVENT] {
    case Persist(event: EVENT)
    case Snapshot
    case None
    case Reply[E, A](result: IO[E, A]) extends Effect[Nothing]
    case Complex(effects: List[Effect[EVENT]])
  }

  object Effect {
    def persistZIO[EVENT](event: EVENT): UIO[Effect[EVENT]] = ZIO.succeed(Persist(event))

    def snapshotZIO[EVENT]: UIO[Effect[EVENT]] = ZIO.succeed(Snapshot)

    val none: UIO[Effect[Nothing]] = ZIO.succeed(None)

    def complexZIO[EVENT](effects: UIO[Effect[EVENT]]*): UIO[Effect[EVENT]] =
      ZIO.collectAll(effects).map(f => Complex(f.toList))

    def reply[EVENT, E, A](result: IO[E, A]): UIO[Effect[EVENT]] =
      ZIO.succeed(Reply(result))
  }

  def apply[COMMAND, EVENT: Tag, STATE: Tag](
    persistenceId: String,
    emptyState: STATE,
    commandHandler: (STATE, COMMAND) => URIO[Journal[EVENT], Effect[EVENT]],
    eventHandler: (STATE, EVENT) => UIO[STATE]
  ): ZIO[Journal[EVENT] with SnapshotStorage[STATE], Storage.LoadError, EntityRef[COMMAND, EVENT, STATE]] = {

    final case class State(offset: Int, entity: STATE) {
      def updateState(entity: STATE): State = this.copy(offset = offset + 1, entity = entity)

      def updateState(offset: Int, entity: STATE): State = this.copy(offset = offset, entity = entity)
    }

    def journalPlayback(
      currentState: Ref.Synchronized[State]
    ) =
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

    def handleEffect[E, A](
      state: State,
      resultPromise: Promise[E, A]
    )(effect: Effect[EVENT]): ZIO[SnapshotStorage[STATE] with Journal[EVENT], Storage.PersistError, State] =
      for {
        journal         <- ZIO.service[Journal[EVENT]]
        snapshotStorage <- ZIO.service[SnapshotStorage[STATE]]
        newState        <- effect match
                             case Effect.Persist(event)   =>
                               (journal.persist(persistenceId, event) *> eventHandler(state.entity, event))
                                 .map(state.updateState)
                             case Effect.Snapshot         =>
                               snapshotStorage.store(persistenceId, Offseted(state.offset, state.entity)).as(state)
                             case Effect.Complex(effects) =>
                               ZIO.foldLeft(effects)(state) { case (state, effect) =>
                                 handleEffect(state, resultPromise)(effect)
                               }
                             case Effect.Reply(value)     =>
                               // TODO providing wrong type causes a runtime error!
                               resultPromise.completeWith(value.mapBoth(_.asInstanceOf[E], _.asInstanceOf[A])).as(state)
                             case Effect.None             => ZIO.succeed(state)
      } yield newState

    def handleCommand[E, A](
      cmd: COMMAND,
      currentState: Ref.Synchronized[State],
      resultPromise: Promise[E, A]
    ): ZIO[SnapshotStorage[STATE] & Journal[EVENT], Storage.PersistError, State] =
      currentState.updateAndGetZIO { st =>
        commandHandler(st.entity, cmd).flatMap(handleEffect(st, resultPromise))
      } <* resultPromise.isDone.flatMap(done => ZIO.unless(done)(resultPromise.succeed(().asInstanceOf[A])))

    for {
      currentState <- Ref.Synchronized.make(State(0, emptyState))
      _            <- journalPlayback(currentState)
      _            <- ZIO.logInfo(s"Loaded $persistenceId")

    } yield new EntityRef[COMMAND, EVENT, STATE] {
      override def state: UIO[STATE] = currentState.get.map(_._2)

      override def send(
        command: COMMAND
      ): ZIO[SnapshotStorage[STATE] & Journal[EVENT], Storage.PersistError, Unit] =
        for {
          promise <- Promise.make[Nothing, Unit]
          _       <- handleCommand(command, currentState, promise)
          result  <- promise.await
        } yield result

      def ask[E, A](command: COMMAND): ZIO[SnapshotStorage[STATE] & Journal[EVENT], Storage.PersistError | E, A] = for {
        promise <- Promise.make[E, A]
        _       <- handleCommand(command, currentState, promise)
        result  <- promise.await
      } yield result
    }
  }
}
