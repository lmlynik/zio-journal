package pl.mlynik

import zio.*
import zio.test.*
import zio.test.TestAspect.*
import zio.test.{ test, * }
import zio.test.Assertion.*
import pl.mlynik.journal.*
import pl.mlynik.journal.Storage.Offseted
import pl.mlynik.journal.serde.ZIOJSONSerde

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

  case object FailResponse

  enum Event {
    case NextMessageAdded(value: String)
    case Cleared
    case Logged(spans: List[String])
  }

  final case class State(numbers: List[String] = Nil)

  def apply(id: String) =
    EventSourcedEntity[Any, Command, Event, State](
      persistenceId = id,
      emptyState = State(),
      commandHandler = (state, cmd) =>
        import Effect.*
        cmd match
          case Command.NextMessage(value)                           =>
            persistZIO(Event.NextMessageAdded(value))
          case Command.Clear                                        =>
            persistZIO(Event.Cleared) >>> snapshotZIO
          case Command.Get                                          =>
            reply(ZIO.succeed(state.numbers))
          case Command.Fail                                         =>
            reply(ZIO.fail(FailResponse))
          case Command.Die                                          =>
            ZIO.die(new Error("i'm dead"))
          case Command.NextMessageDoubleAndFail(value, failureMode) =>
            val failTransaction =
              failureMode match
                case FailureMode.Success => none
                case FailureMode.Fail    =>
                  reply(
                    ZIO.fail(
                      FailResponse
                    )
                  )
                case FailureMode.Die     =>
                  ZIO.die(
                    new Error(
                      "i'm dead"
                    )
                  )
            persistZIO(Event.NextMessageAdded(value)) >>>
              persistZIO(Event.NextMessageAdded(value)) >>>
              failTransaction

          case Command.Log =>
            ZIO.logSpan("handling of log command") {
              FiberRef.currentLogSpan.get.flatMap { spans =>
                ZIO.log("Log Command").delay(10.millis) *> persistZIO(Event.Logged(spans.map(_.label)))
              }
            }
      ,
      eventHandler = (state: State, evt: Event) =>
        evt match
          case Event.NextMessageAdded(value) =>
            ZIO
              .succeed(state.copy(numbers = state.numbers :+ value))
          case Event.Cleared                 =>
            ZIO.succeed(State())
          case Event.Logged(spans)           =>
            ZIO.succeed(State(spans))
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
          response <- entity.ask[Nothing, List[String]](Command.Get)
        } yield assert(response)(equalTo(List("message")))
      },
      test("Accepts asks which returns errors") {
        for {
          entity   <- MyPersistentBehavior("1")
          _        <- entity.send(Command.NextMessage("message"))
          response <- entity.ask[FailResponse.type, List[String]](Command.Fail).either
        } yield assert(response)(isLeft(equalTo(FailResponse)))
      },
      test("handles commands which die") {
        for {
          entity <- MyPersistentBehavior("1")
          r      <- entity.send(Command.Die).absorbWith(_ => new Error).either
        } yield assert(r)(isLeft(hasField("message", _.getMessage, equalTo("i'm dead"))))
      },
      test("multiple persists within a transaction and die should invalidate the events") {
        for {
          entity <- MyPersistentBehavior("1")
          r      <- entity
                      .send(Command.NextMessageDoubleAndFail("I'm virus", FailureMode.Die))
                      .absorbWith(_ => new Error)
                      .either
          state  <- entity.state
        } yield assert(r)(isLeft(hasField("message", _.getMessage, equalTo("i'm dead")))) && assert(state)(
          equalTo(State())
        )
      },
      test("multiple persists within a transaction and fail should invalidate the events") {
        for {
          entity <- MyPersistentBehavior("1")
          r      <- entity
                      .send[FailResponse.type](Command.NextMessageDoubleAndFail("I'm virus", FailureMode.Fail))
                      .either
          state  <- entity.state
        } yield assert(state)(
          equalTo(State())
        )
      } @@ ignore,
      test("propagates trace to the command handler") {
        for {
          entity <- MyPersistentBehavior("1")
          _      <- ZIO.logSpan("parent of log command") {
                      ZIO.logSpan("sending log command")(entity.send(Command.Log)).delay(50.millis)
                    }
          state  <- entity.state
        } yield assert(state)(
          equalTo(State(List("handling of log command", "sending log command", "parent of log command")))
        )
      } @@ withLiveClock,
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
              entity.send(Command.NextMessage(msg)) *> ZIO.sleep(20.millis)
            }
          }
        for {
          id     <- Random.nextString(10)
          f1     <- run(id, "Hello").repeatN(3).delay(0.millis).fork
          f2     <- run(id, "World").repeatN(3).delay(10.millis).fork
          _      <- f1.join
          _      <- f2.join
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
      } @@ withLiveClock @@ withLiveRandom
    ).provide(
      EntityManager.live[Any, MyPersistentBehavior.Command, MyPersistentBehavior.Event, MyPersistentBehavior.State],
      InMemoryJournal.live[MyPersistentBehavior.Event],
      InSnapshotStorage.live[MyPersistentBehavior.State],
      ZIOJSONSerde.live[MyPersistentBehavior.Event]
    )
}
