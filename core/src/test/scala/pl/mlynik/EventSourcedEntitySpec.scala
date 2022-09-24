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

  given JsonCodec[MyPersistentBehavior.Event] = DeriveJsonCodec.gen[MyPersistentBehavior.Event]
  def spec                                    =
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
      test("Accepts asks which returns errors") {
        for {
          entity   <- MyPersistentBehavior("1")
          _        <- entity.send(Command.NextNumber(13))
          response <- entity.ask[FailResponse.type, List[Long]](Command.Fail).either
        } yield assert(response)(isLeft(equalTo(FailResponse)))
      },
      test("Accepts and handles commands in correct order") {
        def run(id: String) = for {
          entity   <- MyPersistentBehavior(id)
          ns       <- ZIO.foreach(1 to 1000)(_ => Random.nextInt)
          _        <- ZIO.foreach(ns)(n => entity.send(Command.NextNumber(n)))
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
          _ <- entity.send(Command.NextNumber(13)) // 0
          _ <- entity.send(Command.NextNumber(13)) // 1
          _ <- entity.send(Command.NextNumber(13)) // 2
          _ <- entity.send(Command.Clear)          // 3
          snapshot <- ZIO
                        .serviceWith[SnapshotStorage[State]](_.loadLast("1"))
                        .flatten
                        .flatMap(f => ZIO.fromOption(f).orElseFail(new Error))

          _ <- entity.send(Command.NextNumber(13)) // 4
          lastEvent <- ZIO.serviceWith[Journal[Event]](_.load("1", snapshot.offset).runCollect.map(_.head)).flatten
        } yield assert(snapshot)(equalTo(Offseted(4, State()))) && assert(lastEvent.offset)(equalTo(4))
      },
      test("calling same entity doesn't corrupt state") {
        def run(i: Int) =
          MyPersistentBehavior("concurrenct1").flatMap { entity =>
            ZIO.foreach(1 to 5) { _ =>
              entity.send(Command.NextNumber(i)) *> ZIO.sleep(50.milliseconds)
            }
          } *> ZIO.log("Finished adding numbers on fiber")
        for {
          fibers <- ZIO.foreach(1 to 5) { millis =>
                      val d = (millis * 100).milliseconds
                      run(millis).delay(d).fork
                    }
          _      <- ZIO.foreach(fibers)(_.join)
          entity <- MyPersistentBehavior("concurrenct1")
          state  <- entity.ask[Nothing, List[Long]](Command.Get)
        } yield assert(state)(equalTo(List(1, 1, 2, 1, 2, 1, 3, 2, 1, 3, 2, 4, 3, 2, 4, 3, 5, 4, 3, 5, 4, 5, 4, 5, 5)))
      } @@ withLiveClock
    ).provide(
      EntityManager.live[MyPersistentBehavior.Command, MyPersistentBehavior.Event, MyPersistentBehavior.State],
      InMemoryJournal.live[MyPersistentBehavior.Event],
      InSnapshotStorage.live[MyPersistentBehavior.State],
      ZIOJSONSerde.live[MyPersistentBehavior.Event]
    )
}
