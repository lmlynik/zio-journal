package pl.mlynik.journal.serde

import zio._

trait Serde[DOMAIN, PAYLOAD] {

  def id: String
  def serialize(domain: DOMAIN): UIO[PAYLOAD]

  def deserialize(payload: PAYLOAD): UIO[DOMAIN]
}

object ZIOJSONSerde {

  import zio.json._

  private class Impl[DOMAIN: Tag](using JsonCodec[DOMAIN]) extends Serde[DOMAIN, String] {

    override def id: String                             = "zio-json"
    override def serialize(domain: DOMAIN): UIO[String] = ZIO.attempt(domain.toJson).orDie

    override def deserialize(payload: String): UIO[DOMAIN] =
      ZIO.fromEither(payload.fromJson[DOMAIN]).mapError(r => new Error(r)).orDie
  }

  // TODO work on better derivation, macro?
  def live[DOMAIN: Tag](using JsonCodec[DOMAIN]): ULayer[Serde[DOMAIN, String]] =
    ZLayer.succeed(new Impl[DOMAIN])
}

object ZIOJSONByteArraySerde {

  import zio.json._

  private class Impl[DOMAIN: Tag](using JsonCodec[DOMAIN]) extends Serde[DOMAIN, Array[Byte]] {

    override def id: String                             = "zio-json-byte-array"
    override def serialize(domain: DOMAIN): UIO[Array[Byte]] = ZIO.attempt(domain.toJson.getBytes("UTF-8")).orDie

    override def deserialize(payload: Array[Byte]): UIO[DOMAIN] =
      ZIO.fromEither(new String(payload, "UTF-8").fromJson[DOMAIN]).mapError(r => new Error(r)).orDie
  }

  // TODO work on better derivation, macro?
  def live[DOMAIN: Tag](using JsonCodec[DOMAIN]): ULayer[Serde[DOMAIN, Array[Byte]]] =
    ZLayer.succeed(new Impl[DOMAIN])
}

object NoopSerde {

  def live[DOMAIN: Tag]: ULayer[Serde[DOMAIN, DOMAIN]] = ZLayer.succeed {
    new Serde[DOMAIN, DOMAIN]:

      override def id: String = "noop"

      override def serialize(domain: DOMAIN): UIO[DOMAIN] = ZIO.succeed(domain)

      override def deserialize(payload: DOMAIN): UIO[DOMAIN] = ZIO.succeed(payload)
  }
}
