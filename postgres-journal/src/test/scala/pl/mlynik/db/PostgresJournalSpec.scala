package pl.mlynik.db

import zio.*
import zio.test.*
import zio.test.TestAspect.*
import zio.test.{ test, * }
import zio.test.Assertion.*
import pl.mlynik.journal.*
import pl.mlynik.journal.Storage.Offseted
import pl.mlynik.journal.serde.{ ZIOJSONByteArraySerde, ZIOJSONSerde }
import zio.json.*

import javax.sql.DataSource

import io.github.scottweaver.zio.testcontainers.postgres.ZPostgreSQLContainer

object PostgresJournalSpec extends ZIOSpecDefault {

  enum Event {
    case NextMessageAdded(value: Long)
    case Cleared
    case Complex(event: List[Event])
  }

  def spec =
    (suite("PostgresJournalSpec")(
      test("persists nad loads events in correct order") {
        for {
          journal <- ZIO.service[Journal[DataSource, Event]]
          _       <- journal.persist("1", 0, Event.NextMessageAdded(13))
          _       <- journal.persist("1", 1, Event.NextMessageAdded(14))
          _       <- journal.persist("2", 0, Event.Complex(Event.Complex(Event.NextMessageAdded(2137) :: Nil) :: Nil))
          events1 <- journal.load("1", 0).runCollect
          events2 <- journal.load("2", 0).runCollect
        } yield assert(events1)(
          equalTo(
            Chunk(
              Offseted(
                offset = 0,
                value = Event.NextMessageAdded(value = 13)
              ),
              Offseted(
                offset = 1,
                value = Event.NextMessageAdded(value = 14)
              )
            )
          )
        ) && assert(events2)(
          equalTo(
            Chunk(
              Offseted(offset = 0, value = Event.Complex(Event.Complex(Event.NextMessageAdded(2137) :: Nil) :: Nil))
            )
          )
        )
      },
      test("loads events from the specified offset") {
        for {
          journal <- ZIO.service[Journal[DataSource, Event]]
          _       <- journal.persist("1", 0, Event.NextMessageAdded(13))
          _       <- journal.persist("1", 1, Event.NextMessageAdded(14))
          events1 <- journal.load("1", 1).runCollect
        } yield assert(events1)(
          equalTo(
            Chunk(
              Offseted(
                offset = 1,
                value = Event.NextMessageAdded(value = 14)
              )
            )
          )
        )
      },
      test("loads events from the specified offset - empty") {
        for {
          journal <- ZIO.service[Journal[DataSource, Event]]
          _       <- journal.persist("1", 0, Event.NextMessageAdded(13))
          _       <- journal.persist("1", 1, Event.NextMessageAdded(14))
          events1 <- journal.load("1", 2).runCollect
        } yield assert(events1)(equalTo(Chunk.empty))
      }
    ) @@ withLiveClock @@ TestAspect.before(DbContext.migrate())).provide(
      ZPostgreSQLContainer.Settings.default,
      ZPostgreSQLContainer.live,
      ZIOJSONByteArraySerde.live[Event],
      PostgresJournal.live[Event]
    )
}
