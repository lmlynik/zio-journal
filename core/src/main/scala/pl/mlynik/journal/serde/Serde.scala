package pl.mlynik.journal.serde

import zio._

trait Serde[DOMAIN, PAYLOAD] {
  def serialize(domain: DOMAIN): UIO[PAYLOAD]

  def deserialize(payload: PAYLOAD): UIO[DOMAIN]
}

object ZIOJSONSerde {

  import zio.json._

  private class Impl[DOMAIN: Tag](using JsonCodec[DOMAIN]) extends Serde[DOMAIN, String] {

    override def serialize(domain: DOMAIN): UIO[String] = ZIO.attempt(domain.toJson).orDie

    override def deserialize(payload: String): UIO[DOMAIN] =
      ZIO.fromEither(payload.fromJson[DOMAIN]).mapError(r => new Error(r)).orDie
  }

  // TODO work on better derivation, macro?
  def live[DOMAIN: Tag](using JsonCodec[DOMAIN]): ULayer[Serde[DOMAIN, String]] =
    ZLayer.succeed(new Impl[DOMAIN])
}

object NoopSerde {
  def live[DOMAIN: Tag]: ULayer[Serde[DOMAIN, DOMAIN]] = ZLayer.succeed {
    new Serde[DOMAIN, DOMAIN]:
      override def serialize(domain: DOMAIN): UIO[DOMAIN] = ZIO.succeed(domain)

      override def deserialize(payload: DOMAIN): UIO[DOMAIN] = ZIO.succeed(payload)
  }
}
