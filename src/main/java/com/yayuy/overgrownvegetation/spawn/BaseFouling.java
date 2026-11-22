package com.yayuy.overgrownvegetation.spawn;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.AssaultTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.BaseMemory;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

/**
 * 拠点を汚す(2026-11-16 F5 の襲撃。ユーザー決定: 拠点に着いた敵は拠点の中や周りに胞子を降らせ、籠城の空気を悪くする。1 体 1 分で空気 +1%・降り積もりは空気の 1/4)。
 *
 * <p>認識した拠点の真ん中から {@code assault.foulRadius}(24【仮】)ブロックの中にいる植物側の Mob(G1・G2 以降。環境生物は数えない)を
 * {@code foulIntervalTicks} ごとに数え、その個体のいる升の空気(浮遊)を {@link SporeFieldServer#emitPoint} で、地面を {@link SporeFieldServer#depositPoint} で上げる。
 * 量の単位: 空気 100% = 胞子の場の {@code fullAt}。倒したときの破裂(11-15)は別に足される。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class BaseFouling {

    private BaseFouling() {
    }

    /** 確かめ用: 最後に数えた数と、これまでに足した空気(%・升の合計)。 */
    public static int lastCount;
    public static double totalAirPercent;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return; // テストは foulOnce を直に呼ぶ
        AssaultTable a = DesignData.director().assault;
        if (level.getGameTime() % a.foulIntervalTicks != 0) return;
        for (BaseMemory.Base b : BaseMemory.get(level.getServer()).bases()) {
            if (b.stage() == BaseMemory.Stage.RECOGNIZED) foulOnce(level, b);
        }
    }

    /** 拠点 1 つを 1 回汚す。数えた敵の数を返す。 */
    public static int foulOnce(ServerLevel level, BaseMemory.Base b) {
        AssaultTable a = DesignData.director().assault;
        double r = a.foulRadius;
        Long2IntOpenHashMap perChunk = new Long2IntOpenHashMap();
        int n = 0;
        for (Mob m : level.getEntitiesOfClass(Mob.class,
                new AABB(b.centerX() - r, level.getMinBuildHeight(), b.centerZ() - r, b.centerX() + r, level.getMaxBuildHeight(), b.centerZ() + r),
                BaseFouling::fouls)) {
            double dx = m.getX() - b.centerX(), dz = m.getZ() - b.centerZ();
            if (dx * dx + dz * dz > r * r) continue;
            perChunk.addTo(ChunkPos.asLong(m.getBlockX() >> 4, m.getBlockZ() >> 4), 1);
            n++;
        }
        lastCount = n;
        if (n == 0) return 0;
        double percentEach = a.foulAirPercentPerMinute * a.foulIntervalTicks / 1200.0D;
        double full = DesignData.sporeField().fullAt();
        for (var e : perChunk.long2IntEntrySet()) {
            int cx = ChunkPos.getX(e.getLongKey()), cz = ChunkPos.getZ(e.getLongKey());
            BlockPos at = new BlockPos((cx << 4) + 8, 64, (cz << 4) + 8);
            double air = percentEach * e.getIntValue() / 100.0D * full;
            SporeFieldServer.emitPoint(level, at, air);
            SporeFieldServer.depositPoint(level, at, air * a.foulDepositShare);
            totalAirPercent += percentEach * e.getIntValue();
        }
        return n;
    }

    /** 汚す側か: 生きている植物側の Mob(G1・G2 以降)。 */
    public static boolean fouls(Mob m) {
        if (!m.isAlive()) return false;
        MobGroup g = MobGroup.of(m);
        return g != null && !g.isCritter();
    }
}
