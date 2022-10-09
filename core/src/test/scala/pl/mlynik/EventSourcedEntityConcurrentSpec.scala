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

object EventSourcedEntityConcurrentSpec extends InMemoryEventSourceEntitySpec {
  import MyPersistentBehavior.*

  def spec =
    suite("EventSourcedEntityConcurrentSpec")(
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
      } @@ withLiveRandom @@ nonFlaky(25)
    )
}
