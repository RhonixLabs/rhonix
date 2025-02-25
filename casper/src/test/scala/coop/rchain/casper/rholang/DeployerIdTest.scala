package coop.rchain.casper.rholang

import cats.effect.{IO, Resource}
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.casper.genesis.Genesis
import coop.rchain.casper.helper.TestNode
import coop.rchain.casper.rholang.Resources._
import coop.rchain.casper.util.GenesisBuilder.buildGenesis
import coop.rchain.casper.util.{ConstructDeploy, GenesisBuilder}
import coop.rchain.crypto.PrivateKey
import coop.rchain.crypto.signatures.Secp256k1
import coop.rchain.casper.syntax._
import coop.rchain.models.Expr.ExprInstance.GBool
import coop.rchain.models.rholang.implicits._
import coop.rchain.models.{GDeployerId, Par}
import coop.rchain.p2p.EffectsTestInstances.LogicalTime
import coop.rchain.shared.scalatestcontrib.effectTest
import coop.rchain.shared.{Base16, Log}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DeployerIdTest extends AnyFlatSpec with Matchers {
  implicit val time              = new LogicalTime[IO]
  implicit val log: Log[IO]      = new Log.NOPLog[IO]()
  private val dummyMergeableName = BlockRandomSeed.nonNegativeMergeableTagName("dummy")

  val runtimeManager: Resource[IO, RuntimeManager[IO]] =
    mkRuntimeManager[IO]("deployer-id-runtime-manager-test", dummyMergeableName)

  "Deployer id" should "be equal to the deployer's public key" in effectTest {
    val sk = PrivateKey(
      Base16.unsafeDecode("b18e1d0045995ec3d010c387ccfeb984d783af8fbb0f40fa7db126d889f6dadd")
    )
    val pk = ByteString.copyFrom(Secp256k1.toPublic(sk).bytes)
    runtimeManager.use { mgr =>
      for {
        deploy <- ConstructDeploy.sourceDeployNowF[IO](
                   s"""new return, auth(`rho:rchain:deployerId`) in { return!(*auth) }""",
                   sec = sk
                 )
        emptyStateHash = RuntimeManager.emptyStateHashFixed
        result         <- mgr.spawnRuntime >>= { _.captureResults(emptyStateHash, deploy) }
        _              = result.size should be(1)
        _              = result.head should be(GDeployerId(pk): Par)
      } yield ()
    }
  }

  val genesisContext = buildGenesis(GenesisBuilder.buildGenesisParametersSize(4))

  it should "make drain vault attacks impossible" in effectTest {
    val deployer = ConstructDeploy.defaultSec
    val attacker = ConstructDeploy.defaultSec2

    checkAccessGranted(deployer, deployer, isAccessGranted = true) >>
      checkAccessGranted(deployer, attacker, isAccessGranted = false)
  }

  def checkAccessGranted(
      deployer: PrivateKey,
      contractUser: PrivateKey,
      isAccessGranted: Boolean
  ): IO[Unit] = {
    val checkDeployerDefinition =
      s"""
         |contract @"checkAuth"(input, ret) = {
         |  new auth(`rho:rchain:deployerId`) in {
         |    ret!(*input == *auth)
         |  }
         |}""".stripMargin
    val checkDeployerCall =
      s"""
         |new return, auth(`rho:rchain:deployerId`), ret in {
         |  @"checkAuth"!(*auth, *ret) |
         |  for(isAuthenticated <- ret) {
         |    return!(*isAuthenticated)
         |  }
         |} """.stripMargin

    TestNode.standaloneEff(genesisContext).use { node =>
      for {
        contract <- ConstructDeploy.sourceDeployNowF[IO](
                     checkDeployerDefinition,
                     sec = deployer,
                     shardId = genesisContext.genesisBlock.shardId
                   )
        block     <- node.addBlock(contract)
        stateHash = block.postStateHash
        checkAuthDeploy <- ConstructDeploy
                            .sourceDeployNowF[IO](checkDeployerCall, sec = contractUser)
        result <- node.runtimeManager.spawnRuntime >>= {
                   _.captureResults(stateHash, checkAuthDeploy)
                 }
        _ = assert(result.size == 1)
        _ = assert(result.head == (GBool(isAccessGranted): Par))
      } yield ()
    }
  }

}
