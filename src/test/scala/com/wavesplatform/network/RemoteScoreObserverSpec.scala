package com.wavesplatform.network

import com.wavesplatform.TransactionGen
import com.wavesplatform.state2.ByteStr
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.Eventually
import org.scalatest.exceptions.TestFailedDueToTimeoutException
import org.scalatest.{FreeSpec, Matchers}
import scorex.lagonaki.mocks.TestBlock

import scala.concurrent.duration.DurationInt

class RemoteScoreObserverSpec extends FreeSpec
  with Matchers
  with MockFactory
  with Eventually
  with TransactionGen {

  private val lastSignatures = Seq(ByteStr("1".getBytes), ByteStr("2".getBytes))

  "should request an extension" - {
    "when a new channel has the better score than the local one" in {
      val channel = new EmbeddedChannel(new RemoteScoreObserver(1.minute, lastSignatures, 1))
      channel.writeInbound(BigInt(2))
      channel.flushInbound()

      val expected = LoadBlockchainExtension(lastSignatures)
      eventually {
        val actual = channel.readOutbound[LoadBlockchainExtension]()
        actual shouldBe expected
      }
    }

    "from the second best channel" - {
      "when the connection with best one is dropped" - {
        def test(bestScore: BigInt, secondBestScore: BigInt): Unit = {
          val scoreObserver = new RemoteScoreObserver(1.minute, lastSignatures, 1)

          val channel1 = new EmbeddedChannel(scoreObserver)
          channel1.writeInbound(BigInt(3))
          channel1.flushInbound()

          val channel2 = new EmbeddedChannel(scoreObserver)
          channel2.writeInbound(BigInt(2))
          channel2.flushInbound()

          channel1.close()

          eventually {
            val actual = channel2.readOutbound[LoadBlockchainExtension]()
            Option(actual) shouldBe defined
          }
        }

        Seq(
          3 -> 2,
          3 -> 3,
          3 -> 4
        ).foreach { case (best, secondBest) =>
          s"$best then $secondBest" in test(best, secondBest)
        }
      }

//      "when the score of the best one is expired" in {
//        val scoreTtl = 500.millis
//
//        val scoreObserver = new RemoteScoreObserver(scoreTtl, lastSignatures, 1)
//        val channel1 = new EmbeddedChannel(scoreObserver)
//        channel1.writeInbound(BigInt(3))
//        channel1.flushInbound()
//
//        eventually {
//          val actual = channel1.readOutbound[LoadBlockchainExtension]()
//          Option(actual) shouldBe defined
//        }
//
//        val channel2 = new EmbeddedChannel(scoreObserver)
//        channel2.writeInbound(BigInt(2))
//        channel2.flushInbound()
//
//        Thread.sleep(scoreTtl.toMillis)
//        channel1.runPendingTasks()
//
//        eventually {
//          val actual = channel2.readOutbound[LoadBlockchainExtension]()
//          Option(actual) shouldBe defined
//        }
//      }
    }
  }

  "should not request a new extension if a previous one is not downloaded yet" - {
    "when the score of the best channel was changed" in {
      val channel = new EmbeddedChannel(new RemoteScoreObserver(1.minute, lastSignatures, 1))
      channel.writeInbound(BigInt(2))
      channel.flushInbound()

      eventually {
        val actual = channel.readOutbound[LoadBlockchainExtension]()
        Option(actual) shouldBe defined
      }

      channel.writeInbound(BigInt(3))
      channel.flushInbound()

      intercept[TestFailedDueToTimeoutException] {
        eventually {
          val actual = channel.readOutbound[LoadBlockchainExtension]()
          Option(actual) shouldBe defined
        }
      }
    }

    "when new connection" in {
      val scoreObserver = new RemoteScoreObserver(1.minute, lastSignatures, 1)
      val channel1 = new EmbeddedChannel(scoreObserver)
      channel1.writeInbound(BigInt(2))
      channel1.flushInbound()

      eventually {
        val actual = channel1.readOutbound[LoadBlockchainExtension]()
        Option(actual) shouldBe defined
      }

      val channel2 = new EmbeddedChannel(scoreObserver)
      channel2.writeInbound(BigInt(3))
      channel2.flushInbound()

      intercept[TestFailedDueToTimeoutException] {
        eventually {
          val actual = channel2.readOutbound[LoadBlockchainExtension]()
          Option(actual) shouldBe defined
        }
      }
    }
  }

  "should re-request extensions" - {
    "when the local score is changed but still worse than the better one" in {
      var currentLastSignatures = lastSignatures

      val channel = new EmbeddedChannel(new RemoteScoreObserver(1.minute, currentLastSignatures, 1))
      channel.writeInbound(BigInt(3))
      channel.flushInbound()

      eventually {
        val actual = channel.readOutbound[LoadBlockchainExtension]()
        Option(actual) shouldBe defined
      }

      currentLastSignatures :+= ByteStr("3".getBytes)
      channel.writeOutbound(LocalScoreChanged(2))

      val expected = LoadBlockchainExtension(currentLastSignatures)
      eventually {
        val actual = channel.readOutbound[LoadBlockchainExtension]()
        actual shouldBe expected
      }
    }
  }

  "should propagate blocks from an expected extensions" in {
    var wasExtensionPropagated = false

    val channel = new EmbeddedChannel(
      new RemoteScoreObserver(1.minute, lastSignatures, 1),
      new ChannelInboundHandlerAdapter {
        override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
          wasExtensionPropagated = msg.isInstanceOf[ExtensionBlocks]
        }
      }
    )

    channel.writeInbound(BigInt(2))
    channel.flushInbound()

    eventually {
      val actual = channel.readOutbound[LoadBlockchainExtension]()
      Option(actual) shouldBe defined
    }

    channel.writeInbound(ExtensionBlocks(Seq(TestBlock.create(Seq(transferGen.sample.get)))))
    channel.flushInbound()

    eventually {
      wasExtensionPropagated shouldBe true
    }
  }

  "should ignore blocks from an unexpected extensions" in {
    var wasExtensionPropagated = false

    val channel = new EmbeddedChannel(
      new RemoteScoreObserver(1.minute, lastSignatures, 1),
      new ChannelInboundHandlerAdapter {
        override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
          wasExtensionPropagated = msg.isInstanceOf[ExtensionBlocks]
        }
      }
    )
    channel.writeInbound(ExtensionBlocks(Seq(TestBlock.create(Seq(transferGen.sample.get)))))
    channel.flushInbound()

    intercept[TestFailedDueToTimeoutException] {
      eventually {
        wasExtensionPropagated shouldBe true
      }
    }
  }

//  "should not affect an expiration timeout, if a received score is the same" in {
//    val scoreTtl = 500.millis
//
//    val scoreObserver = new RemoteScoreObserver(scoreTtl, lastSignatures, 1)
//    val channel1 = new EmbeddedChannel(scoreObserver)
//    channel1.writeInbound(BigInt(3))
//    channel1.flushInbound()
//
//    eventually {
//      val actual = channel1.readOutbound[LoadBlockchainExtension]()
//      Option(actual) shouldBe defined
//    }
//
//    val channel2 = new EmbeddedChannel(scoreObserver)
//    channel2.writeInbound(BigInt(2))
//    channel2.flushInbound()
//
//    Thread.sleep(scoreTtl.toMillis / 2)
//    channel1.writeInbound(BigInt(3))
//    channel1.flushInbound()
//    channel1.runPendingTasks()
//
//    Thread.sleep(scoreTtl.toMillis / 2 + 1)
//    channel1.runPendingTasks()
//
//    eventually {
//      val actual = channel2.readOutbound[LoadBlockchainExtension]()
//      Option(actual) shouldBe defined
//    }
//  }

}
