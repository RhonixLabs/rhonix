package coop.rchain.casper.util.scalatest

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

trait Fs2StreamMatchers {

  /**
    * Checks if Stream will not produce any more elements == stream is empty
    *
    * @param timeout duration to wait for new elements
    */
  class EmptyMatcher[A](timeout: FiniteDuration) extends Matcher[Stream[IO, A]] {

    def apply(left: Stream[IO, A]) = {
      val res = left.take(1).timeout(timeout).compile.toList.attempt.unsafeRunSync()

      val isEmpty = res.isLeft && res.swap.toOption.get.isInstanceOf[TimeoutException]

      val onFail    = if (!isEmpty) s"Stream is not empty, emitted: ${res.toOption.get}" else ""
      val onSuccess = s"Stream is empty"

      MatchResult(isEmpty, onFail, onSuccess)
    }
  }

  def notEmit = new EmptyMatcher[Any](250.millis)

  def notEmit(timeout: FiniteDuration) = new EmptyMatcher[Any](timeout)
}
