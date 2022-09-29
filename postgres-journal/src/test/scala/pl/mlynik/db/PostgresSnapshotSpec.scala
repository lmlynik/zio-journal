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

object PostgresSnapshotSpec extends ZIOSpecDefault {

  case class SnapshotState(message: String)

  def spec =
    (suite("PostgresSnapshotSpec")(
      test("stores and retrieves last snapshot") {
        for {
          snapshotStorage <- ZIO.service[SnapshotStorage[SnapshotState]]
          _               <- snapshotStorage.store("1", Offseted(0, SnapshotState("hello")))
          _               <- snapshotStorage.store("1", Offseted(1, SnapshotState("world")))
          sp              <- snapshotStorage.loadLast("1")
        } yield assert(sp)(
          isSome(
            equalTo(
              Offseted(1, SnapshotState("world"))
            )
          )
        )
      },
      test("returns None on no snapshot") {
        for {
          snapshotStorage <- ZIO.service[SnapshotStorage[SnapshotState]]
          sp              <- snapshotStorage.loadLast("1")
        } yield assert(sp)(isNone)
      }
    ) @@ withLiveClock @@ TestAspect.before(DbContext.migrate())).provide(
      ZPostgreSQLContainer.Settings.default,
      ZPostgreSQLContainer.live,
      ZIOJSONByteArraySerde.live[SnapshotState],
      PostgresSnapshot.live[SnapshotState]
    )
}
