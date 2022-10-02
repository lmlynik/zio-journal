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
import pl.mlynik.journal.serde.Serde
import MyPersistentBehavior.*
import pl.mlynik.types.ENV

abstract class InMemoryEvenSourceEntitySpec extends ZIOSpec[ENV[Command, Error, Event, State]] {
  import MyPersistentBehavior.*

  val storageLayers: ZLayer[Any, Nothing, Serde[Event, String] & Journal[Event] & SnapshotStorage[State]] =
    ZIOJSONSerde.live[MyPersistentBehavior.Event] >+>
      InMemoryJournal.live[MyPersistentBehavior.Event] >+>
      InSnapshotStorage.live[MyPersistentBehavior.State]

  override def bootstrap: ZLayer[Any, Nothing, ENV[Command, Error, Event, State]] =
    EntityManager
      .live[
        Any,
        Any,
        MyPersistentBehavior.Command,
        MyPersistentBehavior.Error,
        MyPersistentBehavior.Event,
        MyPersistentBehavior.State
      ] >+>
      storageLayers

  protected final case class EventsAndSnapshot(events: Chunk[Offseted[Event]], snapshot: Option[Offseted[State]])

  protected def getJournalAndSnapshot(
    id: String
  ): ZIO[Journal[Event] & SnapshotStorage[State], LoadError, EventsAndSnapshot] =
    for {
      events   <- ZIO.serviceWithZIO[Journal[MyPersistentBehavior.Event]](_.load(id, 0).runCollect)
      snapshot <- ZIO.serviceWithZIO[SnapshotStorage[State]](_.loadLast(id))
    } yield EventsAndSnapshot(events, snapshot)

}

object types {

  type ENV[COMMAND, ERROR, EVENT, STATE] = EntityManager[
    Any,
    Any,
    COMMAND,
    ERROR,
    EVENT,
    STATE
  ] & Journal[EVENT] & SnapshotStorage[STATE] & Serde[EVENT, String]
}
