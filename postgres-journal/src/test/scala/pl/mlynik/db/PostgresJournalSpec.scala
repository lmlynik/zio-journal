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
    case NextNumberAdded(value: Long)
    case Cleared
    case Complex(event: List[Event])
  }

  given JsonCodec[Event] = DeriveJsonCodec.gen[Event]

  def spec =
    (suite("PostgresJournalSpec")(
      test("persists events") {
        for {
          journal <- ZIO.service[Journal[Clock & DataSource, Event]]
          _       <- journal.persist("1", 0, Event.NextNumberAdded(13))
        } yield assert(List(13))(equalTo(List(13)))
      },
    ) @@ withLiveClock @@ TestAspect.before(DbContext.migrate())).provide(
      ZPostgreSQLContainer.Settings.default,
      ZPostgreSQLContainer.live,
      ZIOJSONByteArraySerde.live[Event],
      PostgresJournal.live[Event]
    )
}
