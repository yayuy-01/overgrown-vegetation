package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.RaidScaleTable;
import com.yayuy.overgrownvegetation.director.RaidScales;
import com.yayuy.overgrownvegetation.director.RaidScales.RaidScale;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

/**
 * 設定のページの襲撃の項目(11-19 第 2 便)を、襲撃の規模の口 {@link RaidScales.Knobs}(raid-scale が作った)へ写す。
 * Knobs は JVM に 1 つの static なので、<b>サーバーが始まったとき・設定を変えたとき</b>にこのワールドの値で書き直し、止まったら既定へ戻す。
 *
 * <ul>
 *     <li>襲撃があるか → {@code raidsEnabled}</li>
 *     <li>頻度(%) → {@code intervalFactor} = 1 ÷ 頻度(200% なら間が半分)</li>
 *     <li>規模ごとの数(%) → {@code countFactor}</li>
 *     <li>中の同時の上限 → {@code maxAlive[中]}(大の同時の上限は {@link DifficultySetting#LARGE_RAID_MAX_ALIVE} = LargeRaids.maxAlive)</li>
 *     <li>規模ごとの 1 回の合計の上限 → {@code maxTotal}(画面の 0 = 上限なし → Knobs の −1)</li>
 * </ul>
 * 前触れ・壁を壊すかは Knobs に口が無いので、読む所({@code RaidScales#omenDays}・{@code BreachGoal})が直に読む。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class RaidSettings {

    private RaidSettings() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        push(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        RaidScales.Knobs.reset();
    }

    /** このワールドの値を Knobs へ書く。 */
    public static void push(MinecraftServer server) {
        WorldDifficultyData d = WorldDifficultyData.get(server);
        RaidScales.Knobs.raidsEnabled = d.value(DifficultySetting.RAIDS) >= 0.5D;
        RaidScales.Knobs.intervalFactor = 1.0D / Math.max(0.01D, d.value(DifficultySetting.RAID_FREQUENCY));
        RaidScales.Knobs.countFactor[RaidScale.SMALL.ordinal()] = d.value(DifficultySetting.RAID_COUNT_SMALL);
        RaidScales.Knobs.countFactor[RaidScale.MEDIUM.ordinal()] = d.value(DifficultySetting.RAID_COUNT_MEDIUM);
        RaidScales.Knobs.countFactor[RaidScale.LARGE.ordinal()] = d.value(DifficultySetting.RAID_COUNT_LARGE);
        RaidScales.Knobs.maxAlive[RaidScale.MEDIUM.ordinal()] = (int) Math.round(d.value(DifficultySetting.RAID_MAX_ALIVE_MEDIUM));
        RaidScales.Knobs.maxTotal[RaidScale.SMALL.ordinal()] = total(d.value(DifficultySetting.RAID_MAX_TOTAL_SMALL));
        RaidScales.Knobs.maxTotal[RaidScale.MEDIUM.ordinal()] = total(d.value(DifficultySetting.RAID_MAX_TOTAL_MEDIUM));
        RaidScales.Knobs.maxTotal[RaidScale.LARGE.ordinal()] = total(d.value(DifficultySetting.RAID_MAX_TOTAL_LARGE));
    }

    /** 画面の合計の上限(0 = 上限なし)を Knobs の形(−1 = 上限なし)へ。 */
    private static int total(double v) {
        long n = Math.round(v);
        return n <= 0 ? -1 : (int) Math.min(Integer.MAX_VALUE / 4, n);
    }

    /** 設計データのその段の同時の上限(無ければ {@code fallback})。設定のページの既定値。 */
    public static int designMaxAlive(RaidScale s, int fallback) {
        RaidScaleTable.Tier t = RaidScales.tier(s);
        return t != null && t.maxAlive() > 0 ? t.maxAlive() : fallback;
    }
}
