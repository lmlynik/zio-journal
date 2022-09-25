package pl.mlynik

import zio.*
import zio.test.*
import zio.test.TestAspect.*
import zio.test.{ test, * }
import zio.test.Assertion.*
import pl.mlynik.journal.*
import pl.mlynik.journal.Storage.Offseted
import pl.mlynik.journal.serde.ZIOJSONSerde
import zio.json.*

object MyPersistentBehavior {

  import EventSourcedEntity.*
  enum Command {
    case NextMessage(value: String)
    case Clear
    case Get
    case Fail
  }

  case object FailResponse

  enum Event {
    case NextMessageAdded(value: String)
    case Cleared
  }

  final case class State(numbers: List[String] = Nil)

  def apply(id: String) =
    EventSourcedEntity[Any, Command, Event, State](
      persistenceId = id,
      emptyState = State(),
      commandHandler = (state, cmd) =>
        cmd match
          case Command.NextMessage(value) => Effect.persistZIO(Event.NextMessageAdded(value))
          case Command.Clear              =>
            Effect.persistZIO(Event.Cleared) >>> Effect.snapshotZIO
          case Command.Get                =>
            Effect.reply(ZIO.succeed(state.numbers))
          case Command.Fail               =>
            Effect.reply(ZIO.fail(FailResponse))
      ,
      eventHandler = (state, evt) =>
        evt match
          case Event.NextMessageAdded(value) =>
            ZIO
              .succeed(state.copy(numbers = state.numbers :+ value))
          case Event.Cleared                 => ZIO.succeed(State())
    )
}
object EventSourcedEntitySpec extends ZIOSpecDefault {
  import MyPersistentBehavior.*
  def spec =
    suite("EventSourcedEntitySpec")(
      test("Accepts commands which update states") {
        for {
          entity <- MyPersistentBehavior("1")
          _      <- entity.send(Command.NextMessage("message"))
          state  <- entity.state
        } yield assert(state.numbers)(equalTo(List("message")))
      },
      test("Accepts asks which returns value") {
        for {
          entity   <- MyPersistentBehavior("1")
          _        <- entity.send(Command.NextMessage("message"))
          response <- entity.ask[Nothing, List[Long]](Command.Get)
        } yield assert(response)(equalTo(List("message")))
      },
      test("Accepts asks which returns errors") {
        for {
          entity   <- MyPersistentBehavior("1")
          _        <- entity.send(Command.NextMessage("message"))
          response <- entity.ask[FailResponse.type, List[Long]](Command.Fail).either
        } yield assert(response)(isLeft(equalTo(FailResponse)))
      },
      test("Accepts and handles commands in correct order") {
        def run(id: String) = for {
          entity   <- MyPersistentBehavior(id)
          ns       <- ZIO.foreach(1 to 1000)(_ => Random.nextInt)
          _        <- ZIO.foreach(ns)(n => entity.send(Command.NextMessage(s"message $n")))
          response <- entity.ask[Nothing, List[Long]](Command.Get)
        } yield (ns, response)
        for {
          f1 <- run("1").fork
          f2 <- run("2").fork
          fs <- f1.zip(f2).join
        } yield assert(fs._1)(equalTo(fs._1)) &&
          assert(fs._2)(equalTo(fs._2)) &&
          assert(fs._3._1)(equalTo(fs._3._1)) &&
          assert(fs._3._2)(equalTo(fs._3._2)) &&
          assert(fs._3._1)(not(equalTo(fs._1)))
      },
      test("Stores snapshot and replays the journal from the correct offseted event") {

        for {
          entity   <- MyPersistentBehavior("1")
          _ <- entity.send(Command.NextMessage("message")) // 0
          _ <- entity.send(Command.NextMessage("message")) // 1
          _ <- entity.send(Command.NextMessage("message")) // 2
          _ <- entity.send(Command.Clear)                  // 3
          snapshot <- ZIO
                        .serviceWith[SnapshotStorage[Any, State]](_.loadLast("1"))
                        .flatten
                        .flatMap(f => ZIO.fromOption(f).orElseFail(new Error))

          _ <- entity.send(Command.NextMessage("message")) // 4
          lastEvent <- ZIO.serviceWith[Journal[Any, Event]](_.load("1", snapshot.offset).runCollect.map(_.head)).flatten
        } yield assert(snapshot)(equalTo(Offseted(4, State()))) && assert(lastEvent.offset)(equalTo(4))
      },
      test("calling same entity doesn't corrupt state") {
        def run(id: String, message: String) =
          MyPersistentBehavior(id).flatMap { entity =>
            ZIO.foreach(1 to 3) { x =>
              val msg = message + " " + x
              entity.send(Command.NextMessage(msg)) *> ZIO.sleep(50.millis)
            }
          }
        for {
          id     <- Random.nextString(10)
          f1     <- run(id, "Hello").repeatN(3).delay(0.millis).fork
          f2     <- run(id, "World").repeatN(3).delay(25.millis).fork
          _      <- f1.await
          _      <- f2.await
          entity <- MyPersistentBehavior(id)
          state  <- entity.ask[Nothing, List[Long]](Command.Get)
          _      <- entity.send(Command.Clear)
        } yield assert(state)(
          equalTo(
            List(
              "Hello 1",
              "World 1",
              "Hello 2",
              "World 2",
              "Hello 3",
              "World 3",
              "Hello 1",
              "World 1",
              "Hello 2",
              "World 2",
              "Hello 3",
              "World 3",
              "Hello 1",
              "World 1",
              "Hello 2",
              "World 2",
              "Hello 3",
              "World 3",
              "Hello 1",
              "World 1",
              "Hello 2",
              "World 2",
              "Hello 3",
              "World 3"
            )
          )
        )
      } @@ withLiveClock @@ withLiveRandom @@ nonFlaky
    ).provide(
      EntityManager.live[Any, MyPersistentBehavior.Command, MyPersistentBehavior.Event, MyPersistentBehavior.State],
      InMemoryJournal.live[MyPersistentBehavior.Event],
      InSnapshotStorage.live[MyPersistentBehavior.State],
      ZIOJSONSerde.live[MyPersistentBehavior.Event]
    )
}
