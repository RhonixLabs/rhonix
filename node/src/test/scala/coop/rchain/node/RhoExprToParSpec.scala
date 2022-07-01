package coop.rchain.node

import cats.syntax.all._
import coop.rchain.models.syntax._
import coop.rchain.node.api.WebApi._
import org.scalacheck.Arbitrary._
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class RhoExprToParSpec extends AnyPropSpec with ScalaCheckDrivenPropertyChecks with Matchers {
  property("WebAPI RhoExpr => Par => RhoExpr conversion work correct") {
    forAll { rhoExpr: RhoExpr =>
      rhoExprToRhoExpr(rhoExpr) shouldBe rhoExpr.some
    }
  }

  // Converts RhoExpr to Par and then back to RhoExpr
  private val rhoExprToRhoExpr = rhoExprToParProto _ andThen exprFromParProto

  implicit private def aArb: Arbitrary[RhoExpr] = {

    val numOfElements = 2 // StackOverflowError with n >= 3

    // def genExprPar   = withList(ExprPar)
    def genExprTuple = withList(ExprTuple)
    def genExprList  = withList(ExprList)
    def genExprSet   = Gen.listOfN(numOfElements, genRhoExpr).map(data => ExprSet(data.toSet))
    def genExprMap   = Gen.mapOfN(numOfElements, arbTuple2[String, RhoExpr].arbitrary).map(ExprMap)

    def genExprBool   = arbBool.arbitrary.map(b => ExprBool(b))
    def genExprInt    = arbLong.arbitrary.map(l => ExprInt(l))
    def genExprString = arbString.arbitrary.map(s => ExprString(s))
    def genExprUri    = arbString.arbitrary.map(uri => ExprUri(uri))
    def genExprBytes  = genHexString.map(ExprBytes)
    def genExprUnforg =
      Gen
        .oneOf(
          genHexString.map(UnforgPrivate),
          genHexString.map(UnforgDeploy),
          genHexString.map(UnforgDeployer)
        )
        .map(ExprUnforg)

    def genRhoExpr: Gen[RhoExpr] =
      Gen.lzy(
        Gen.oneOf(
          // genExprPar,
          genExprTuple,
          genExprList,
          genExprSet,
          genExprMap,
          genExprBool,
          genExprInt,
          genExprString,
          genExprUri,
          genExprBytes,
          genExprUnforg
        )
      )

    // Helpers
    def withList[A <: RhoExpr](f: List[RhoExpr] => A) =
      Gen.listOfN(numOfElements, genRhoExpr).map(f)
    def genHexString = Gen.listOf(arbByte.arbitrary).map(_.toArray.toHexString)

    Arbitrary(genRhoExpr)
  }
}
