package com.wavesplatform.it.sync

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.domain.account.KeyPair
import com.wavesplatform.dex.domain.asset.AssetPair
import com.wavesplatform.dex.domain.order.OrderType.{BUY, SELL}
import com.wavesplatform.dex.domain.order.{Order, OrderType}
import com.wavesplatform.dex.it.api.responses.dex.{LevelResponse, OrderStatus}
import com.wavesplatform.dex.it.test.Scripts
import com.wavesplatform.dex.it.waves.MkWavesEntities.IssueResults
import com.wavesplatform.it.MatcherSuiteBase
import com.wavesplatform.it.config.DexTestConfig._

/**
  * BUY orders price:  (1 - p) * best bid <= price <= (1 + l) * best ask
  * SELL orders price: (1 - l) * best bid <= price <= (1 + p) * best ask
  *
  * where:
  *
  *   p = max price deviation profit / 100
  *   l = max price deviation loss / 100
  *   best bid = highest price of buy
  *   best ask = lowest price of sell
  *
  * BUY orders fee:  fee >= fs * (1 - fd) * best ask * amount
  * SELL orders fee: fee >= fs * (1 - fd) * best bid * amount
  *
  * where:
  *
  *   fs = fee in percents from order-fee settings (order-fee.percent.min-fee) / 100
  *   fd = max fee deviation / 100
  *   best bid = highest price of buy
  *   best ask = lowest price of sell
  */
class OrderDeviationsTestSuite extends MatcherSuiteBase {

  val deviationProfit = 70
  val deviationLoss   = 60
  val deviationFee    = 40

  val IssueResults(scriptAssetTx, _, scriptAsset) =
    mkIssueExtended(alice, "asset1", defaultAssetQuantity, fee = smartIssueFee, script = Some(Scripts.alwaysTrue))

  val IssueResults(anotherScriptAssetTx, _, anotherScriptAsset) =
    mkIssueExtended(alice, "asset2", defaultAssetQuantity, fee = smartIssueFee, script = Some(Scripts.alwaysTrue))

  val scriptAssetsPair: AssetPair = createAssetPair(scriptAsset, anotherScriptAsset)

  override protected val dexInitialSuiteConfig: Config = ConfigFactory.parseString(
    s"""
       |TN.dex {
       |  price-assets = [ "$UsdId", "$BtcId", "TN" ]
       |  allowed-order-versions = [1, 2, 3]
       |  max-price-deviations {
       |    enable = yes
       |    profit = $deviationProfit
       |    loss = $deviationLoss
       |    fee = $deviationFee
       |  }
       |  order-fee.-1 {
       |    mode = "percent"
       |    percent {
       |      asset-type = "price"
       |      min-fee = 0.1
       |    }
       |  }
       |}
       """.stripMargin
  )

  override protected def beforeAll(): Unit = {
    wavesNode1.start()

    broadcastAndAwait(IssueBtcTx, IssueEthTx, IssueUsdTx, scriptAssetTx, anotherScriptAssetTx)
    Seq(scriptAsset, anotherScriptAsset, eth, usd).foreach { asset =>
      broadcastAndAwait(
        mkTransfer(alice, bob, defaultAssetQuantity / 2, asset, 0.005.TN)
      )
    }
    broadcastAndAwait(mkTransfer(bob, alice, defaultAssetQuantity / 2, btc, 0.005.TN))

    dex1.start()
  }

  def orderIsOutOfDeviationBounds(price: String, orderType: OrderType): String = {

    val lowerBound = orderType match {
      case SELL => 100 - deviationLoss
      case BUY  => 100 - deviationProfit
    }

    val upperBound = orderType match {
      case SELL => 100 + deviationProfit
      case BUY  => 100 + deviationLoss
    }

    s"The $orderType order's price $price is out of deviation bounds. It should meet the following matcher's requirements: " +
      s"$lowerBound% of best bid price <= order price <= $upperBound% of best ask price"
  }

  def feeIsOutOfDeviationBounds(fee: String, feeAssetId: String, orderType: OrderType): String = {

    val marketType = orderType match {
      case SELL => "bid"
      case BUY  => "ask"
    }

    s"The $orderType order's matcher fee $fee $feeAssetId is out of deviation bounds. " +
      s"It should meet the following matcher's requirements: matcher fee >= ${100 - deviationFee}% of fee which should be paid in case of matching with best $marketType"
  }

