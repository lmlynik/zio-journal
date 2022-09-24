package pl.mlynik.db

import io.getquill.jdbczio.Quill
import io.getquill.{ PostgresZioJdbcContext, SnakeCase }
import org.flywaydb.core.Flyway
import zio.{ RIO, ZIO, ZLayer }

import javax.sql.DataSource

object DbContext extends PostgresZioJdbcContext(SnakeCase) {

  def migrate(): RIO[DataSource, Unit] = for {
    ds        <- ZIO.service[DataSource]
    migration <- ZIO.attempt {
                   Flyway.configure().dataSource(ds).locations("db").outOfOrder(true).load()
                 }
    _         <- ZIO.attempt(migration.repair())
    _         <- ZIO.attempt(migration.migrate())
  } yield ()

}
