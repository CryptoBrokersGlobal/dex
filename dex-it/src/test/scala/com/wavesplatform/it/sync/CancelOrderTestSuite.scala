package com.wavesplatform.it.sync

import java.util.concurrent.ThreadLocalRandom

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.dex.domain.account.KeyPair
import com.wavesplatform.dex.domain.asset.Asset.{IssuedAsset, Waves}
import com.wavesplatform.dex.domain.asset.AssetPair
import com.wavesplatform.dex.domain.order.{Order, OrderType}
import com.wavesplatform.dex.it.api.responses.dex.OrderStatus
import com.wavesplatform.dex.it.time.GlobalTimer
import com.wavesplatform.dex.it.time.GlobalTimer.TimerOpsImplicits
import com.wavesplatform.dex.util.FutureOps.Implicits
import com.wavesplatform.it.MatcherSuiteBase
import com.wavesplatform.wavesj.Transfer

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class CancelOrderTestSuite extends MatcherSuiteBase {

  override protected def dexInitialSuiteConfig: Config = ConfigFactory.parseString(s"""TN.dex.price-assets = [ "$UsdId", "$BtcId", "TN" ]""")

  private var knownAccounts = List(alice, bob)

  override protected def beforeAll(): Unit = {
    wavesNode1.start()
    broadcastAndAwait(IssueUsdTx, IssueBtcTx)
    dex1.start()
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()

    knownAccounts.foreach(dex1.api.cancelAll(_))
    eventually {
      val orderBook = dex1.api.orderBook(wavesUsdPair)
      orderBook.bids shouldBe empty
      orderBook.asks shouldBe empty
    }
  }

  "Order can be canceled" - {
    "by sender" in {
      val order = mkBobOrder
      placeAndAwaitAtDex(order)

      dex1.api.cancel(bob, order)
      dex1.api.waitForOrderStatus(order, OrderStatus.Cancelled)

      dex1.api.orderHistoryByPair(bob, wavesUsdPair).collectFirst {
        case o if o.id == order.id() => o.status shouldEqual OrderStatus.Cancelled
      }
    }

    "array of orders could be cancelled with API key" in {
      val acc = createAccountWithBalance(100.TN -> Waves)
      knownAccounts = acc :: knownAccounts

      val ids = for { i <- 1 to 10 } yield {
        val o = mkOrder(acc, wavesUsdPair, OrderType.SELL, i.TN, 100 + i)
        placeAndAwaitAtDex(o)
        o.id.value
      }

      dex1.api.cancelAllByIdsWithApiKey(acc, ids.toSet)

      ids.map(dex1.api.waitForOrderStatus(wavesUsdPair, _, OrderStatus.Cancelled))
    }

    "only owners orders should be cancelled with API key if one of them from another owner" in {
      val acc = createAccountWithBalance(100.TN -> Waves)
      knownAccounts = acc :: knownAccounts

      val o = mkOrder(alice, wavesUsdPair, OrderType.SELL, 1.TN, 100)
      placeAndAwaitAtDex(o)

      val ids = for { i <- 1 to 10 } yield {
        val o = mkOrder(acc, wavesUsdPair, OrderType.SELL, i.TN, 100 + i)
        placeAndAwaitAtDex(o)
        o.id()
      }

      val allIds = ids :+ o.id()

      dex1.api.cancelAllByIdsWithApiKey(acc, allIds.toSet)

      dex1.api.waitForOrderStatus(o, OrderStatus.Accepted)
      ids.map(dex1.api.waitForOrderStatus(wavesUsdPair, _, OrderStatus.Cancelled))

      dex1.api.cancel(alice, o)
    }

    "with API key" - {
      "and without X-User-Public-Key" in {
        val order = mkBobOrder
        placeAndAwaitAtDex(order)

        dex1.api.cancelWithApiKey(order)
        dex1.api.waitForOrderStatus(order, OrderStatus.Cancelled)

        dex1.api.orderHistory(bob).find(_.id == order.id()).get.status shouldBe OrderStatus.Cancelled

        dex1.api.orderHistoryByPair(bob, wavesUsdPair).find(_.id == order.id()).get.status shouldBe OrderStatus.Cancelled
      }

      "and with a valid X-User-Public-Key" in {
        val order = mkBobOrder
        placeAndAwaitAtDex(order)

        dex1.api.cancelWithApiKey(order, Some(order.senderPublicKey))
        dex1.api.waitForOrderStatus(order, OrderStatus.Cancelled)

        dex1.api.orderHistory(bob).find(_.id == order.id()).get.status shouldBe OrderStatus.Cancelled

        dex1.api.orderHistoryByPair(bob, wavesUsdPair).find(_.id == order.id()).get.status shouldBe OrderStatus.Cancelled
      }

      "and with an invalid X-User-Public-Key" in {
        val order = mkBobOrder
        placeAndAwaitAtDex(order)

        dex1.api.tryCancelWithApiKey(order.id(), Some(alice.publicKey)) should failWith(9437193) // OrderNotFound
        dex1.api.cancelWithApiKey(order)
        dex1.api.waitForOrderStatus(order, OrderStatus.Cancelled)
      }
    }
  }

  "Cancel is rejected" - {
    "when order already cancelled" in {
      val order = mkOrder(bob, wavesUsdPair, OrderType.SELL, 100.TN, 800)
      placeAndAwaitAtDex(order)
      cancelAndAwait(bob, order)
      dex1.api.tryCancel(bob, order) should failWith(9437194) // OrderCanceled
    }

    "when request sender is not the sender of and order" in {
      val order = mkBobOrder
      placeAndAwaitAtDex(order)

      val r = dex1.api.tryCancel(matcher, order)
      r shouldBe 'left
      r.left.get.error shouldBe 9437193 // OrderNotFound

      // Cleanup
      dex1.api.cancel(bob, order)
      dex1.api.waitForOrderStatus(order, OrderStatus.Cancelled)
    }
  }

  "Batch cancel works for" - {
    "all orders placed by an address" in {
      val orders = mkBobOrders(wavesUsdPair) ::: mkBobOrders(wavesBtcPair)
      orders.foreach(dex1.api.place)
      orders.foreach(dex1.api.waitForOrderStatus(_, OrderStatus.Accepted))

      dex1.api.cancelAll(bob)
      orders.foreach(dex1.api.waitForOrderStatus(_, OrderStatus.Cancelled))
    }

    "a pair" in {
      val wavesUsdOrders = mkBobOrders(wavesUsdPair)
      val wavesBtcOrders = mkBobOrders(wavesBtcPair)
      val orders         = wavesUsdOrders ::: wavesBtcOrders
      orders.foreach(dex1.api.place)
      orders.foreach(dex1.api.waitForOrderStatus(_, OrderStatus.Accepted))

      dex1.api.cancelAllByPair(bob, wavesBtcPair)

      wavesBtcOrders.foreach(dex1.api.waitForOrderStatus(_, OrderStatus.Cancelled))
      wavesUsdOrders.foreach(dex1.api.waitForOrderStatus(_, OrderStatus.Accepted))

      dex1.api.cancelAllByPair(bob, wavesUsdPair)
      wavesUsdOrders.foreach(dex1.api.waitForOrderStatus(_, OrderStatus.Cancelled))
    }
  }

  "Batch cancel by id" - {
    "works for specified orders placed by an address" in {
      val orders = mkBobOrders(wavesUsdPair) ::: mkBobOrders(wavesBtcPair)
      orders.foreach(dex1.api.place)
      orders.foreach(dex1.api.waitForOrderStatus(_, OrderStatus.Accepted))

      dex1.api.cancelAllByIdsWithApiKey(bob, orders.map(_.id()).toSet)

      orders.foreach(dex1.api.waitForOrderStatus(_, OrderStatus.Cancelled))
    }

    // DEX-548
    "returns a rejected orders if an owner is invalid" ignore {
      val orders = mkBobOrders(wavesUsdPair)
      orders.foreach(dex1.api.place)
      orders.foreach(dex1.api.waitForOrderStatus(_, OrderStatus.Accepted))

      dex1.api.cancelAllByIdsWithApiKey(alice, orders.map(_.id()).toSet)
      // here is validation
    }
  }

  "Auto cancel" - {
    "wrong cancel when executing a big order by small amount" in {
      val amount             = 835.85722414.TN
      val traderTotalBalance = amount + matcherFee
      val trader             = createAccountWithBalance(traderTotalBalance -> Waves)

      eventually {
        dex1.api.tradableBalance(trader, wavesUsdPair).getOrElse(Waves, 0L) shouldBe traderTotalBalance
      }

      knownAccounts = trader :: knownAccounts

      // Spending all assets
      val counterOrder   = mkOrderDP(trader, wavesUsdPair, OrderType.SELL, amount, 9032, version = 3)
      val submittedOrder = mkOrderDP(alice, wavesUsdPair, OrderType.BUY, 0.0001.TN, 9097)

      placeAndAwaitAtDex(counterOrder)
      placeAndAwaitAtNode(submittedOrder)

      dex1.api.orderStatus(counterOrder).status shouldBe OrderStatus.PartiallyFilled
      dex1.api.orderStatus(submittedOrder).status shouldBe OrderStatus.Filled
    }

    "wrong cancel when match on all coins" in {
      val accounts = (1 to 30).map(_ => createAccountWithBalance(issueFee -> Waves))
      knownAccounts = knownAccounts ++ accounts

      val oneOrderAmount = 10000
      val orderPrice     = 3000000000000L

      broadcastAndAwait(mkMassTransfer(alice, Waves, accounts.map(x => new Transfer(x.toAddress, issueFee + matcherFee)).toList))

      val accountsAndAssets = accounts.zipWithIndex.map {
        case (account, i) => account -> mkIssue(account, s"WowSoMuchCoin-$i", quantity = oneOrderAmount, decimals = 2)
      }.toMap
      broadcastAndAwait(accountsAndAssets.values.toSeq: _*)

      val sells = accountsAndAssets.map {
        case (account, asset) =>
          val issuedAsset = IssuedAsset(asset.getId)
          val assetPair   = AssetPair(issuedAsset, Waves)
          eventually {
            dex1.api.tradableBalance(account, assetPair).getOrElse(issuedAsset, 0L) shouldBe oneOrderAmount
          }
          mkOrder(account, assetPair, OrderType.SELL, oneOrderAmount, orderPrice)
      }

      sells.foreach(placeAndAwaitAtDex(_))

      val buyOrders = for {
        (_, asset) <- accountsAndAssets
        i          <- 1 to 10
      } yield
        mkOrder(alice,
                AssetPair(IssuedAsset(asset.getId), Waves),
                OrderType.BUY,
                amount = oneOrderAmount / 10,
                price = orderPrice,
                ttl = 30.days - i.seconds) // to make different orders

      Await.ready(
        {
          Future.traverse(buyOrders.groupBy(_.assetPair).values) { orders =>
            Future.inSeries(orders)(dex1.asyncApi.place(_).flatMap { _ =>
              val wait = ThreadLocalRandom.current().nextInt(100, 1200).millis
              GlobalTimer.instance.sleep(wait)
            })
          }
        },
        5.minutes
      )

      val statuses = sells.map { order =>
        order -> dex1.api.waitForOrder(order)(r => r.status == OrderStatus.Cancelled || r.status == OrderStatus.Filled).status
      }

      statuses.foreach {
        case (order, status) =>
          withClue(s"${order.id()}: ") {
            status shouldBe OrderStatus.Filled
          }
      }
    }
  }

  "After cancelAllOrders (200) all of them should be cancelled" in {
    val totalAccounts    = 20
    val ordersPerAccount = 200

    val accounts = (1 to totalAccounts).map(_ => createAccountWithBalance(1000.TN -> Waves)).toList
    knownAccounts = knownAccounts ++ accounts

    accounts.foreach { account =>
      eventually {
        dex1.api.tradableBalance(account, wavesUsdPair).getOrElse(Waves, 0L) shouldBe 1000.TN
      }
    }

    def place(account: KeyPair, startPrice: Long, numOrders: Int): Future[Seq[Order.Id]] = {
      val orders = (1 to numOrders).map { i =>
        mkOrder(account, wavesUsdPair, OrderType.SELL, 1.TN, startPrice + i) // version 2
      }

      val futures = orders.map(dex1.asyncApi.place)
      Future.sequence(futures).map(_ => orders.map(_.id()))
    }

    Await.ready(
      for {
        orderIds <- {
          val pairs = accounts.zipWithIndex.map { case (account, i) => (account, (i + 1) * 1000) }
          Future.inSeries(pairs)(Function.tupled(place(_, _, ordersPerAccount))).map(_.flatten)
        }
        _ <- Future.traverse(accounts) { account =>
          dex1.asyncApi.orderHistoryByPair(account, wavesUsdPair).map { orders =>
            withClue(s"account $account: ") {
              orders.size shouldBe ordersPerAccount
            }
          }
        }
        _         <- Future.traverse(accounts)(dex1.asyncApi.cancelAll(_))
        _         <- Future.inSeries(orderIds)(dex1.asyncApi.waitForOrderStatus(wavesUsdPair, _, OrderStatus.Cancelled))
        orderBook <- dex1.asyncApi.orderBook(wavesUsdPair)
      } yield {
        orderBook.bids should be(empty)
        orderBook.asks should be(empty)
      },
      5.minutes
    )
  }

  private def mkBobOrder                        = mkOrderDP(bob, wavesUsdPair, OrderType.SELL, 100.TN, 8)
  private def mkBobOrders(assetPair: AssetPair) = (1 to 5).map(i => mkOrder(bob, assetPair, OrderType.SELL, 100.TN + i, 400)).toList
}
