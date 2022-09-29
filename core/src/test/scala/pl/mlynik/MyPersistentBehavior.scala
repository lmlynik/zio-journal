package pl.mlynik

import zio._
import pl.mlynik.journal.Journal
import pl.mlynik.Effect.EffectIO

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

  def commandHandler(state: State, cmd: Command)(using trace: Trace): EffectIO[Any, Error, Event] = {
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
        val failTransaction: IO[Error, Effect[Event]] =
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
          persistZIO(Event.NextMessageAdded(value)) >>>
          snapshotZIO >>>
          failTransaction

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

  def apply(id: String) =
    EventSourcedEntity[Any, Any, Command, Error, Event, State](
      persistenceId = id,
      emptyState = State(),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
