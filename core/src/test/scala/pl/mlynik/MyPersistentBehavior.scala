package pl.mlynik

import zio._
import pl.mlynik.journal.Journal
import pl.mlynik.Effect.EffectIO
import pl.mlynik.journal.EntityRef
import pl.mlynik.journal.Storage.LoadError
import pl.mlynik.journal.SnapshotStorage

object MyPersistentBehavior {

  import EventSourcedEntity.*

  enum FailureMode {
    case Success
    case Fail
    case Die
  }
  enum Command     {
    case NextMessage(value: String)
    case NextMessageDoubleAndFail(value: String, failureMode: FailureMode)
    case Clear
    case Get
    case Fail
    case Die
    case Log
  }

  enum Error {
    case FailResponse
  }

  enum Event {
    case NextMessageAdded(value: String)
    case Cleared
    case Logged(spans: List[String])
  }

  final case class State(numbers: List[String] = Nil)

  def commandHandler(ref: Ref[String])(state: State, cmd: Command)(using trace: Trace): EffectIO[Any, Error, Event] = {
    import Effect.*
    cmd match
      case Command.NextMessage(value)                           =>
        persistZIO(Event.NextMessageAdded(value))
      case Command.Clear                                        =>
        persistZIO(Event.Cleared) >>> snapshotZIO
      case Command.Get                                          =>
        reply(ZIO.succeed(state.numbers))
      case Command.Fail                                         =>
        reply(ZIO.fail(Error.FailResponse))
      case Command.Die                                          =>
        ZIO.die(new Throwable("i'm dead"))
      case Command.NextMessageDoubleAndFail(value, failureMode) =>
        val maybeFail: IO[Error, Effect[Event]] =
          failureMode match
            case FailureMode.Success =>
              none
            case FailureMode.Fail    =>
              ZIO.fail(
                Error.FailResponse
              )
            case FailureMode.Die     =>
              ZIO.die(
                new Throwable(
                  "i'm dead"
                )
              )

        persistZIO(Event.NextMessageAdded(value)) >>>
          snapshotZIO >>>
          runZIO(ref.set(value)) >>>
          maybeFail >>>
          persistZIO(Event.NextMessageAdded(value))

      case Command.Log =>
        ZIO.logSpan("handling of log command") {
          FiberRef.currentLogSpan.get.flatMap { spans =>
            ZIO.log("Log Command").delay(10.millis) *> persistZIO(Event.Logged(spans.map(_.label)))
          }
        }
  }

  def eventHandler(state: State, evt: Event): ZIO[Any, Nothing, State] =
    evt match
      case Event.NextMessageAdded(value) =>
        ZIO
          .succeed(state.copy(numbers = state.numbers :+ value))
      case Event.Cleared                 =>
        ZIO.succeed(State())
      case Event.Logged(spans)           =>
        ZIO.succeed(State(spans))

  type EntityType = ZIO[SnapshotStorage[
    State
  ] & Journal[Event] & EntityManager[Any, Any, Command, Error, Event, State], LoadError, EntityRef[
    Any,
    Any,
    Command,
    Error,
    Event,
    State
  ]]

  def apply(id: String): EntityType                   =
    Ref.make("").flatMap { ref =>
      apply(id, ref)
    }
  def apply(id: String, ref: Ref[String]): EntityType =
    EventSourcedEntity[Any, Any, Command, Error, Event, State](
      persistenceId = id,
      emptyState = State(),
      commandHandler = commandHandler(ref),
      eventHandler = eventHandler
    )
}
