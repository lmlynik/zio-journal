package pl.mlynik.db

import io.getquill.*
import pl.mlynik.journal.Storage.Offseted
import pl.mlynik.journal.serde.Serde
import pl.mlynik.journal.{ Journal, Storage }
import zio.concurrent.ConcurrentMap
import zio.stream.{ ZPipeline, ZStream }
import zio.*

import java.sql.Timestamp
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

final class PostgresJournal[EVENT](
  serde: Serde[EVENT, Array[Byte]]
) extends Journal[Clock & DataSource, EVENT] {

  import DbContext.*

  given encodeUUID: MappedEncoding[Instant, Timestamp] = MappedEncoding[Instant, Timestamp](null)
  given decodeUUID: MappedEncoding[Timestamp, Instant] = MappedEncoding[Timestamp, Instant](null)

  case class SqlError(io: SQLException) extends Storage.PersistError with Storage.LoadError
  override def persist(id: String, offset: Long, event: EVENT): ZIO[Clock & DataSource, Storage.PersistError, Unit] = {
    val i = for {
      instant <- ZIO.serviceWithZIO[Clock](_.instant)
      payload <- serde.serialize(event)
      insert   = quote {
                   query[JournalRow].insertValue(lift(JournalRow(id, offset, instant.toEpochMilli, payload)))
                 }
      _       <- run(insert)
    } yield ()

    i.mapError(sqlError => SqlError(sqlError))
  }

  private val deserialize = ZPipeline.mapZIO[Any, Nothing, JournalRow, Offseted[EVENT]] { r =>
    serde.deserialize(r.payload).map { event =>
      Offseted(r.sequenceNumber, event)
    }
  }

  override def load(id: String, loadFrom: Long): ZStream[Clock & DataSource, Storage.LoadError, Offseted[EVENT]] = {
    val load: StreamResult[JournalRow] = stream(
      query[JournalRow]
        .filter(row => row.persistenceId == lift(id) && row.sequenceNumber >= lift(loadFrom))
        .sortBy(_.sequenceNumber)(Ord.desc)
    )

    load.mapError(sqlError => SqlError(sqlError)).via(deserialize)
  }
}

object PostgresJournal {
  def live[EVENT: Tag]: ZLayer[Serde[EVENT, Array[Byte]], Nothing, Journal[Clock & DataSource, EVENT]] =
    ZLayer.fromZIO {
      for {
        serde <- ZIO.service[Serde[EVENT, Array[Byte]]]
      } yield new PostgresJournal(serde)
    }
}
