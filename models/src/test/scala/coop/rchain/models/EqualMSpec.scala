package coop.rchain.models

import com.google.protobuf.ByteString
import coop.rchain.models.Expr.ExprInstance.GInt
import coop.rchain.models.testImplicits._
import coop.rchain.models.testUtils.TestUtils.forAllSimilarA
import cats.Eval
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalacheck.{Arbitrary, Shrink}
import org.scalatest.Assertion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import coop.rchain.catscontrib.effect.implicits.sEval

import scala.Function.tupled
import scala.annotation.nowarn
import scala.collection.immutable.BitSet
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

class EqualMSpec extends AnyFlatSpec with ScalaCheckPropertyChecks with Matchers {

  implicit override val generatorDrivenConfig =
    PropertyCheckConfiguration(sizeRange = 25, minSuccessful = 100)

  behavior of "EqualM"

  sameResultAsReference[Par]
  sameResultAsReference[Expr]
  sameResultAsReference[BindPattern]
  sameResultAsReference[Bundle]
  sameResultAsReference[Connective]
  sameResultAsReference[ConnectiveBody]
  sameResultAsReference[EList]
  sameResultAsReference[EMap]
  sameResultAsReference[EMatches]
  sameResultAsReference[EMethod]
  sameResultAsReference[ENeq]
  sameResultAsReference[ENot]
  sameResultAsReference[EOr]
  sameResultAsReference[ESet]
  sameResultAsReference[ETuple]
  sameResultAsReference[EVar]
  sameResultAsReference[GPrivate]
  sameResultAsReference[KeyValuePair]
  sameResultAsReference[ListBindPatterns]
  sameResultAsReference[Match]
  sameResultAsReference[MatchCase]
  sameResultAsReference[New]
  sameResultAsReference[ParWithRandom]
  sameResultAsReference[PCost]
  sameResultAsReference[Receive]
  sameResultAsReference[ReceiveBind]
  sameResultAsReference[Send]
  sameResultAsReference[TaggedContinuation]
  sameResultAsReference[Var]
  sameResultAsReference[VarRef]
  sameResultAsReference[ParSet]
  sameResultAsReference[ParMap]

  sameResultAsReference[Int]
  sameResultAsReference[BigInt]
  sameResultAsReference[Long]
  sameResultAsReference[String]
  sameResultAsReference[ByteString]
  sameResultAsReference[BitSet]
  sameResultAsReference[AlwaysEqual[BitSet]]

  sameResultAsReference[SortedParHashSet]
  sameResultAsReference[SortedParMap]

  //fixed regressions / corner cases:
  sameResultAsReference(GInt(-1), GInt(-1))
  sameResultAsReference(Expr(GInt(-1)), Expr(GInt(-1)))

  def sameResultAsReference[A <: Any: EqualM: Arbitrary: Shrink: Pretty](
      implicit tag: ClassTag[A]
  ): Unit =
    it must s"provide same results as equals for ${tag.runtimeClass.getSimpleName}" in {
      forAllSimilarA[A]((x, y) => sameResultAsReference(x, y))
    }

  // this @nowarn is to disable warning which are fatal according to the project configuration
  // 'non-variable type argument Any in type pattern coop.rchain.models.AlwaysEqual[Any] is unchecked since it
  // is eliminated by erasure [error] case (_: AlwaysEqual[Any], _: AlwaysEqual[Any]) => self.equals(other)'
  @nowarn
  private def sameResultAsReference[A <: Any: EqualM: Pretty](x: A, y: A): Assertion = {
    // We are going to override the generated hashCode for our generated AST classes,
    // so in this test we rely on the underlying implementation from ScalaRuntime,
    // and hard-code the current definition for the handmade AST classes.
    def reference(self: Any, other: Any): Boolean = (self, other) match {
      case (left: ParSet, right: ParSet) =>
        val eqF = (x: ParSet) => (x.ps, x.remainder, x.connectiveUsed)
        Equiv.universal.equiv(eqF(left), eqF(right))
      case (left: ParMap, right: ParMap) =>
        val eqF = (x: ParMap) => (x.ps, x.remainder, x.connectiveUsed)
        Equiv.universal.equiv(eqF(left), eqF(right))
      // this case is introduced after migration to scala 2.13 since AlwaysEqual falls under Product case for some reason
      case (_: AlwaysEqual[Any], _: AlwaysEqual[Any]) => self.equals(other)
      case (left: Product, right: Product) =>
        left.getClass.isInstance(other) &&
          left.productIterator
            .zip(right.productIterator)
            .forall(tupled(reference))
      case _ => self == other
    }

    val referenceResult = reference(x, y)
    val equalsResult    = x == y
    val equalMResult    = EqualM[A].equal[IO](x, y).timeout(20.seconds).unsafeRunSync()

    withClue(
      s"""
         |
         |Inconsistent results:
         |
         |     reference(x, y): $referenceResult
         |              x == y: $equalsResult
         |  EqualM.equal(x, y): $equalMResult
         |
         |Test data used:
         |
         |${Pretty.pretty(x)}
         |
         |and
         |
         |${Pretty.pretty(y)}
         |
         |""".stripMargin
    ) {
      // With this check we know that:
      //   EqualM.equal[Id] == _.equals == reference
      // This makes this test valid both before and after we override the generated hashCode
      // (which is long done when you're reading this).
      referenceResult should be(equalsResult)
      equalMResult should be(referenceResult)
    }
  }

}
