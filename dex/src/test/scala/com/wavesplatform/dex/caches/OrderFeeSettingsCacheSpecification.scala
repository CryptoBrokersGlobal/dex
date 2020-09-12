package com.wavesplatform.dex.caches

import com.wavesplatform.dex.MatcherSpecBase
import com.wavesplatform.dex.settings.AssetType
import com.wavesplatform.dex.settings.OrderFeeSettings.{DynamicSettings, OrderFeeSettings, PercentSettings}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class OrderFeeSettingsCacheSpecification extends AnyWordSpecLike with Matchers with MatcherSpecBase {

  "OrderFeeSettingsCache" should {

    "prevent matcher start if settings wasn't set" in {
      a[IllegalArgumentException] should be thrownBy new OrderFeeSettingsCache(Map.empty[Long, OrderFeeSettings])
    }

    "throw error if there is no settings for the given offset" in {
      a[IllegalStateException] should be thrownBy new OrderFeeSettingsCache(Map(100L -> DynamicSettings.symmetric(mkAssetId("TN"), 0.003.TN)))
        .getSettingsForOffset(1)
    }

    "correctly retrieve current fee settings" in {

      val settingsMap =
        Map(
          -1L   -> DynamicSettings.symmetric(mkAssetId("TN"), 0.003.TN),
          2L    -> DynamicSettings(mkAssetId("TN"), 0.001.TN, 0.005.TN),
          15L   -> DynamicSettings(mkAssetId("TN"), 0.002.TN, 0.004.TN),
          1000L -> PercentSettings(AssetType.AMOUNT, 0.005)
        )

      val ofsc = new OrderFeeSettingsCache(settingsMap)

      def check(offset: Long, current: OrderFeeSettings, actual: OrderFeeSettings): Unit = {
        ofsc.getSettingsForOffset(offset) should matchTo(current)
        ofsc.getSettingsForOffset(offset + 1) should matchTo(actual)
      }

      check(offset = -1L, current = DynamicSettings(mkAssetId("TN"), 0.003.TN, 0.003.TN), actual = DynamicSettings(mkAssetId("TN"), 0.003.TN, 0.003.TN))
      check(offset = 0L, current = DynamicSettings(mkAssetId("TN"), 0.003.TN, 0.003.TN), actual = DynamicSettings(mkAssetId("TN"), 0.003.TN, 0.003.TN))
      check(offset = 17L, current = DynamicSettings(mkAssetId("TN"), 0.002.TN, 0.004.TN), actual = DynamicSettings(mkAssetId("TN"), 0.002.TN, 0.004.TN))
      check(offset = 100L, current = DynamicSettings(mkAssetId("TN"), 0.002.TN, 0.004.TN), actual = DynamicSettings(mkAssetId("TN"), 0.002.TN, 0.004.TN))
      check(offset = 999L, current = DynamicSettings(mkAssetId("TN"), 0.002.TN, 0.004.TN), actual = PercentSettings(AssetType.AMOUNT, 0.005))
    }
  }
}
