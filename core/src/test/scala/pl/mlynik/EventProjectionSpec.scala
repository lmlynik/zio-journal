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

object EventProjectionSpec extends InMemoryEventSourceEntitySpec {
  import MyPersistentBehavior.*

  def spec =
    suite("EventProjectionSpec")(
      test("Accepts commands which update states") {
        for {
          entity      <- MyPersistentBehavior("1")
          messages    <- ZIO.foreach(1 to 10000)(_ => Random.nextString(20).map(Command.NextMessage.apply _))
          _           <- ZIO.foreach(messages)(entity.send)
          eventStream <- EventProjection
                           .events[Event]("1", "projectionId1")
                           .take(10000)
                           .collect { case Offseted(_, Event.NextMessageAdded(msg)) => msg }
                           .runCollect

        } yield assert(eventStream.size)(equalTo(10000))
          && assert(eventStream)(equalTo(messages.collect { case Command.NextMessage(msg) => msg }))
      }
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
      ZIOJSONSerde.live[MyPersistentBehavior.Event],
      InMemoryProjectionOffsetStorage.live
    )
}
