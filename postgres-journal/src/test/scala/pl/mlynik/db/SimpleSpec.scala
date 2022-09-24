package pl.mlynik.db

import zio.*
import zio.test.*
import zio.test.TestAspect.*
import zio.test.{ test, * }
import zio.test.Assertion.*

object SimpleSpec extends ZIOSpecDefault {
  
  def spec =
    suite("simple spec")(
      test("test") {
        def foo: ZIO[Clock, Nothing, Int] = ZIO.succeed(42)

        for {
          n <- foo
        } yield assert(n)(equalTo(42))
      }
    ) @@ withLiveClock
}