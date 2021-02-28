package com.wavesplatform.dex.model

import com.wavesplatform.dex.caches.OrderFeeSettingsCache
import com.wavesplatform.dex.domain.order.Order
import com.wavesplatform.dex.domain.utils.EitherExt2
import com.wavesplatform.dex.model.AcceptedOrder.partialFee
import com.wavesplatform.dex.model.OrderValidator.multiplyFeeByDouble
import com.wavesplatform.dex.settings.AssetType
import com.wavesplatform.dex.settings.OrderFeeSettings.{DynamicSettings, FixedSettings, OrderFeeSettings, PercentSettings}

object Fee {
  def getMakerTakerFeeByOffset(ofsc: OrderFeeSettingsCache)(offset: Long)(s: AcceptedOrder, c: LimitOrder): (Long, Long) = {
    getMakerTakerFee(ofsc getSettingsForOffset offset)(s, c)
  }

  def getMakerTakerFee(ofs: => OrderFeeSettings)(s: AcceptedOrder, c: LimitOrder): (Long, Long) = {
    val executedPrice  = c.price
    val executedAmount = AcceptedOrder.executedAmount(s, c)

    def absoluteFee(totalCounterFee: Long, totalSubmittedFee: Long): (Long, Long) = {
      val counterExecutedFee   = partialFee(totalCounterFee, c.order.amount, executedAmount)
      val submittedExecutedFee = partialFee(totalSubmittedFee, s.order.amount, executedAmount)

      (
        if (c.isFirstMatch && c.order.version >= 3) counterExecutedFee max 1L else counterExecutedFee,
        if (s.isFirstMatch && s.order.version >= 3) submittedExecutedFee max 1L else submittedExecutedFee
      )
    }

    ofs match {
      case PercentSettings(assetType, _) =>
        val (buy, sell) = Order.splitByType(s.order, c.order)

        val (buyAmt, sellAmt) = assetType match {
          case AssetType.AMOUNT    => buy.getReceiveAmount _ -> sell.getSpendAmount _
          case AssetType.PRICE     => buy.getSpendAmount _   -> sell.getReceiveAmount _
          case AssetType.RECEIVING => buy.getReceiveAmount _ -> sell.getReceiveAmount _
          case AssetType.SPENDING  => buy.getSpendAmount _   -> sell.getSpendAmount _
        }

        def buySellFee(buyAmount: Long, buyPrice: Long, sellAmount: Long, sellPrice: Long): (Long, Long) =
          buyAmt(buyAmount, buyPrice).explicitGet() -> sellAmt(sellAmount, sellPrice).explicitGet()

        val (buyAmountExecuted, sellAmountExecuted) = buySellFee(executedAmount, executedPrice, executedAmount, executedPrice)
        val (buyAmountTotal, sellAmountTotal)       = buySellFee(buy.amount, executedPrice, sell.amount, executedPrice)

        val buyExecutedFee  = partialFee(buy.matcherFee, buyAmountTotal, buyAmountExecuted)
        val sellExecutedFee = partialFee(sell.matcherFee, sellAmountTotal, sellAmountExecuted)

        if (c.isBuyOrder) (buyExecutedFee, sellExecutedFee)
        else (sellExecutedFee, buyExecutedFee)

      case settings: DynamicSettings =>
        val fee = absoluteFee(
          totalCounterFee = multiplyFeeByDouble(c.matcherFee, settings.makerRatio),
          totalSubmittedFee = multiplyFeeByDouble(s.matcherFee, settings.takerRatio)
        )

        println("Fee.scala-59: OrderFeeSettings: " + ofs)
        println("Fee.scala-60: AcceptedOrder: " + s)
        println("Fee.scala-61: LimitOrder: " + c)
        println("Fee.scala-62: counterFee: " + c.matcherFee + " - makerRatio: " + settings.makerRatio + " - totalCounterFee: " + multiplyFeeByDouble(c.matcherFee, settings.makerRatio))
        println("Fee.scala-63: submittedFee: " + s.matcherFee + " - takerRatio: " + settings.takerRatio + " - totalSubmittedFee: " + multiplyFeeByDouble(s.matcherFee, settings.takerRatio))
        println("Fee.scala-64: absoluteFee: " + fee)

        absoluteFee(
          totalCounterFee = multiplyFeeByDouble(c.matcherFee, settings.makerRatio),
          totalSubmittedFee = multiplyFeeByDouble(s.matcherFee, settings.takerRatio)
        )

      case _: FixedSettings =>
        absoluteFee(
          totalCounterFee = c.matcherFee,
          totalSubmittedFee = s.matcherFee
        )
    }
  }
}
