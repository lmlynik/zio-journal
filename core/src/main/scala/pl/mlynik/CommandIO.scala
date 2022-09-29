package pl.mlynik
import zio.*

enum Effect[+EVENT] {
  case Persist(event: EVENT)
  case Snapshot
  case None
  case Reply[E, A](result: IO[E, A]) extends Effect[Nothing]
  case Complex(effects: List[Effect[EVENT]])
}

import scala.annotation.targetName

object Effect {

  type EffectIO[R, Err, EVENT] = ZIO[R, Err, Effect[EVENT]]
  def persistZIO[EVENT](event: EVENT): UIO[Effect[EVENT]] = ZIO.succeed(Persist(event))

  def snapshotZIO[EVENT]: UIO[Effect[EVENT]] = ZIO.succeed(Snapshot)

  def none[EVENT]: UIO[Effect[EVENT]] = ZIO.succeed(None)

  def complexZIO[R, Err, EVENT](effects: EffectIO[R, Err, EVENT]*): EffectIO[R, Err, EVENT] =
    ZIO.collectAll(effects).map(f => Complex(f.toList))

  def reply[EVENT, A](result: A): UIO[Effect[EVENT]] =
    ZIO.succeed(Reply(ZIO.succeed(result)))

  def reply[EVENT, E, A](result: IO[E, A]): UIO[Effect[EVENT]] =
    ZIO.succeed(Reply(result))

  def fail[EVENT, E, A](result: IO[E, A]): UIO[Effect[EVENT]] =
    ZIO.succeed(Reply(result))

  extension [R, Err, EVENT](ef1: EffectIO[R, Err, EVENT]) {
    @targetName("andThen")
    def >>>(ef2: EffectIO[R, Err, EVENT]) = complexZIO(ef1, ef2)
  }
}
