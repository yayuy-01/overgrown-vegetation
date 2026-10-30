package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.plan.BaseEmitter;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.Blueprints;
import com.yayuy.overgrownvegetation.base.plan.BuildingKind;
import com.yayuy.overgrownvegetation.base.plan.Plot;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 基地を建てる・広げる工事(2026-10-01)。1 tick にチャンク {@link #CHUNKS_PER_TICK} つずつ、中心に近い順に置く
 * (工兵が建てる設定。工兵はまだいないので、いまは建物が中心から順に現れるだけ)。
 *
 * <p>広げるとき(レベル L → L+1)は、古い計画と新しい計画で中身の要約({@link BaseEmitter#columnKey})が違う列
 * (外周が中へ入った・金網が壁になった・新しい区画がかかった・塔が消えた)と、新しい建物・外周の塔と正門・検問所、
 * 階数の変わる管理棟だけを置き直す。内側の建物には触らない。途中までの進み具合は台帳に残るので、ワールドを閉じても続きから建つ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BaseConstruction {

    /** 1 tick に置くチャンクの数(仮決め。レベル 1 ≒ 25 チャンク = 1 秒強、レベル 5 ≒ 200 チャンク = 10 秒)。 */
    public static final int CHUNKS_PER_TICK = 2;

    /** 工事を始める。{@code from} = 今のレベル(0 = 新しく建てる)。 */
    public static void start(BaseSites data, BaseSite site, int from, int to) {
        if (site.fallen) { // 陥落した基地は建てない・広げない(仮の陥落、順 2 の着手順 1)
            return;
        }
        site.buildFrom = from;
        site.buildTo = Math.max(1, Math.min(BasePlan.MAX_LEVEL, to));
        site.cursor = 0;
        data.changed();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        BaseSites data = BaseSites.get(server);
        // サーバーが重いときは予算の番人が次の tick へ回す(2026-10-06。結果は同じで遅れるだけ)。
        // 1 チャンクの工事は生成を伴うことがあり重いので、止めるときは丸ごと止める。
        boolean started = false;
        long start = 0L;
        int done = 0;
        try {
            for (BaseSite site : data.all()) {
                if (site.buildTo <= 0 || site.fallen) {
                    continue;
                }
                ServerLevel level = server.getLevel(site.dimension);
                if (level == null) {
                    continue;
                }
                if (!started) {
                    if (!TickBudget.mayStart(PerfJob.BASE_CONSTRUCTION)) {
                        return;
                    }
                    started = true;
                    start = TickBudget.begin();
                } else if (!TickBudget.timeLeft()) {
                    break;
                }
                List<ChunkPos> chunks = chunks(site, site.buildTo);
                for (int n = 0; n < CHUNKS_PER_TICK && site.cursor < chunks.size()
                        && (n == 0 || TickBudget.timeLeft()); n++) {
                    ChunkPos c = chunks.get(site.cursor++);
                    level.getChunk(c.x, c.z); // 読み込む(無ければ作る)
                    placeChunk(level, site, c);
                    done++;
                }
                data.changed();
                if (site.cursor >= chunks.size()) {
                    finish(level, data, site);
                }
            }
        } finally {
            if (started) {
                TickBudget.end(PerfJob.BASE_CONSTRUCTION, start, done);
            }
        }
    }

    /** 工事を全部その場で済ませる(GameTest・コマンドの「すぐ建てる」用)。 */
    public static void runToEnd(ServerLevel level, BaseSites data, BaseSite site) {
        List<ChunkPos> chunks = chunks(site, site.buildTo);
        while (site.cursor < chunks.size()) {
            ChunkPos c = chunks.get(site.cursor++);
            level.getChunk(c.x, c.z);
            placeChunk(level, site, c);
        }
        finish(level, data, site);
    }

    private static void placeChunk(ServerLevel level, BaseSite site, ChunkPos c) {
        int from = site.buildFrom;
        int to = site.buildTo;
        if (from <= 0) {
            BasePlacer.placeChunk(level, site, to, c, (x, z) -> true, p -> true);
            return;
        }
        BaseEmitter before = site.emitter(from);
        BaseEmitter after = site.emitter(to);
        boolean hqChanges = Blueprints.hqFloors(from) != Blueprints.hqFloors(to);
        Set<Plot> old = new HashSet<>(before.plan().plots);
        BaseEmitter.ColumnFilter columns = (x, z) -> before.columnKey(x, z) != after.columnKey(x, z);
        Predicate<Plot> plots = p -> !old.contains(p)
                || p.kind() == BuildingKind.WATCHTOWER || p.kind() == BuildingKind.GATEHOUSE || p.kind() == BuildingKind.CHECKPOINT
                || (hqChanges && p.kind() == BuildingKind.HQ);
        BasePlacer.placeChunk(level, site, to, c, columns, plots);
    }

    private static void finish(ServerLevel level, BaseSites data, BaseSite site) {
        int to = site.buildTo;
        site.setLevel(to);
        site.buildTo = 0;
        site.buildFrom = 0;
        site.cursor = 0;
        data.changed();
        // 前からいる兵士の持ち場も新しい範囲に(順 2 の着手順 3。読み込まれていない兵士は読み込まれたときに)
        BaseGarrison.rehome(level, site);
        if (BaseGarrison.enabled) {
            BaseGarrison.reinforce(level, site);
        }
        BlockPos c = site.center;
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(c) < 256 * 256) {
                p.sendSystemMessage(Component.translatable("message.overgrown_vegetation.base.built", site.id, to));
            }
        }
    }

    /** 工事で置くチャンク(計画の手を入れる範囲を覆う世界のチャンク、中心に近い順)。 */
    static List<ChunkPos> chunks(BaseSite site, int level) {
        BasePlan plan = site.plan(level);
        BlockPos a = site.toWorld(plan.minX(), 0, plan.minZ());
        BlockPos b = site.toWorld(plan.maxX(), 0, plan.maxZ());
        int cx0 = Math.min(a.getX(), b.getX()) >> 4;
        int cx1 = Math.max(a.getX(), b.getX()) >> 4;
        int cz0 = Math.min(a.getZ(), b.getZ()) >> 4;
        int cz1 = Math.max(a.getZ(), b.getZ()) >> 4;
        List<ChunkPos> out = new ArrayList<>();
        for (int x = cx0; x <= cx1; x++) {
            for (int z = cz0; z <= cz1; z++) {
                out.add(new ChunkPos(x, z));
            }
        }
        int ccx = site.center.getX() >> 4;
        int ccz = site.center.getZ() >> 4;
        out.sort(Comparator.comparingInt((ChunkPos p) -> Math.max(Math.abs(p.x - ccx), Math.abs(p.z - ccz)))
                .thenComparingInt(p -> p.x).thenComparingInt(p -> p.z));
        return out;
    }

    private BaseConstruction() {
    }
}