  "buy orders price is" - {

    "in deviation bounds" - {
      for (assetPair <- Seq(wavesBtcPair, ethWavesPair, scriptAssetsPair)) s"$assetPair" in {

        val feeAsset = assetPair.priceAsset

        val bestAskOrder = mkOrder(alice, assetPair, SELL, 2000.TN, 500000, 4 * matcherFee, feeAsset)
        val bestBidOrder = mkOrder(bob, assetPair, BUY, 2000.TN, 300000, 2 * matcherFee, feeAsset)

        placeAndAwaitAtDex(bestAskOrder)
        placeAndAwaitAtDex(bestBidOrder)

        dex1.api.orderBook(assetPair).asks should matchTo(List(LevelResponse(2000.TN, 500000)))
        dex1.api.orderBook(assetPair).bids should matchTo(List(LevelResponse(2000.TN, 300000)))

        placeAndAwaitAtDex(mkOrder(bob, assetPair, BUY, 1000.TN, 90000, 3 * matcherFee, feeAsset), OrderStatus.Accepted)
        placeAndAwaitAtDex(mkOrder(bob, assetPair, BUY, 1000.TN, 800000, 3 * matcherFee, feeAsset), OrderStatus.Filled)

        waitForOrderAtNode(bestAskOrder)

        dex1.api.reservedBalance(alice) should matchTo(Map(assetPair.amountAsset -> 100000000000L, feeAsset -> 2 * matcherFee))
        dex1.api.reservedBalance(bob) should matchTo(Map(assetPair.priceAsset    -> 691500000L))

        cancelAll(alice, bob)
      }

      s"$wavesUsdPair" in {
        val feeAsset     = wavesUsdPair.priceAsset
        val bestAskOrder = mkOrder(bob, wavesUsdPair, SELL, 2000.TN, 500, 4 * 300, feeAsset)
        val bestBidOrder = mkOrder(alice, wavesUsdPair, BUY, 2000.TN, 300, 2 * 300, feeAsset)

        placeAndAwaitAtDex(bestAskOrder, OrderStatus.Accepted)
        placeAndAwaitAtDex(bestBidOrder, OrderStatus.Accepted)

        dex1.api.orderBook(wavesUsdPair).asks should matchTo(List(LevelResponse(2000.TN, 500)))
        dex1.api.orderBook(wavesUsdPair).bids should matchTo(List(LevelResponse(2000.TN, 300)))

        placeAndAwaitAtDex(mkOrder(alice, wavesUsdPair, BUY, 1000.TN, 90, 3 * 300, feeAsset), OrderStatus.Accepted)
        placeAndAwaitAtDex(mkOrder(alice, wavesUsdPair, BUY, 1000.TN, 800, 3 * 300, feeAsset), OrderStatus.Filled)

        waitForOrderAtNode(bestAskOrder)

        dex1.api.reservedBalance(bob) should matchTo(Map(wavesUsdPair.amountAsset  -> 100000000000L, feeAsset -> 600))
        dex1.api.reservedBalance(alice) should matchTo(Map(wavesUsdPair.priceAsset -> 691500L))

        cancelAll(alice, bob)
      }
    }

    "out of deviation bounds" - {
      "-- too low" - {
        for (assetPair <- Seq(wavesBtcPair, ethWavesPair, scriptAssetsPair)) s"$assetPair" in {
          val bestBidOrder = mkOrder(bob, assetPair, BUY, 1000.TN, 4000000, 2 * matcherFee, feeAsset = assetPair.priceAsset)
          placeAndAwaitAtDex(bestBidOrder)

          dex1.api.orderBook(assetPair).bids shouldBe List(LevelResponse(1000.TN, 4000000))
          dex1.api.reservedBalance(bob)(assetPair.priceAsset) shouldBe 300600000L

          dex1.api.tryPlace(mkOrder(bob, assetPair, BUY, 1000.TN, 89999, matcherFee, feeAsset = assetPair.priceAsset)) should failWith(
            9441295, // DeviantOrderPrice
            orderIsOutOfDeviationBounds("0.00089999", BUY)
          )

          dex1.api.reservedBalance(bob) shouldBe Map(assetPair.priceAsset -> 300600000L)

          dex1.api.cancel(bob, bestBidOrder)

          dex1.api.reservedBalance(bob) shouldBe empty
          cancelAll(alice)
        }

        s"$wavesUsdPair" in {
          val bestBidOrder = mkOrder(bob, wavesUsdPair, BUY, 1000.TN, 300, 2 * 300, feeAsset = wavesUsdPair.priceAsset)
          placeAndAwaitAtDex(bestBidOrder)

          dex1.api.orderBook(wavesUsdPair).bids shouldBe List(LevelResponse(1000.TN, 300))

          dex1.api.reservedBalance(bob)(wavesUsdPair.priceAsset) shouldBe 300600L

          dex1.api.tryPlace(mkOrder(bob, wavesUsdPair, BUY, 1000.TN, 89, matcherFee, feeAsset = wavesUsdPair.priceAsset)) should failWith(
            9441295, // DeviantOrderPrice
            orderIsOutOfDeviationBounds("0.89", BUY)
          )

          dex1.api.reservedBalance(bob) shouldBe Map(wavesUsdPair.priceAsset -> 300600L)

          cancelAll(alice, bob)
        }
      }

      "-- too high" - {
        for (assetPair <- Seq(wavesBtcPair, ethWavesPair, scriptAssetsPair)) s"$assetPair" in {
          val bestAskOrder = mkOrder(alice, assetPair, SELL, 1000.TN, 500000, 4 * matcherFee, feeAsset = assetPair.priceAsset)
          placeAndAwaitAtDex(bestAskOrder)

          dex1.api.orderBook(assetPair).asks shouldBe List(LevelResponse(1000.TN, 500000))

          dex1.api
            .tryPlace(mkOrder(bob, assetPair, BUY, 1000.TN, 800001, 3 * matcherFee, feeAsset = assetPair.priceAsset)) should failWith(
            9441295, // DeviantOrderPrice
            orderIsOutOfDeviationBounds("0.00800001", BUY)
          )

          dex1.api.reservedBalance(bob) shouldBe empty

          cancelAll(alice, bob)
        }

        s"$wavesUsdPair" in {
          val bestAskOrder = mkOrder(bob, wavesUsdPair, SELL, 1000.TN, 500, 4 * 300, feeAsset = wavesUsdPair.priceAsset)
          placeAndAwaitAtDex(bestAskOrder)

          dex1.api.orderBook(wavesUsdPair).asks shouldBe List(LevelResponse(1000.TN, 500))

          dex1.api.tryPlace(mkOrder(alice, wavesUsdPair, BUY, 1000.TN, 801, 3 * 300, feeAsset = wavesUsdPair.priceAsset)) should failWith(
            9441295, // DeviantOrderPrice
            orderIsOutOfDeviationBounds("8.01", BUY)
          )

          dex1.api.reservedBalance(alice) shouldBe empty

          cancelAll(alice, bob)
        }
      }
    }
  }

