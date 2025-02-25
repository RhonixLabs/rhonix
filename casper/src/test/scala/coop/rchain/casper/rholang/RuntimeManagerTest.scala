package coop.rchain.casper.rholang

import cats.data.EitherT
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource, Sync}
import cats.syntax.all._
import cats.{Applicative, Functor}
import com.google.protobuf.ByteString
import coop.rchain.casper.protocol.ProcessedSystemDeploy.Failed
import coop.rchain.casper.protocol.{
  BlockMessage,
  DeployData,
  ProcessedDeploy,
  ProcessedSystemDeploy
}
import coop.rchain.casper.rholang.sysdeploys._
import coop.rchain.casper.rholang.types._
import coop.rchain.casper.syntax._
import coop.rchain.casper.util.{ConstructDeploy, GenesisBuilder}
import coop.rchain.catscontrib.Catscontrib._
import coop.rchain.crypto.hash.Blake2b512Random
import coop.rchain.crypto.signatures.Signed
import coop.rchain.metrics
import coop.rchain.metrics.{Metrics, NoopSpan, Span}
import coop.rchain.models.PCost
import coop.rchain.models.block.StateHash.StateHash
import coop.rchain.models.syntax._
import coop.rchain.rholang.interpreter.SystemProcesses.BlockData
import coop.rchain.rholang.interpreter.accounting.Cost
import coop.rchain.rholang.interpreter.compiler.Compiler
import coop.rchain.rholang.interpreter.errors.BugFoundError
import coop.rchain.rholang.interpreter.{accounting, ParBuilderUtil, ReplayRhoRuntime}
import coop.rchain.shared.scalatestcontrib.effectTest
import coop.rchain.shared.{Base16, Log}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
sealed trait SystemDeployReplayResult[A]

final case class ReplaySucceeded[A](stateHash: StateHash, result: A)
    extends SystemDeployReplayResult[A]

final case class ReplayFailed[A](systemDeployError: SystemDeployUserError)
    extends SystemDeployReplayResult[A]

object SystemDeployReplayResult {
  def replaySucceeded[A](stateHash: StateHash, result: A): SystemDeployReplayResult[A] =
    ReplaySucceeded(stateHash, result)
  def replayFailed[A](systemDeployError: SystemDeployUserError): SystemDeployReplayResult[A] =
    ReplayFailed(systemDeployError)
}

class RuntimeManagerTest extends AnyFlatSpec with Matchers with EitherValues {
  implicit val log: Log[IO]            = Log.log[IO]
  implicit val metricsEff: Metrics[IO] = new metrics.Metrics.MetricsNOP[IO]
  implicit val noopSpan: Span[IO]      = NoopSpan[IO]()

  val genesisContext: GenesisBuilder.GenesisContext = GenesisBuilder.buildGenesis()
  val genesis: BlockMessage                         = genesisContext.genesisBlock
  val genesisBlockNum: Long                         = genesis.blockNumber
  val genesisSeqNum: Long                           = genesis.seqNum

  val runtimeManagerResource: Resource[IO, RuntimeManager[IO]] =
    Resources
      .copyStorage[IO](genesisContext.storageDirectory)
      .evalMap(Resources.mkTestRNodeStoreManager[IO])
      .evalMap(
        Resources
          .mkRuntimeManagerAt[IO](_, BlockRandomSeed.nonNegativeMergeableTagName(genesis.shardId))
      )

  private def computeState[F[_]: Functor](
      runtimeManager: RuntimeManager[F],
      deploy: Signed[DeployData],
      stateHash: StateHash
  ): F[(StateHash, ProcessedDeploy)] = {
    val rand = BlockRandomSeed.randomGenerator(
      genesis.shardId,
      genesisBlockNum,
      genesisContext.validatorPks.head,
      stateHash.toBlake2b256Hash
    )
    for {
      res <- runtimeManager.computeState(stateHash)(
              deploy :: Nil,
              Nil,
              rand,
              BlockData(
                genesisBlockNum,
                genesisContext.validatorPks.head,
                genesisSeqNum
              )
            )
      (hash, Seq(result), _) = res
    } yield (hash, result)
  }

