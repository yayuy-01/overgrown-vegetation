package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.world.entity.LivingEntity;

/**
 * <b>植物側の押し合いを間引く</b>(2026-11-18 大群のための軽量化 B の ②b。11-18 朝ユーザー決定で既定は入り)。
 *
 * <p>植物側の Mob は、自分からの押し合いの探し({@code pushEntities})を {@code push.intervalTicks}(perf.json、【仮】4)tick に 1 回にする。
 * 押し合いは「見つけた方が、自分と相手の両方を押す」作りなので:
 * <ul>
 *     <li>プレイヤー・ほかの Mob とは、相手の毎 tick の押し合いで今までどおり離れる</li>
 *     <li>植物側どうしは、両方が間引くので押す力が約 1/4 = 軽く離れ合うだけになる</li>
 *     <li>詰め込みすぎの窒息ダメージ(maxEntityCramming)の判定も同じ間隔になる</li>
 * </ul>
 * 入り切りは common toml の {@code plantPushThinEnabled}。
 */
public final class PlantPushThin {

    private PlantPushThin() {
    }

    /** この tick の押し合いを飛ばすか(サーバー側の植物側の Mob だけ)。 */
    public static boolean skip(LivingEntity e) {
        if (e.level().isClientSide || !OvergrownVegetationConfig.COMMON.plantPushThinEnabled.get()) {
            return false;
        }
        int interval = DesignData.perf().pushIntervalTicks;
        if (interval <= 1 || Math.floorMod(e.tickCount + e.getId(), interval) == 0) {
            return false;
        }
        return PlantSideRules.isPlantSide(e);
    }
}