  "sell orders price is" - {
    "in deviation bounds" - {

      for (assetPair <- Seq(wavesBtcPair, ethWavesPair, scriptAssetsPair)) s"$assetPair" in {

        val feeAsset     = assetPair.priceAsset
        val bestAskOrder = mkOrder(alice, assetPair, SELL, 2000.TN, 500000, 4 * matcherFee, feeAsset)
        val bestBidOrder = mkOrder(bob, assetPair, BUY, 2000.TN, 300000, 2 * matcherFee, feeAsset)

        Seq(bestAskOrder, bestBidOrder).foreach { placeAndAwaitAtDex(_) }

        dex1.api.orderBook(assetPair).asks should matchTo(List(LevelResponse(2000.TN, 500000)))
        dex1.api.orderBook(assetPair).bids should matchTo(List(LevelResponse(2000.TN, 300000)))

        placeAndAwaitAtDex(mkOrder(alice, assetPair, SELL, 1000.TN, 850000, 3 * matcherFee, feeAsset), OrderStatus.Accepted)
        placeAndAwaitAtDex(mkOrder(alice, assetPair, SELL, 1000.TN, 120000, 3 * matcherFee, feeAsset), OrderStatus.Filled)

        waitForOrderAtNode(bestBidOrder)

        dex1.api.reservedBalance(alice) should matchTo(Map(assetPair.amountAsset -> 300000000000L, feeAsset -> 7 * matcherFee))
        dex1.api.reservedBalance(bob) should matchTo(Map(assetPair.priceAsset    -> 300300000L))

        cancelAll(alice, bob)
      }
    }

    "out of deviation bounds" - {
      "-- too low" - {
        for (assetPair <- Seq(wavesBtcPair, ethWavesPair, scriptAssetsPair)) s"$assetPair" in {
          val bestBidOrder = mkOrder(bob, assetPair, BUY, 1000.TN, 4000000, matcherFee, feeAsset = assetPair.priceAsset)
          placeAndAwaitAtDex(bestBidOrder)

          dex1.api.orderBook(assetPair).bids shouldBe List(LevelResponse(1000.TN, 4000000))

          dex1.api.tryPlace(mkOrder(alice, assetPair, SELL, 1000.TN, 119999, matcherFee, feeAsset = assetPair.priceAsset)) should failWith(
            9441295, // DeviantOrderPrice
            orderIsOutOfDeviationBounds("0.00119999", SELL)
          )

          dex1.api.reservedBalance(alice) shouldBe empty

          cancelAll(bob)
        }

        s"$wavesUsdPair" in {
          val bestBidOrder = mkOrder(bob, wavesUsdPair, BUY, 1000.TN, 300, 300, feeAsset = wavesUsdPair.priceAsset)
          placeAndAwaitAtDex(bestBidOrder)

          dex1.api.orderBook(wavesUsdPair).bids shouldBe List(LevelResponse(1000.TN, 300))

          dex1.api.tryPlace(mkOrder(alice, wavesUsdPair, SELL, 1000.TN, 119, 300, feeAsset = wavesUsdPair.priceAsset)) should failWith(
            9441295, // DeviantOrderPrice
            orderIsOutOfDeviationBounds("1.19", SELL)
          )

          dex1.api.reservedBalance(alice) shouldBe empty

          cancelAll(bob, bob)
        }
      }

      "-- too high" - {
        for (assetPair <- Seq(wavesBtcPair, ethWavesPair, scriptAssetsPair)) s"$assetPair" in {

          val feeAsset     = assetPair.priceAsset
          val bestAskOrder = mkOrder(alice, assetPair, SELL, 1000.TN, 500000, 2 * matcherFee, feeAsset)

          placeAndAwaitAtDex(bestAskOrder)

          dex1.api.orderBook(assetPair).asks should matchTo(List(LevelResponse(1000.TN, 500000)))

          dex1.api
            .tryPlace(mkOrder(alice, assetPair, SELL, 1000.TN, 850001, 3 * matcherFee, feeAsset)) should failWith(
            9441295, // DeviantOrderPrice
            orderIsOutOfDeviationBounds("0.00850001", SELL)
          )
          dex1.api.reservedBalance(alice) should matchTo(Map(assetPair.amountAsset -> 1000.TN, feeAsset -> 2 * matcherFee))

          cancelAll(alice, bob)
        }

        s"$wavesUsdPair" in {

          val feeAsset     = wavesUsdPair.priceAsset
          val bestAskOrder = mkOrder(alice, wavesUsdPair, SELL, 1000.TN, 500, 2 * 300, feeAsset)
          placeAndAwaitAtDex(bestAskOrder)

          dex1.api.orderBook(wavesUsdPair).asks shouldBe List(LevelResponse(1000.TN, 500))

          dex1.api.tryPlace(mkOrder(alice, wavesUsdPair, SELL, 1000.TN, 851, 3 * 300, feeAsset)) should failWith(
            9441295, // DeviantOrderPrice
            orderIsOutOfDeviationBounds("8.51", SELL)
          )
          dex1.api.reservedBalance(alice) shouldBe Map(wavesUsdPair.amountAsset -> 1000.TN, feeAsset -> 2 * 300)

          cancelAll(alice, bob)
        }
      }
    }
  }

