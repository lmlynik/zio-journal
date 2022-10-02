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

object EventSourcedEntityStashSpec extends InMemoryEvenSourceEntitySpec {
  import MyPersistentBehavior.*

  def spec =
    suite("EventSourcedEntityStashSpec")(
      test("Accepts commands which update states") {
        for {
          entity   <- MyPersistentBehavior("1")
          _        <- entity.send(Command.StartStashing)
          _        <- entity.send(Command.Stash("message")).repeatN(4)
          stateA   <- entity.state
          _        <- entity.ask[State](Command.Unstash)
          stateB   <- entity.state
        } yield assert(stateA.messages)(equalTo(Nil))
          && assert(stateA.stashing)(isTrue)
          && assert(stateB.messages.head)(equalTo("message"))
          && assert(stateB.messages)(hasSize(equalTo(5)))
          && assert(stateB.stashing)(isFalse)
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
      ZIOJSONSerde.live[MyPersistentBehavior.Event]
    )
}
