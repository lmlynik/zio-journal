package pl.mlynik

import zio.*
import zio.test.*
import zio.test.TestAspect.*
import zio.test.{ test, * }
import zio.test.Assertion.*
import pl.mlynik.journal.*
import pl.mlynik.journal.Storage.Offseted
import pl.mlynik.journal.serde.ZIOJSONSerde
import pl.mlynik.journal.Storage.LoadError

object EventSourcedEntitySpec extends ZIOSpecDefault {
  import MyPersistentBehavior.*

  private final case class EventsAndSnapshot(events: Chunk[Offseted[Event]], snapshot: Option[Offseted[State]])

  private def getJournalAndSnapshot(
    id: String
  ): ZIO[Journal[Event] & SnapshotStorage[State], LoadError, EventsAndSnapshot] =
    for {
      events   <- ZIO.serviceWithZIO[Journal[MyPersistentBehavior.Event]](_.load("1", 0).runCollect)
      snapshot <- ZIO.serviceWithZIO[SnapshotStorage[State]](_.loadLast("1"))
    } yield EventsAndSnapshot(events, snapshot)

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
          response <- entity.ask[List[String]](Command.Get)
        } yield assert(response)(equalTo(List("message")))
      },
      test("Accepts asks which returns errors") {
        for {
          entity   <- MyPersistentBehavior("1")
          _        <- entity.send(Command.NextMessage("message"))
          response <- entity.ask[List[String]](Command.Fail).either
        } yield assert(response)(isLeft(equalTo(Error.FailResponse)))
      },
      test("handles commands which die") {
        for {
          entity <- MyPersistentBehavior("1")
          r      <- entity.send(Command.Die).absorbWith(_ => new Throwable).either
        } yield assert(r)(isLeft(hasField("message", _.getMessage, equalTo("i'm dead"))))
      },
      test("multiple persists within a transaction and success should maintain the events") {
        for {
          ref    <- Ref.make("")
          entity <- MyPersistentBehavior("1", ref)
          _      <- entity
                      .send(Command.NextMessageDoubleAndFail("I'm good", FailureMode.Success))
                      .either
          e      <- getJournalAndSnapshot("1")
          refVal <- ref.get
        } yield assert(e.events)(hasSize(equalTo(2))) && assert(e.snapshot)(isSome) && assert(refVal)(
          equalTo("I'm good")
        )
      },
      test("multiple persists within a transaction and die should invalidate the events") {
        for {
          ref    <- Ref.make("")
          entity <- MyPersistentBehavior("1", ref)
          r      <- entity
                      .send(Command.NextMessageDoubleAndFail("I'm virus", FailureMode.Die))
                      .absorbWith(_ => new Throwable)
                      .either
          state  <- entity.state
          e      <- getJournalAndSnapshot("1")
          refVal <- ref.get
        } yield assert(r)(isLeft(hasField("message", _.getMessage, equalTo("i'm dead")))) && assert(state)(
          equalTo(State())
        ) && assert(e.events)(isEmpty) && assert(e.snapshot)(isNone) && assert(refVal)(equalTo(""))
      } @@ withLiveRandom @@ nonFlaky,
      test("multiple persists within a transaction and fail should invalidate the events") {
        for {
          entity <- MyPersistentBehavior("1")
          _      <- entity
                      .send(Command.NextMessageDoubleAndFail("I'm virus", FailureMode.Fail))
                      .either
          state  <- entity.state
          e      <- getJournalAndSnapshot("1")
        } yield assert(state)(
          equalTo(State())
        ) && assert(e.events)(isEmpty) && assert(e.snapshot)(isNone)
      },
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
          response <- entity.ask[List[Long]](Command.Get)
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
                        .serviceWith[SnapshotStorage[State]](_.loadLast("1"))
                        .flatten
                        .flatMap(f => ZIO.fromOption(f).orElseFail(new Throwable))

          _ <- entity.send(Command.NextMessage("message")) // 4
          lastEvent <- ZIO.serviceWith[Journal[Event]](_.load("1", snapshot.offset).runCollect.map(_.head)).flatten
        } yield assert(snapshot)(equalTo(Offseted(4, State()))) && assert(lastEvent.offset)(equalTo(4))
      },
      test("calling same entity doesn't corrupt state") {

        def run(id: String, message: String) =
          MyPersistentBehavior(id).flatMap { entity =>
            ZIO.foreach(1 to 5) { x =>
              val msg = message + " " + x
              entity.send(Command.NextMessage(msg)) *> ZIO.sleep(50.millis)
            }
          }
        for {
          id     <- Random.nextString(10)
          f1     <- run(id, "Hello").repeatN(5).delay(0.millis).fork
          f2     <- run(id, "World").repeatN(5).delay(25.millis).fork
          _      <- TestClock.adjust(5.minutes)
          _      <- f1.join
          _      <- f2.join
          entity <- MyPersistentBehavior(id)
          state  <- entity.ask[List[String]](Command.Get)
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
              "Hello 4",
              "World 4",
              "Hello 5",
              "World 5",
              "Hello 1",
              "World 1",
              "Hello 2",
              "World 2",
              "Hello 3",
              "World 3",
              "Hello 4",
              "World 4",
              "Hello 5",
              "World 5",
              "Hello 1",
              "World 1",
              "Hello 2",
              "World 2",
              "Hello 3",
              "World 3",
              "Hello 4",
              "World 4",
              "Hello 5",
              "World 5",
              "Hello 1",
              "World 1",
              "Hello 2",
              "World 2",
              "Hello 3",
              "World 3",
              "Hello 4",
              "World 4",
              "Hello 5",
              "World 5",
              "Hello 1",
              "World 1",
              "Hello 2",
              "World 2",
              "Hello 3",
              "World 3",
              "Hello 4",
              "World 4",
              "Hello 5",
              "World 5",
              "Hello 1",
              "World 1",
              "Hello 2",
              "World 2",
              "Hello 3",
              "World 3",
              "Hello 4",
              "World 4",
              "Hello 5",
              "World 5"
            )
          )
        )
      } @@ withLiveRandom @@ nonFlaky
    ).provide(
      EntityManager
        .live[
          Any,
          Any,
          MyPersistentBehavior.Command,
          MyPersistentBehavior.Error,
          MyPersistentBehavior.Event,
          MyPersistentBehavior.State
        ],
      InMemoryJournal.live[MyPersistentBehavior.Event],
      InSnapshotStorage.live[MyPersistentBehavior.State],
      ZIOJSONSerde.live[MyPersistentBehavior.Event]
    )
}