  "orders fee is" - {
    "in deviation bounds" - {
      for (assetPair <- Seq(wavesBtcPair, ethWavesPair, scriptAssetsPair)) s"$assetPair" in {
        val aliceOrder1 = mkOrder(alice, assetPair, SELL, 1000.TN, 600000, 2 * matcherFee, feeAsset = assetPair.priceAsset)
        placeAndAwaitAtDex(aliceOrder1)

        dex1.api.orderBook(assetPair).asks shouldBe List(LevelResponse(1000.TN, 600000))

        val bobOrder1 = mkOrder(bob, assetPair, BUY, 1000.TN, 800000, 3 * matcherFee, feeAsset = assetPair.priceAsset)
        placeAndAwaitAtDex(bobOrder1, OrderStatus.Filled)

        val aliceOrder2 = mkOrder(alice, assetPair, BUY, 1000.TN, 700000, 3 * matcherFee, feeAsset = assetPair.priceAsset)
        placeAndAwaitAtDex(aliceOrder2)
        dex1.api.orderBook(assetPair).bids shouldBe List(LevelResponse(1000.TN, 700000))

        val bobOrder2 = mkOrder(bob, assetPair, SELL, 1000.TN, 600000, 2 * matcherFee, feeAsset = assetPair.priceAsset)
        placeAndAwaitAtDex(bobOrder2, OrderStatus.Filled)
        waitForOrdersAtNode(aliceOrder1, aliceOrder2)

        cancelAll(alice, bob)
      }

      s"$wavesUsdPair" in {
        val bobOrder1 = mkOrder(bob, wavesUsdPair, SELL, 1000.TN, 600, 600, feeAsset = wavesUsdPair.priceAsset)
        placeAndAwaitAtDex(bobOrder1)

        dex1.api.orderBook(wavesUsdPair).asks shouldBe List(LevelResponse(1000.TN, 600))

        val aliceOrder1 = mkOrder(alice, wavesUsdPair, BUY, 1000.TN, 800, 3 * 300, feeAsset = wavesUsdPair.priceAsset)
        placeAndAwaitAtDex(aliceOrder1, OrderStatus.Filled)

        val aliceOrder2 = mkOrder(alice, wavesUsdPair, BUY, 1000.TN, 700, 3 * 300, feeAsset = wavesUsdPair.priceAsset)
        placeAndAwaitAtDex(aliceOrder2)
        dex1.api.orderBook(wavesUsdPair).bids shouldBe List(LevelResponse(1000.TN, 700))

        val bobOrder2 = mkOrder(bob, wavesUsdPair, SELL, 1000.TN, 600, 2 * 300, feeAsset = wavesUsdPair.priceAsset)
        placeAndAwaitAtDex(bobOrder2, OrderStatus.Filled)
        waitForOrdersAtNode(bobOrder1, aliceOrder1, aliceOrder2, bobOrder2)

        cancelAll(alice, bob)
      }
    }

    "out of deviation bounds" - {
      for (assetPair <- Seq(wavesBtcPair, ethWavesPair, scriptAssetsPair)) s"$assetPair" in {
        val bestAskOrder = mkOrder(alice, assetPair, SELL, 1000.TN, 600000, 2 * matcherFee, feeAsset = assetPair.priceAsset)
        placeAndAwaitAtDex(bestAskOrder)

        dex1.api.orderBook(assetPair).asks shouldBe List(LevelResponse(1000.TN, 600000))

        dex1.api.tryPlace(mkOrder(bob, assetPair, BUY, 1000.TN, 300000, 359999, feeAsset = assetPair.priceAsset)) should failWith(
          9441551, // DeviantOrderMatcherFee
          feeIsOutOfDeviationBounds("0.00359999", assetPair.priceAssetStr, BUY)
        )

        dex1.api.cancel(alice, bestAskOrder)

        val bestBidOrder = mkOrder(bob, assetPair, BUY, 1000.TN, 1200000, 4 * matcherFee, feeAsset = assetPair.priceAsset)
        placeAndAwaitAtDex(bestBidOrder)

        dex1.api.orderBook(assetPair).bids shouldBe List(LevelResponse(1000.TN, 1200000))

        dex1.api.tryPlace(mkOrder(alice, assetPair, SELL, 1000.TN, 600000, 719999, feeAsset = assetPair.priceAsset)) should failWith(
          9441551, // DeviantOrderMatcherFee
          feeIsOutOfDeviationBounds("0.00719999", assetPair.priceAssetStr, SELL)
        )

        cancelAll(alice, bob)
      }

      s"$wavesUsdPair" in {
        val bestAskOrder = mkOrder(bob, wavesUsdPair, SELL, 1000.TN, 600, 2 * 300, feeAsset = wavesUsdPair.priceAsset)
        placeAndAwaitAtDex(bestAskOrder)

        dex1.api.orderBook(wavesUsdPair).asks shouldBe List(LevelResponse(1000.TN, 600))

        dex1.api.tryPlace(mkOrder(alice, wavesUsdPair, BUY, 1000.TN, 300, 359, feeAsset = wavesUsdPair.priceAsset)) should failWith(
          9441551, // DeviantOrderMatcherFee
          feeIsOutOfDeviationBounds("3.59", wavesUsdPair.priceAssetStr, BUY)
        )

        dex1.api.cancel(bob, bestAskOrder)

        val bestBidOrder = mkOrder(alice, wavesUsdPair, BUY, 1000.TN, 1200, 4 * 300, feeAsset = wavesUsdPair.priceAsset)
        placeAndAwaitAtDex(bestBidOrder)

        dex1.api.orderBook(wavesUsdPair).bids shouldBe List(LevelResponse(1000.TN, 1200))

        dex1.api.tryPlace(mkOrder(bob, wavesUsdPair, SELL, 1000.TN, 600, 719, feeAsset = wavesUsdPair.priceAsset)) should failWith(
          9441551, // DeviantOrderMatcherFee
          feeIsOutOfDeviationBounds("7.19", wavesUsdPair.priceAssetStr, SELL)
        )

        cancelAll(alice, bob)
      }
    }
  }

  private def cancelAll(xs: KeyPair*): Unit         = xs.foreach(dex1.api.cancelAll(_))
  private def waitForOrdersAtNode(xs: Order*): Unit = xs.foreach(waitForOrderAtNode(_))
}