  private def replayComputeState[F[_]: Applicative](runtimeManager: RuntimeManager[F])(
      stateHash: StateHash,
      processedDeploy: ProcessedDeploy
  ): F[Either[ReplayFailure, StateHash]] = {
    val rand = BlockRandomSeed.randomGenerator(
      genesis.shardId,
      genesisBlockNum,
      genesisContext.validatorPks.head,
      stateHash.toBlake2b256Hash
    )
    runtimeManager.replayComputeState(stateHash)(
      processedDeploy :: Nil,
      Nil,
      rand,
      BlockData(
        genesisBlockNum,
        genesisContext.validatorPks.head,
        genesisSeqNum
      ),
      withCostAccounting = true
    )
  }

  "computeState" should "charge for deploys" in effectTest {
    runtimeManagerResource.use { runtimeManager =>
      val genPostState = genesis.postStateHash
      val source       = """
                            # new rl(`rho:registry:lookup`), listOpsCh in {
                            #   rl!(`rho:lang:listOps`, *listOpsCh) |
                            #   for(x <- listOpsCh){
                            #     Nil
                            #   }
                            # }
                            #""".stripMargin('#')
      ConstructDeploy.sourceDeployNowF[IO](source = source, phloLimit = 100000) >>= { deploy =>
        computeState(runtimeManager, deploy, genPostState) >>= {
          case (playStateHash1, processedDeploy) =>
            replayComputeState(runtimeManager)(genPostState, processedDeploy) map {
              case Right(replayStateHash1) =>
                assert(playStateHash1 != genPostState && replayStateHash1 == playStateHash1)
              case Left(replayFailure) => fail(s"Unexpected replay failure: $replayFailure")
            }
        }
      }
    }
  }

