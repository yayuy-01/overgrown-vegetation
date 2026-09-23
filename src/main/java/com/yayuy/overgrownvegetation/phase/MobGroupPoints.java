package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * 汚染側(グループ1〜10)の生き死にでフェーズポイントを加算する(2026-09-13 のフェーズ再編)。
 *
 * <ul>
 *     <li><b>グループ1〜10の Mob が死んだ</b> → {@code points.perGroupMobDeath}(既定 1)。
 *     倒したのがプレイヤーでも、別の Mob でも、落下や溶岩でも同じ。汚染側が減ること自体を数えている。</li>
 *     <li><b>グループ1〜10の Mob が他の Mob を倒した</b> → {@code points.perGroupMobKill}(既定 1)。
 *     グループ3がグループ2を捕食したぶん({@code predation.json} の食物連鎖)も含む。</li>
 * </ul>
 *
 * <p>グループ同士の殺害は<b>両方</b>成立するので合計 2 点入る(死んだぶん + 倒したぶん)。
 * 倒した相手がプレイヤーのときは「他の Mob を倒した」に数えない。
 *
 * <p>矢や胞子爆弾のような間接攻撃でも、{@code getSource().getEntity()} が撃った本人を返すので
 * 撃った側のグループで判定される。
 *
 * <p>2026-09-13 より前は「プレイヤーが感染 Mob を倒したら 15 点」({@code InfectionKillPoints})と
 * 「汚染シダが湧いたら 100 点」({@code BlightFernSpawnPoints})だったが、どちらもこのクラスと
 * 過繁茂チャンク数({@link PhaseData#addOvergrownChunks})へ置き換えて削除した。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MobGroupPoints {

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            return;
        }

        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        PhaseTable phases = com.yayuy.overgrownvegetation.data.DesignData.phases();
        long points = 0L;

        if (MobGroup.of(victim) != null) {
            points += phases.pointsPerGroupMobDeath();
        }
        if (!(victim instanceof Player)
                && event.getSource().getEntity() instanceof LivingEntity killer
                && killer != victim
                && MobGroup.of(killer) != null) {
            points += phases.pointsPerGroupMobKill();
        }

        if (points > 0L) {
            PhaseData.get(serverLevel).addPoints(serverLevel.getServer().overworld(), points);
        }
    }

    private MobGroupPoints() {
    }
}
