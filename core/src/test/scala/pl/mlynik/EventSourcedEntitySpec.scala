package pl.mlynik

import zio._
import zio.test._
import zio.test.Assertion._

import pl.mlynik.journal._

object MyPersistentBehavior {

  import EventSourcedEntity.*
  enum Command {
    case NextNumber(value: Long)
    case Clear
    case Get
    case Fail
  }

  case object FailResponse

  enum Event {
    case NextNumberAdded(value: Long)
    case Cleared
  }

  final case class State(numbers: List[Long] = Nil)

  def apply(id: String) =
    EventSourcedEntity[Command, Event, State](
      persistenceId = id,
      emptyState = State(),
      commandHandler = (state, cmd) =>
        cmd match
          case Command.NextNumber(value) => Effect.persistZIO(Event.NextNumberAdded(value))
          case Command.Clear             => Effect.complexZIO(Effect.persistZIO(Event.Cleared), Effect.snapshotZIO)
          case Command.Get               =>
            Effect.reply(ZIO.succeed(state.numbers))
          case Command.Fail              =>
            Effect.reply(ZIO.fail(FailResponse))
      ,
      eventHandler = (state, evt) =>
        evt match
          case Event.NextNumberAdded(value) =>
            ZIO
              .succeed(state.copy(numbers = state.numbers :+ value))
          case Event.Cleared                => ZIO.succeed(State())
    )
}
object EventSourcedEntitySpec extends ZIOSpecDefault {
  import MyPersistentBehavior.*
  def spec =
    suite("EventSourcedEntitySpec")(
      test("Accepts commands which update states") {
        for {
          entity <- MyPersistentBehavior("1")
          _      <- entity.send(Command.NextNumber(13))
          state  <- entity.state
        } yield assert(state.numbers)(equalTo(List(13)))
      },
      test("Accepts asks which returns value") {
        for {
          entity   <- MyPersistentBehavior("1")
          _        <- entity.send(Command.NextNumber(13))
          response <- entity.ask[Nothing, List[Long]](Command.Get)
        } yield assert(response)(equalTo(List(13)))
      },
      test("Accepts asks which returns error") {
        for {
          entity   <- MyPersistentBehavior("1")
          _        <- entity.send(Command.NextNumber(13))
          response <- entity.ask[FailResponse.type, List[Long]](Command.Fail).either
        } yield assert(response)(isLeft(equalTo(FailResponse)))
      }
    ).provide(
      InMemoryJournal.live[MyPersistentBehavior.Event],
      NoopSnapshotStorage.live[MyPersistentBehavior.State]
    )
}
