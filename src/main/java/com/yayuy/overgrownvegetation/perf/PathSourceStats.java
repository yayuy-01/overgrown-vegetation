package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack;
import com.yayuy.overgrownvegetation.spawn.PlantMarch;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * 経路探索の出どころと失敗の割合(2026-11-18 大群の軽量化 A・流れの地図の下調べ)。{@link HordeMeter} が数えている間だけ、
 * 探索した Mob の状態で 4 つに分けて、回数・時間・届かなかった数を数え、20 秒ごとにログへ 1 行書く。
 * <ul>
 *     <li>後続: 外の群れの後続(先導役でない)で、相手がいないか遠い</li>
 *     <li>行進: 拠点へ歩かされている({@link PlantMarch#active})</li>
 *     <li>相手: 標的がいる(種ごとの狩り)</li>
 *     <li>ほか</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class PathSourceStats {

    private static final Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    static final String[] NAMES = {"後続", "行進", "相手", "ほか", "バニラ"};
    private static final long[] calls = new long[NAMES.length], nanos = new long[NAMES.length], nulls = new long[NAMES.length],
            unreach = new long[NAMES.length], nodes = new long[NAMES.length];
    private static int ticks;

    private PathSourceStats() {
    }

    /** {@code PathFinder#findPath} の後に呼ぶ(サーバースレッド)。 */
    public static void record(Mob mob, long ns, Path path) {
        int k;
        if (!HordeMeter.isMod(mob)) {
            k = 4;
        } else {
            CrawlerPack p = CrawlerPack.externalOf(mob);
            if (p != null && p.size() > 0 && p.leader() != mob && (mob.getTarget() == null || mob.distanceToSqr(mob.getTarget()) >= 100.0D)) k = 0;
            else if (PlantMarch.active(mob) && mob.getTarget() == null) k = 1;
            else if (mob.getTarget() != null) k = 2;
            else k = 3;
        }
        calls[k]++;
        nanos[k] += ns;
        if (path == null) nulls[k]++;
        else {
            if (!path.canReach()) unreach[k]++;
            nodes[k] += path.getNodeCount();
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!HordeMeter.on) {
            ticks = 0;
            return;
        }
        if (++ticks < 400) return;
        StringBuilder b = new StringBuilder("経路の出どころ(20 秒):");
        for (int k = 0; k < NAMES.length; k++) {
            if (calls[k] == 0) continue;
            b.append(String.format(Locale.ROOT, " %s %d 回 %.2f ms/tick(1 回 %.2f ms)・無し %d・届かない %d・節 平均 %.1f |", NAMES[k], calls[k],
                    nanos[k] / 1e6 / ticks, nanos[k] / 1e6 / calls[k], nulls[k], unreach[k],
                    calls[k] - nulls[k] == 0 ? 0.0D : (double) nodes[k] / (calls[k] - nulls[k])));
            calls[k] = nanos[k] = nulls[k] = unreach[k] = nodes[k] = 0;
        }
        b.append(' ').append(FlowFields.describe());
        LOGGER.info(b.toString());
        ticks = 0;
    }
}
