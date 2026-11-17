package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 過繁茂のバイオームにはカエルがいない(2026-11-17 軽量化。ユーザー決定「過繁茂では湧かない」)。
 *
 * <p>測った値: F7 の前線(本物の写し・台帳 50 万チャンク)にカエルが 41 匹いて、バニラの跳ぶ先探し({@code LongJumpToRandomPos})で
 * サーバーの 1 tick の約 13%(1 匹 約 0.14 ms)を使っていた。前線のカエルはチャンクを作ったときのバニラの湿地のものが、
 * バイオームが過繁茂に変わった後も残っていたもの。
 *
 * <ul>
 *     <li>自然に出てくるカエル・オタマジャクシ(自然湧き・スポナー・構造物)は、過繁茂の中なら出さない。
 *     プレイヤーが出した分(スポーンエッグ・命令・バケツ)は出す。</li>
 *     <li>既にいるカエル(セーブから読み込まれたもの・繁茂から歩いて入ってきたもの)は、過繁茂の中にいて、
 *     まわり {@link #UNSEEN_RANGE} ブロックにプレイヤーがいないときだけ静かに消す(目の前で消えないように)。
 *     ※ ここはユーザーの決定(「湧かない」)より一歩広い。ユーザーに確かめ中(11-17)。</li>
 *     <li>名札・リードの付いたカエルと、植物側に変わったカエルは残す(胞子感染の途中のものは消す)。</li>
 * </ul>
 * 距離 48 と間隔 100 tick は仮決め。
 * 繁茂の段階(過繁茂の手前)では今までどおり。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OvergrownFrogs {

    /** 消す前に見るプレイヤーとの距離(遠くの Mob の思考の間引きと同じ 48)。 */
    static final double UNSEEN_RANGE = 48.0;
    /** 既にいるカエルを見直す間隔(tick)。 */
    static final int CHECK_EVERY = 100;

    private OvergrownFrogs() {
    }

    private static boolean isFrogKind(Entity e) {
        return e instanceof Frog || e instanceof Tadpole;
    }

    /**
     * 消さない: 名札・リード付き・植物側に変わった(汎用の感染体)カエル。
     * 胞子感染の途中({@code MobInfection#isInfected})は数えない —— 過繁茂の中のカエルはほぼ全部が感染中で、数えると 1 匹も減らなかった(11-17 に確かめた)。
     */
    private static boolean kept(Entity e) {
        if (e.hasCustomName() || (e instanceof net.minecraft.world.entity.Leashable l && l.isLeashed())) return true;
        if (com.yayuy.overgrownvegetation.infect.GenericInfection.isTurned(e)) return true;
        return e instanceof net.minecraft.world.entity.LivingEntity le && com.yayuy.overgrownvegetation.spore.SporeExposureTracker.isPlantSide(le);
    }

    static boolean inOvergrown(ServerLevel level, Entity e) {
        return ModBiomes.isFullyOvergrown(level.getBiome(e.blockPosition()));
    }

    static boolean inOvergrown(ServerLevel level, double x, double y, double z) {
        return ModBiomes.isFullyOvergrown(level.getBiome(net.minecraft.core.BlockPos.containing(x, y, z)));
    }

    /**
     * 自然に出てくる分(自然湧き・スポナー・構造物など。オタマジャクシからの成長はこの出来事を通らないので、見ていない所で消す方で減る)だけを止める。
     * プレイヤーが出した分(スポーンエッグ・命令・バケツ・ディスペンサー)は止めない。
     */
    @SubscribeEvent
    public static void onFinalizeSpawn(net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent event) {
        if (!isFrogKind(event.getEntity()) || !(event.getLevel().getLevel() instanceof ServerLevel level)) return;
        switch (event.getSpawnType()) {
            case SPAWN_EGG, COMMAND, BUCKET, DISPENSER, TRIGGERED -> {
                return;
            }
            default -> {
            }
        }
        if (inOvergrown(level, event.getX(), event.getY(), event.getZ())) {
            event.setSpawnCancelled(true);
        }
    }

    @SubscribeEvent
    public static void onTick(EntityTickEvent.Pre event) {
        Entity e = event.getEntity();
        if (!(e instanceof Frog) || (e.tickCount + e.getId()) % CHECK_EVERY != 0 || !(e.level() instanceof ServerLevel level) || kept(e)) {
            return;
        }
        if (!inOvergrown(level, e)) {
            return;
        }
        double r2 = UNSEEN_RANGE * UNSEEN_RANGE;
        for (Player p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(e) <= r2) {
                return;
            }
        }
        e.discard();
    }
}
