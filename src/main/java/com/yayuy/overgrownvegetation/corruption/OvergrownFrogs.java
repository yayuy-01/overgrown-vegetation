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
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 過繁茂のバイオームにはカエルがいない(2026-11-17 軽量化。ユーザー決定「過繁茂では湧かない」)。
 *
 * <p>測った値: F7 の前線(本物の写し・台帳 50 万チャンク)にカエルが 41 匹いて、バニラの跳ぶ先探し({@code LongJumpToRandomPos})で
 * サーバーの 1 tick の約 13%(1 匹 約 0.14 ms)を使っていた。前線のカエルはチャンクを作ったときのバニラの湿地のものが、
 * バイオームが過繁茂に変わった後も残っていたもの。
 *
 * <ul>
 *     <li>新しく加わるカエル・オタマジャクシ(自然湧き・繁殖・オタマジャクシの成長・スポナー)は、過繁茂の中なら加えない。</li>
 *     <li>既にいるカエル(セーブから読み込まれたもの・繁茂から歩いて入ってきたもの)は、過繁茂の中にいて、
 *     まわり {@link #UNSEEN_RANGE} ブロックにプレイヤーがいないときだけ静かに消す(目の前で消えないように)。</li>
 *     <li>名札・リードの付いたカエルは残す。</li>
 * </ul>
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

    private static boolean kept(Entity e) {
        return e.hasCustomName() || (e instanceof net.minecraft.world.entity.Leashable l && l.isLeashed());
    }

    static boolean inOvergrown(ServerLevel level, Entity e) {
        return ModBiomes.isFullyOvergrown(level.getBiome(e.blockPosition()));
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (event.loadedFromDisk() || !isFrogKind(e) || !(event.getLevel() instanceof ServerLevel level) || kept(e)) {
            return;
        }
        if (inOvergrown(level, e)) {
            event.setCanceled(true);
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
