package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.AquaticOvergrownMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.PathType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * <b>陸のグループ1〜10 は水を避ける</b>(ユーザー要望 2026-09-19:
 * 「可能なかぎり水を避ける行動は、他のグループ1〜10 の水生の mob 以外に共通する属性としてほしい」)。
 *
 * <p>それまでは種ごとのコンストラクタで思い思いに設定していて、揃っていなかった。
 * <ul>
 *     <li>グループ3・4 の土台({@code AbstractOvergrownPredatorEntity} /
 *     {@code AbstractOvergrownInvaderEntity} など)は {@code WATER = -1}(水に入らない)だけ設定。
 *     <b>汀({@code WATER_BORDER})は素通り</b>なので、水際ぎりぎりを歩く経路が平気で引かれていた。</li>
 *     <li>スカトゥリウムは {@code WATER_BORDER = 0} と<b>わざわざ避けないように</b>してあった。</li>
 *     <li><b>グループ1(過繁茂した◯◯ = バニラ Mob 由来)は何も設定していない</b> ——
 *     ゾンビなら {@code WATER = 8} なので、平気で水に入って渡っていた。</li>
 * </ul>
 * ここで一括して掛け直すので、<b>種ごとの設定を増やさなくても新しい Mob に自動で効く</b>。
 *
 * <h2>掛けるもの</h2>
 * <ul>
 *     <li>{@code WATER = -1} —— 水のマスを経路に使わない(= 自分から入らない)。</li>
 *     <li>{@code WATER_BORDER = landMobWaterBorderMalus}(既定 16)—— 水に<b>面した</b>マスの
 *     通行コスト。バニラ既定は 8 で、これは「通れるが高くつく」重み。大きくするほど
 *     <b>岸から離れた道を選ぶ</b>ようになる。通れなくなるわけではないので、
 *     岬や桟橋のような「そこしか道がない」場所では今までどおり通る。</li>
 * </ul>
 *
 * <h2>掛けない相手</h2>
 * <ul>
 *     <li>グループに属さない Mob(バニラ・他 MOD)。</li>
 *     <li>{@link AquaticOvergrownMob} —— 水生種。</li>
 *     <li><b>自分で {@code WATER = 0} を宣言している種</b> —— メガラクネのような水陸両用。
 *     「水は平気」と明示している種の意思をここで上書きしない。</li>
 * </ul>
 *
 * <p>飛行種(ヴェロフィル・メガネウラ)にも掛かるが、飛行の経路探索は水の枡目を陸と同じに
 * 扱うので実害はない(もともと {@code WATER = -1} を設定してある種もある)。
 *
 * <h2>水を跳び越えるのは別の話</h2>
 * ここでやるのは「<b>経路として選ばない</b>」だけ。どうしても渡らなければ相手に届かないときに
 * 跳んで渡るのは {@code InvaderWaterLeapGoal} の仕事で、そちらは
 * <b>陸路が引けなくなって初めて</b>動く。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LandMobWaterAvoidance {

    @SubscribeEvent
    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (!OvergrownVegetationConfig.COMMON.landMobWaterAvoidance.get()) {
            return;
        }
        if (MobGroup.of(mob) == null || mob instanceof AquaticOvergrownMob) {
            return;
        }
        if (mob.getPathfindingMalus(PathType.WATER) == 0.0F) {
            // 「水は平気」と自分で宣言している種(水陸両用)。触らない。
            return;
        }
        mob.setPathfindingMalus(PathType.WATER, -1.0F);
        mob.setPathfindingMalus(PathType.WATER_BORDER,
                OvergrownVegetationConfig.COMMON.landMobWaterBorderMalus.get().floatValue());
    }

    private LandMobWaterAvoidance() {
    }
}