  private def compareSuccessfulSystemDeploys[S <: SystemDeploy](
      runtimeManager: RuntimeManager[IO]
  )(startState: StateHash)(
      playSystemDeploy: S,
      replaySystemDeploy: S
  )(resultAssertion: S#Result => Boolean): IO[StateHash] =
    for {
      runtime   <- runtimeManager.spawnRuntime
      blockData = BlockData.fromBlock(genesis)
      _         <- runtime.setBlockData(blockData)
      r <- runtime.playSystemDeploy(startState)(playSystemDeploy).attempt >>= {
            case Right(PlaySucceeded(finalPlayStateHash, processedSystemDeploy, _, playResult)) =>
              assert(resultAssertion(playResult))
              for {
                runtimeReplay <- runtimeManager.spawnReplayRuntime
                _             <- runtimeReplay.setBlockData(blockData)

                // Replay System Deploy
                r <- execReplaySystemDeploy(
                      runtimeReplay,
                      startState,
                      replaySystemDeploy,
                      processedSystemDeploy
                    ).value
                      .map {
                        case Right(systemDeployReplayResult) =>
                          systemDeployReplayResult match {
                            case ReplaySucceeded(finalReplayStateHash, replayResult) =>
                              assert(finalPlayStateHash == finalReplayStateHash)
                              assert(playResult == replayResult)
                              finalReplayStateHash
                            case ReplayFailed(systemDeployError) =>
                              fail(
                                s"Unexpected user error during replay: ${systemDeployError.errorMessage}"
                              )
                          }
                        case Left(replayFailure) =>
                          fail(s"Unexpected replay failure: $replayFailure")
                      }
                      .handleErrorWith { throwable =>
                        fail(s"Unexpected system error during replay: ${throwable.getMessage}")
                      }
              } yield r

            case Right(PlayFailed(Failed(_, errorMsg))) =>
              fail(s"Unexpected user error during play: $errorMsg")
            case Left(throwable) =>
              fail(s"Unexpected system error during play: ${throwable.getMessage}")
          }
    } yield r

  private def execReplaySystemDeploy[F[_]: Sync: Log: Span, S <: SystemDeploy](
      runtime: ReplayRhoRuntime[F],
      stateHash: StateHash,
      systemDeploy: S,
      processedSystemDeploy: ProcessedSystemDeploy
  ): EitherT[F, ReplayFailure, SystemDeployReplayResult[S#Result]] = {
    // Resets runtime to start state
    val resetRuntime =
      runtime.reset(stateHash.toBlake2b256Hash).liftEitherT[ReplayFailure]

    // Replays system deploy
    val expectedFailure = processedSystemDeploy.fold(_ => none, (_, errorMsg) => errorMsg.some)
    val replaySysDeploy = runtime.replaySystemDeployInternal(systemDeploy, expectedFailure)

    for {
      replayed <- runtime
                   .rigWithCheck(processedSystemDeploy, resetRuntime *> replaySysDeploy)
                   .semiflatMap {
                     case (Right(value), _) =>
                       runtime.createCheckpoint
                         .map { checkpoint =>
                           SystemDeployReplayResult.replaySucceeded(
                             checkpoint.root.toByteString,
                             value
                           )
                         }
                     case (Left(failure), _) =>
                       SystemDeployReplayResult.replayFailed[S#Result](failure).pure[F]
                   }
    } yield replayed
  }

  "PreChargeDeploy" should "reduce user account balance by the correct amount" in effectTest {
    runtimeManagerResource.use { runtimeManager =>
      val userPk = ConstructDeploy.defaultPub
      compareSuccessfulSystemDeploys(runtimeManager)(genesis.postStateHash)(
        new PreChargeDeploy(
          chargeAmount = 9000000,
          pk = userPk,
          rand = Blake2b512Random(Array(0.toByte))
        ),
        new PreChargeDeploy(
          chargeAmount = 9000000,
          pk = userPk,
          rand = Blake2b512Random(Array(0.toByte))
        )
      )(_ => true) >>= { stateHash0 =>
        compareSuccessfulSystemDeploys(runtimeManager)(stateHash0)(
          new CheckBalance(pk = userPk, rand = Blake2b512Random(Array(1.toByte))),
          new CheckBalance(pk = userPk, rand = Blake2b512Random(Array(1.toByte)))
        )(_ == 0) >>= { stateHash1 =>
          compareSuccessfulSystemDeploys(runtimeManager)(stateHash1)(
            new RefundDeploy(refundAmount = 9000000, Blake2b512Random(Array(2.toByte))),
            new RefundDeploy(refundAmount = 9000000, Blake2b512Random(Array(2.toByte)))
          )(_ => true) >>= { stateHash2 =>
            compareSuccessfulSystemDeploys(runtimeManager)(stateHash2)(
              new CheckBalance(pk = userPk, rand = Blake2b512Random(Array(3.toByte))),
              new CheckBalance(pk = userPk, rand = Blake2b512Random(Array(3.toByte)))
            )(_ == 9000000)
          }
        }
      }
    }
  }

  "closeBlock" should "make epoch change and reward validator" in effectTest {
    runtimeManagerResource.use { runtimeManager =>
      compareSuccessfulSystemDeploys(runtimeManager)(genesis.postStateHash)(
        CloseBlockDeploy(
          initialRand = Blake2b512Random(Array(0.toByte))
        ),
        CloseBlockDeploy(
          initialRand = Blake2b512Random(Array(0.toByte))
        )
      )(_ => true)
    }
  }

  "closeBlock replay" should "fail with different random seed" in {
    an[Exception] should be thrownBy effectTest({
      runtimeManagerResource.use { runtimeManager =>
        compareSuccessfulSystemDeploys(runtimeManager)(genesis.postStateHash)(
          CloseBlockDeploy(
            initialRand = Blake2b512Random(Array(0.toByte))
          ),
          CloseBlockDeploy(
            initialRand = Blake2b512Random(Array(1.toByte))
          )
        )(_ => true)
      }
    })
  }

  "BalanceDeploy" should "compute REV balances" in effectTest {
    runtimeManagerResource.use { runtimeManager =>
      val userPk = ConstructDeploy.defaultPub
      compareSuccessfulSystemDeploys(runtimeManager)(genesis.postStateHash)(
        new CheckBalance(pk = userPk, rand = Blake2b512Random(Array.empty[Byte])),
        new CheckBalance(pk = userPk, rand = Blake2b512Random(Array.empty[Byte]))
      )(_ == 9000000)
    }
  }

  "computeState" should "capture rholang errors" in effectTest {
    val badRholang = """ for(@x <- @"x" & @y <- @"y"){ @"xy"!(x + y) } | @"x"!(1) | @"y"!("hi") """
    for {
      deploy <- ConstructDeploy.sourceDeployNowF[IO](badRholang)
      result <- runtimeManagerResource.use(
                 computeState(_, deploy, genesis.postStateHash)
               )
      _ = result._2.isFailed should be(true)
    } yield ()
  }

  "computeState then computeBonds" should "be replayable after-all" in effectTest {

    import cats.instances.vector._

    val gps = genesis.postStateHash

    val s0 = "@1!(1)"
    val s1 = "@2!(2)"
    val s2 = "for(@a <- @1){ @123!(5 * a) }"

    val deploys0F = Vector(s0, s1, s2).traverse(ConstructDeploy.sourceDeployNowF[IO](_))

    val s3 = "@1!(1)"
    val s4 = "for(@a <- @2){ @456!(5 * a) }"

    val deploys1F = Vector(s3, s4).traverse(ConstructDeploy.sourceDeployNowF[IO](_))

    runtimeManagerResource.use { runtimeManager =>
      for {
        deploys0 <- deploys0F
        deploys1 <- deploys1F
        blockData = BlockData(
          genesisBlockNum,
          genesisContext.validatorPks.head,
          genesisSeqNum
        )
        rand = BlockRandomSeed.randomGenerator(
          genesis.shardId,
          genesisBlockNum,
          genesisContext.validatorPks.head,
          gps.toBlake2b256Hash
        )
        playStateHash0AndProcessedDeploys0 <- runtimeManager.computeState(gps)(
                                               deploys0.toList,
                                               CloseBlockDeploy(
                                                 rand.splitByte(deploys0.length.toByte)
                                               ) :: Nil,
                                               rand,
                                               blockData
                                             )
        (playStateHash0, processedDeploys0, processedSysDeploys0) = playStateHash0AndProcessedDeploys0
        bonds0                                                    <- runtimeManager.computeBonds(playStateHash0)
        replayError0OrReplayStateHash0 <- runtimeManager.replayComputeState(gps)(
                                           processedDeploys0,
                                           processedSysDeploys0,
                                           rand,
                                           blockData,
                                           withCostAccounting = true
                                         )
        replayStateHash0 = replayError0OrReplayStateHash0.toOption.get
        _                = assert(playStateHash0 == replayStateHash0)
        bonds1           <- runtimeManager.computeBonds(playStateHash0)
        _                = assert(bonds0 == bonds1)
        rand2 = BlockRandomSeed.randomGenerator(
          genesis.shardId,
          genesisBlockNum,
          genesisContext.validatorPks.head,
          playStateHash0.toBlake2b256Hash
        )
        playStateHash1AndProcessedDeploys1 <- runtimeManager.computeState(playStateHash0)(
                                               deploys1.toList,
                                               CloseBlockDeploy(
                                                 rand2.splitByte(deploys1.length.toByte)
                                               ) :: Nil,
                                               rand2,
                                               blockData
                                             )
        (playStateHash1, processedDeploys1, processedSysDeploys1) = playStateHash1AndProcessedDeploys1
        bonds2                                                    <- runtimeManager.computeBonds(playStateHash1)
        replayError1OrReplayStateHash1 <- runtimeManager.replayComputeState(playStateHash0)(
                                           processedDeploys1,
                                           processedSysDeploys1,
                                           rand2,
                                           blockData,
                                           withCostAccounting = true
                                         )
        replayStateHash1 = replayError1OrReplayStateHash1.toOption.get
        _                = assert(playStateHash1 == replayStateHash1)
        bonds3           <- runtimeManager.computeBonds(playStateHash1)
        _                = assert(bonds2 == bonds3)
      } yield ()
    }
  }

  it should "capture rholang parsing errors and charge for parsing" in effectTest {
    val badRholang = """ for(@x <- @"x" & @y <- @"y"){ @"xy"!(x + y) | @"x"!(1) | @"y"!("hi") """
    for {
      deploy <- ConstructDeploy.sourceDeployNowF[IO](badRholang)
      result <- runtimeManagerResource.use(
                 computeState(_, deploy, genesis.postStateHash)
               )
      _ = result._2.isFailed should be(true)
      _ = result._2.cost.cost shouldEqual accounting.parsingCost(badRholang).value
    } yield ()
  }

  it should "charge for parsing and execution" in effectTest {
    val correctRholang = """ for(@x <- @"x" & @y <- @"y"){ @"xy"!(x + y) | @"x"!(1) | @"y"!(2) }"""

    runtimeManagerResource
      .use {
        case runtimeManager => {
          implicit val rand: Blake2b512Random = Blake2b512Random(Array.empty[Byte])
          val initialPhlo                     = Cost.UNSAFE_MAX
          for {
            deploy <- ConstructDeploy.sourceDeployNowF[IO](correctRholang)

            runtime       <- runtimeManager.spawnRuntime
            _             <- runtime.cost.set(initialPhlo)
            term          <- Compiler[IO].sourceToADT(deploy.data.term)
            _             <- runtime.inj(term)
            phlosLeft     <- runtime.cost.current
            reductionCost = initialPhlo - phlosLeft

            parsingCost = accounting.parsingCost(correctRholang)

            result <- computeState(runtimeManager, deploy, genesis.postStateHash)

            _ = result._2.cost.cost shouldEqual (reductionCost + parsingCost).value
          } yield ()
        }
      }
  }

  "captureResult" should "return the value at the specified channel after a rholang computation" in effectTest {
    val purseValue = "37"

    runtimeManagerResource.use { mgr =>
      for {
        deploy0 <- ConstructDeploy.sourceDeployNowF[IO](
                    s"""
                      |new rl(`rho:registry:lookup`), NonNegativeNumberCh in {
                      |  rl!(`rho:lang:nonNegativeNumber`, *NonNegativeNumberCh) |
                      |  for(@(_, NonNegativeNumber) <- NonNegativeNumberCh) {
                      |    @NonNegativeNumber!($purseValue, "nn")
                      |  }
                      |}""".stripMargin
                  )
        result0 <- computeState(mgr, deploy0, genesis.postStateHash)
        hash    = result0._1
        deploy1 <- ConstructDeploy.sourceDeployNowF[IO](
                    s"""new return in { for(nn <- @"nn"){ nn!("value", *return) } } """
                  )
        result1 <- mgr.spawnRuntime >>= { _.captureResults(hash, deploy1) }

        _ = result1.size should be(1)
        _ = result1.head should be(ParBuilderUtil.mkTerm(purseValue).toOption.get)
      } yield ()
    }
  }

  it should "handle multiple results and no results appropriately" in {
    val n           = 8
    val returns     = (1 to n).map(i => s""" return!($i) """).mkString("|")
    val term        = s""" new return in { $returns } """
    val termNoRes   = s""" new x, return in { $returns } """
    val deploy      = ConstructDeploy.sourceDeploy(term, timestamp = 0)
    val deployNoRes = ConstructDeploy.sourceDeploy(termNoRes, timestamp = 0)
    val manyResults =
      runtimeManagerResource
        .use(
          mgr =>
            for {
              hash <- RuntimeManager.emptyStateHashFixed.pure[IO]
              res  <- mgr.spawnRuntime >>= { _.captureResults(hash, deploy) }
            } yield res
        )
        .timeout(10.seconds)
        .unsafeRunSync()
    val noResults =
      runtimeManagerResource
        .use(
          mgr =>
            for {
              hash <- RuntimeManager.emptyStateHashFixed.pure[IO]
              res  <- mgr.spawnRuntime >>= { _.captureResults(hash, deployNoRes) }
            } yield res
        )
        .timeout(10.seconds)
        .unsafeRunSync()

    noResults.isEmpty should be(true)

    manyResults.size should be(n)
    (1 to n).forall(i => manyResults.contains(ParBuilderUtil.mkTerm(i.toString).toOption.get)) should be(
      true
    )
  }

  "captureResult" should "throw error if execution fails" in {
    val buggyTerm = s""" new @return in { return.undefined() } """
    val deploy    = ConstructDeploy.sourceDeploy(buggyTerm, timestamp = 0)
    val task =
      runtimeManagerResource
        .use(
          mgr =>
            for {
              hash <- RuntimeManager.emptyStateHashFixed.pure[IO]
              res  <- mgr.spawnRuntime >>= { _.captureResults(hash, deploy) }
            } yield res
        )

    task.attempt.timeout(1.seconds).unsafeRunSync().left.value shouldBe a[BugFoundError]
  }

  "emptyStateHash" should "not remember previous hot store state" in effectTest {
    val term = ConstructDeploy.basicDeployData[IO](0).unsafeRunSync()

    def run: IO[StateHash] =
      runtimeManagerResource
        .use { m =>
          for {
            hash <- RuntimeManager.emptyStateHashFixed.pure[IO]
            afterHash <- computeState(m, term, genesis.postStateHash)
                          .map(_ => hash)
          } yield afterHash
        }

    for {
      res            <- run.product(run)
      (hash1, hash2) = res
      _              = hash1 should be(hash2)
    } yield ()
  }

  "computeState" should "be replayed by replayComputeState" in effectTest {
    runtimeManagerResource.use { runtimeManager =>
      for {
        deploy <- ConstructDeploy.sourceDeployNowF[IO](
                   """
                                                            # new deployerId(`rho:rchain:deployerId`),
                                                            #     rl(`rho:registry:lookup`),
                                                            #     revAddressOps(`rho:rev:address`),
                                                            #     revAddressCh,
                                                            #     revVaultCh in {
                                                            #   rl!(`rho:rchain:revVault`, *revVaultCh) |
                                                            #   revAddressOps!("fromDeployerId", *deployerId, *revAddressCh) |
                                                            #   for(@userRevAddress <- revAddressCh & @(_, revVault) <- revVaultCh){
                                                            #     new userVaultCh in {
                                                            #       @revVault!("findOrCreate", userRevAddress, *userVaultCh) |
                                                            #       for(@(true, userVault) <- userVaultCh){
                                                            #         @userVault!("balance", "IGNORE")
                                                            #       }
                                                            #     }
                                                            #   }
                                                            # }
                                                            #""".stripMargin('#')
                 )
        genPostState = genesis.postStateHash
        blockData = BlockData(
          genesisBlockNum,
          genesisContext.validatorPks.head,
          genesisSeqNum
        )
        rand = BlockRandomSeed.randomGenerator(
          genesis.shardId,
          genesisBlockNum,
          genesisContext.validatorPks.head,
          genPostState.toBlake2b256Hash
        )
        deploys = deploy :: Nil
        computeStateResult <- runtimeManager.computeState(genPostState)(
                               deploys,
                               CloseBlockDeploy(
                                 rand.splitByte(deploys.length.toByte)
                               ) :: Nil,
                               rand,
                               blockData
                             )
        (playPostState, processedDeploys, processedSystemDeploys) = computeStateResult
        replayComputeStateResult <- runtimeManager.replayComputeState(genPostState)(
                                     processedDeploys,
                                     processedSystemDeploys,
                                     rand,
                                     blockData,
                                     withCostAccounting = true
                                   )
      } yield {
        replayComputeStateResult match {
          case Right(replayPostState) =>
            assert(playPostState == replayPostState)
          case Left(replayFailure) => fail(s"Found replay failure: $replayFailure")
        }
      }
    }
  }

  "computeState" should "charge deploys separately" in effectTest {

    def deployCost(p: Seq[ProcessedDeploy]): Long = p.map(_.cost.cost).sum

    runtimeManagerResource.use { mgr =>
      for {
        deploy0 <- ConstructDeploy.sourceDeployNowF[IO](""" for(@x <- @"w") { @"z"!("Got x") } """)
        deploy1 <- ConstructDeploy.sourceDeployNowF[IO](
                    """ for(@x <- @"x" & @y <- @"y"){ @"xy"!(x + y) | @"x"!(1) | @"y"!(10) } """
                  )
        genPostState = genesis.postStateHash
        blockData = BlockData(
          genesisBlockNum,
          genesisContext.validatorPks.head,
          genesisSeqNum
        )
        rand = BlockRandomSeed.randomGenerator(
          genesis.shardId,
          genesisBlockNum,
          genesisContext.validatorPks.head,
          genPostState.toBlake2b256Hash
        )
        deploys = deploy0 :: Nil
        firstDeploy <- mgr
                        .computeState(genPostState)(
                          deploys,
                          CloseBlockDeploy(rand.splitByte(deploys.length.toByte)) :: Nil,
                          rand,
                          blockData
                        )
                        .map(_._2)
        secondDeploy <- mgr
                         .computeState(genPostState)(
                           deploy1 :: Nil,
                           CloseBlockDeploy(rand.splitByte(deploys.length.toByte)) :: Nil,
                           rand,
                           blockData
                         )
                         .map(_._2)
        compoundDeploy <- mgr
                           .computeState(genPostState)(
                             deploy0 :: deploy1 :: Nil,
                             CloseBlockDeploy(rand.splitByte(deploys.length.toByte)) :: Nil,
                             rand,
                             blockData
                           )
                           .map(_._2)
        _                  = assert(firstDeploy.size == 1)
        _                  = assert(secondDeploy.size == 1)
        _                  = assert(compoundDeploy.size == 2)
        firstDeployCost    = deployCost(firstDeploy)
        secondDeployCost   = deployCost(secondDeploy)
        compoundDeployCost = deployCost(compoundDeploy)
        _                  = assert(firstDeployCost < compoundDeployCost)
        _                  = assert(secondDeployCost < compoundDeployCost)
        _ = assert(
          firstDeployCost == deployCost(
            compoundDeploy.find(_.deploy == firstDeploy.head.deploy).toVector
          )
        )
        _ = assert(
          secondDeployCost == deployCost(
            compoundDeploy.find(_.deploy == secondDeploy.head.deploy).toVector
          )
        )
        _ = assert((firstDeployCost + secondDeployCost) == compoundDeployCost)
      } yield ()
    }
  }

  it should "just work" in effectTest {
    runtimeManagerResource.use { runtimeManager =>
      val genPostState = genesis.postStateHash
      val source =
        """
          #new d1,d2,d3,d4,d5,d6,d7,d8,d9 in {
          #  contract d1(@depth) = {
          #    if (depth <= 0) {
          #      Nil
          #    } else {
          #      d1!(depth - 1) | d1!(depth - 1) | d1!(depth - 1) | d1!(depth - 1) | d1!(depth - 1) | d1!(depth - 1) | d1!(depth - 1) | d1!(depth - 1) | d1!(depth - 1) | d1!(depth - 1)  }
          #  } |
          #  contract d2(@depth) = {
          #    if (depth <= 0) {
          #      Nil
          #    } else {
          #      d2!(depth - 1) | d2!(depth - 1) | d2!(depth - 1) | d2!(depth - 1) | d2!(depth - 1) | d2!(depth - 1) | d2!(depth - 1) | d2!(depth - 1) | d2!(depth - 1) | d2!(depth - 1)  }
          #  } |
          #  contract d3(@depth) = {
          #    if (depth <= 0) {
          #      Nil
          #    } else {
          #      d3!(depth - 1) | d3!(depth - 1) | d3!(depth - 1) | d3!(depth - 1) | d3!(depth - 1) | d3!(depth - 1) | d3!(depth - 1) | d3!(depth - 1) | d3!(depth - 1) | d3!(depth - 1)  }
          #  } |
          #  contract d4(@depth) = {
          #    if (depth <= 0) {
          #      Nil
          #    } else {
          #      d4!(depth - 1) | d4!(depth - 1) | d4!(depth - 1) | d4!(depth - 1) | d4!(depth - 1) | d4!(depth - 1) | d4!(depth - 1) | d4!(depth - 1) | d4!(depth - 1) | d4!(depth - 1)  }
          #  } |
          #  contract d5(@depth) = {
          #    if (depth <= 0) {
          #      Nil
          #    } else {
          #      d5!(depth - 1) | d5!(depth - 1) | d5!(depth - 1) | d5!(depth - 1) | d5!(depth - 1) | d5!(depth - 1) | d5!(depth - 1) | d5!(depth - 1) | d5!(depth - 1) | d5!(depth - 1)  }
          #  } |
          #  contract d6(@depth) = {
          #    if (depth <= 0) {
          #      Nil
          #    } else {
          #      d6!(depth - 1) | d6!(depth - 1) | d6!(depth - 1) | d6!(depth - 1) | d6!(depth - 1) | d6!(depth - 1) | d6!(depth - 1) | d6!(depth - 1) | d6!(depth - 1) | d6!(depth - 1)  }
          #  } |
          #  contract d7(@depth) = {
          #    if (depth <= 0) {
          #      Nil
          #    } else {
          #      d7!(depth - 1) | d7!(depth - 1) | d7!(depth - 1) | d7!(depth - 1) | d7!(depth - 1) | d7!(depth - 1) | d7!(depth - 1) | d7!(depth - 1) | d7!(depth - 1) | d7!(depth - 1)  }
          #  } |
          #  contract d8(@depth) = {
          #    if (depth <= 0) {
          #      Nil
          #    } else {
          #      d8!(depth - 1) | d8!(depth - 1) | d8!(depth - 1) | d8!(depth - 1) | d8!(depth - 1) | d8!(depth - 1) | d8!(depth - 1) | d8!(depth - 1) | d8!(depth - 1) | d8!(depth - 1) }
          #  } |
          #  contract d9(@depth) = {
          #    if (depth <= 0) {
          #      Nil
          #    } else {
          #      d9!(depth - 1) | d9!(depth - 1) | d9!(depth - 1) | d9!(depth - 1) | d9!(depth - 1) | d9!(depth - 1) | d9!(depth - 1) | d9!(depth - 1) | d9!(depth - 1) | d9!(depth - 1) }
          #  } |
          #  d1!(2) |
          #  d2!(2) |
          #  d3!(2) |
          #  d4!(2) |
          #  d5!(2) |
          #  d6!(2) |
          #  d7!(2) |
          #  d8!(2) |
          #  d9!(2)
          #}
          #""".stripMargin('#')
      ConstructDeploy.sourceDeployNowF[IO](source = source, phloLimit = Int.MaxValue - 2) >>= {
        deploy =>
          computeState(runtimeManager, deploy, genPostState) >>= {
            case (playStateHash1, processedDeploy) =>
              replayComputeState(runtimeManager)(genPostState, processedDeploy) map {
                case Right(replayStateHash1) =>
                  assert(playStateHash1 != genPostState && replayStateHash1 == playStateHash1)
                case Left(replayFailure) => fail(s"Unexpected replay failure: $replayFailure")
              }
          }
      }
    }
  }

  private def invalidReplay(source: String): IO[Either[ReplayFailure, StateHash]] =
    runtimeManagerResource.use { runtimeManager =>
      for {
        deploy       <- ConstructDeploy.sourceDeployNowF[IO](source, phloLimit = 10000)
        genPostState = genesis.postStateHash
        blockData = BlockData(
          genesisBlockNum,
          genesisContext.validatorPks.head,
          genesisSeqNum
        )
        rand = BlockRandomSeed.randomGenerator(
          genesis.shardId,
          genesisBlockNum,
          genesisContext.validatorPks.head,
          genPostState.toBlake2b256Hash
        )
        deploys = Seq(deploy)
        newState <- runtimeManager
                     .computeState(genPostState)(
                       Seq(deploy),
                       Seq(
                         CloseBlockDeploy(rand.splitByte(deploys.length.toByte))
                       ),
                       rand,
                       blockData
                     )
        (_, processedDeploys, processedSystemDeploys) = newState
        processedDeploy                               = processedDeploys.head
        processedDeployCost                           = processedDeploy.cost.cost
        invalidProcessedDeploy = processedDeploy.copy(
          cost = PCost(processedDeployCost - 1)
        )
        result <- runtimeManager.replayComputeState(genPostState)(
                   Seq(invalidProcessedDeploy),
                   processedSystemDeploys,
                   rand,
                   blockData,
                   withCostAccounting = true
                 )
      } yield result
    }

  "replayComputeState" should "catch discrepancies in initial and replay cost when no errors are thrown" in effectTest {
    invalidReplay("@0!(0) | for(@0 <- @0){ Nil }").map {
      case Left(ReplayCostMismatch(initialCost, replayCost)) =>
        assert(initialCost == 322L && replayCost == 323L)
      case _ => fail()
    }
  }

  "replayComputeState" should "not catch discrepancies in initial and replay cost when user errors are thrown" in effectTest {
    invalidReplay("@0!(0) | for(@x <- @0){ x.undefined() }").map {
      case Left(ReplayCostMismatch(initialCost, replayCost)) =>
        assert(initialCost == 9999L && replayCost == 10000L)
      case _ => fail()
    }
  }

  // This is additional test for sorting with joins and channels inside joins.
  // - after reverted PR https://github.com/rchain/rchain/pull/2436
  "joins" should "be replayed correctly" in effectTest {
    def hex(bs: ByteString) = Base16.encode(bs.toByteArray)

    val term =
      """
        |new a, b, c, d in {
        |  for (_ <- a & _ <- b) { Nil } |
        |  for (_ <- a & _ <- c) { Nil } |
        |  for (_ <- a & _ <- d) { Nil }
        |}
        |""".stripMargin

    val genPostState = genesis.postStateHash
    for {
      deploy <- ConstructDeploy.sourceDeployNowF[IO](term)
      result <- runtimeManagerResource.use { rm =>
                 val blockData = BlockData(
                   1L,
                   genesisContext.validatorPks.head,
                   1
                 )
                 val rand = BlockRandomSeed.randomGenerator(
                   genesis.shardId,
                   1L,
                   genesisContext.validatorPks.head,
                   genPostState.toBlake2b256Hash
                 )
                 for {

                   newState                                           <- rm.computeState(genPostState)(Seq(deploy), Seq(), rand, blockData)
                   (stateHash, processedDeploys, processedSysDeploys) = newState
                   result <- rm.replayComputeState(genPostState)(
                              processedDeploys,
                              processedSysDeploys,
                              rand,
                              blockData,
                              withCostAccounting = true
                            )
                 } yield (stateHash, result.toOption.get)
               }
      (playHash, replayHash) = result
      _                      = hex(playHash) shouldBe hex(replayHash)
    } yield ()
  }
}
