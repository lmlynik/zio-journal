package pl.mlynik.db

import io.getquill.*
import pl.mlynik.journal.Storage.*
import pl.mlynik.journal.serde.Serde
import pl.mlynik.journal.{ Journal, SnapshotStorage, Storage }
import zio.concurrent.ConcurrentMap
import zio.stream.{ ZPipeline, ZStream }
import zio.*

import java.sql.Timestamp
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

final class PostgresSnapshot[STATE](
  serde: Serde[STATE, Array[Byte]],
  dataSource: DataSource
) extends SnapshotStorage[STATE] {

  import DbContext.*

  case class SqlError(io: SQLException) extends PersistError with LoadError

  def store(id: String, state: Offseted[STATE]): ZIO[Any, PersistError, Unit] = {
    val i = for {
      instant <- Clock.instant
      payload <- serde.serialize(state.value)
      insert   = quote {
                   query[Snapshot].insertValue(lift(Snapshot(id, state.offset, instant.toEpochMilli, payload)))
                 }
      _       <- run(insert)
    } yield ()

    i.mapError(sqlError => SqlError(sqlError)).provideLayer(ZLayer.succeed(dataSource))
  }

  def loadLast(id: String): ZIO[Any, LoadError, Option[Offseted[STATE]]] = {
    val r = quote {
      query[Snapshot].filter(_.persistenceId == lift(id)).sortBy(_.sequenceNumber)(Ord.desc).take(1)
    }

    def deserialize(snapshot: Option[Snapshot]): ZIO[Any, Nothing, Option[Offseted[STATE]]] =
      snapshot match
        case Some(value) =>
          serde.deserialize(value.payload).map { payload =>
            Some(Offseted(value.sequenceNumber, payload))
          }
        case None        =>
          ZIO.none

    run(r)
      .mapBoth(
        sqlError => SqlError(sqlError),
        snapshots => snapshots.headOption
      )
      .flatMap(deserialize)
      .provideLayer(ZLayer.succeed(dataSource))
  }
}

object PostgresSnapshot {
  def live[STATE: Tag]: ZLayer[Serde[STATE, Array[Byte]] & DataSource, Nothing, SnapshotStorage[STATE]] =
    ZLayer.fromZIO {
      for {
        datasource <- ZIO.service[DataSource]
        serde      <- ZIO.service[Serde[STATE, Array[Byte]]]
      } yield new PostgresSnapshot(serde, datasource)
    }
}
