package com.wavesplatform.it.sync

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.domain.account.KeyPair
import com.wavesplatform.dex.domain.asset.Asset.Waves
import com.wavesplatform.dex.domain.order.Order.PriceConstant
import com.wavesplatform.dex.domain.order.OrderType._
import com.wavesplatform.dex.it.api.responses.dex.OrderStatus
import com.wavesplatform.it.MatcherSuiteBase

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class OrderBookTestSuite extends MatcherSuiteBase {

  override protected def dexInitialSuiteConfig: Config = ConfigFactory.parseString(s"""TN.dex.price-assets = [ "$UsdId", "TN" ]""")

  private case class ReservedBalances(wct: Long, usd: Long, waves: Long)
  private def reservedBalancesOf(pk: KeyPair): ReservedBalances = {
    val reservedBalances = dex1.api.reservedBalance(pk)
    ReservedBalances(
      reservedBalances.getOrElse(wct, 0),
      reservedBalances.getOrElse(usd, 0),
      reservedBalances.getOrElse(Waves, 0)
    )
  }

  private val (amount, price)         = (1000L, PriceConstant)
  private val buyOrder                = mkOrder(alice, wctUsdPair, BUY, 2 * amount, price)
  private val anotherBuyOrder         = mkOrder(alice, wctUsdPair, BUY, amount, price)
  private val sellOrder               = mkOrder(bob, wctUsdPair, SELL, amount, 2 * price)
  private val buyOrderForAnotherPair  = mkOrder(alice, wctWavesPair, BUY, amount, price)
  private val sellOrderForAnotherPair = mkOrder(bob, wctWavesPair, SELL, amount, 2 * price)

  private var aliceRBForOnePair = ReservedBalances(0, 0, 0)
  private var bobRBForOnePair   = ReservedBalances(0, 0, 0)

  private var aliceRBForBothPairs = ReservedBalances(0, 0, 0)
  private var bobRBForBothPairs   = ReservedBalances(0, 0, 0)

  override protected def beforeAll(): Unit = {
    wavesNode1.start()
    broadcastAndAwait(IssueUsdTx, IssueWctTx)
    dex1.start()
  }

  "Place orders and delete the order book" in {
    val submitted = mkOrder(bob, wctUsdPair, SELL, amount, price)
    List(buyOrder, anotherBuyOrder, submitted, sellOrder).foreach(dex1.api.place)

    dex1.api.waitForOrderStatus(buyOrder, OrderStatus.PartiallyFilled)
    dex1.api.waitForOrderStatus(submitted, OrderStatus.Filled)

    aliceRBForOnePair = reservedBalancesOf(alice)
    bobRBForOnePair = reservedBalancesOf(bob)

    List(buyOrderForAnotherPair, sellOrderForAnotherPair).foreach(dex1.api.place)

    dex1.api.waitForOrderStatus(buyOrderForAnotherPair, OrderStatus.Accepted)
    dex1.api.waitForOrderStatus(sellOrderForAnotherPair, OrderStatus.Accepted)

    aliceRBForBothPairs = reservedBalancesOf(alice)
    bobRBForBothPairs = reservedBalancesOf(bob)

    dex1.api.tryDeleteOrderBook(wctUsdPair) shouldBe 'right
  }

  "When delete order book" - {
    "orders by the pair should be canceled" in {
      dex1.api.waitForOrderStatus(buyOrder, OrderStatus.Cancelled)
      dex1.api.waitForOrderStatus(anotherBuyOrder, OrderStatus.Cancelled)
      dex1.api.waitForOrderStatus(sellOrder, OrderStatus.Cancelled)
    }

    "the order book was deleted" in {
      withClue("orderBook") {
        val orderBook = dex1.api.orderBook(wctUsdPair)
        orderBook.bids shouldBe empty
        orderBook.asks shouldBe empty
      }

      withClue("tradingMarkets") {
        val tradingPairs = dex1.api.allOrderBooks.markets.map(x => s"${x.amountAsset}-${x.priceAsset}")
        tradingPairs shouldNot contain(wctUsdPair.key)
      }

      withClue("getAllSnapshotOffsets") {
        dex1.api.allSnapshotOffsets.keySet shouldNot contain(wctUsdPair.key)
      }
    }

    "reserved balances should be released for the pair" in {
      val (aliceReservedBalances, bobReservedBalances) = (reservedBalancesOf(alice), reservedBalancesOf(bob))
      aliceReservedBalances.usd shouldBe 0
      aliceReservedBalances.waves shouldBe (aliceRBForBothPairs.waves - aliceRBForOnePair.waves)
      bobReservedBalances.wct shouldBe (bobRBForBothPairs.wct - bobRBForOnePair.wct)
      bobReservedBalances.waves shouldBe (bobRBForBothPairs.waves - bobRBForOnePair.waves)
    }

    "it should not affect other pairs and their orders" in {
      dex1.api.orderStatus(buyOrderForAnotherPair).status shouldBe OrderStatus.Accepted
      dex1.api.orderStatus(sellOrderForAnotherPair).status shouldBe OrderStatus.Accepted
      dex1.api.place(mkOrder(alice, wctWavesPair, BUY, amount, price))

      val orderBook = dex1.api.orderBook(wctWavesPair)
      orderBook.bids shouldNot be(empty)
      orderBook.asks shouldNot be(empty)
    }

    "matcher can start after multiple delete events" in {
      def deleteWctWaves() = dex1.asyncApi.tryDeleteOrderBook(wctWavesPair)
      val deleteMultipleTimes = deleteWctWaves()
        .zip(deleteWctWaves())
        .map(_ => ())
        .recover { case _ => () } // It's ok: either this should fail, or restartNode should work

      Await.ready(deleteMultipleTimes, 1.minute)
      dex1.restart()
    }
  }
}
