package com.yayuy.overgrownvegetation.base.gen;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseGarrison;
import com.yayuy.overgrownvegetation.base.build.BasePurifiers;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 世界生成の基地を台帳に載せる(順 2 の着手順 2、2026-10-04)。ブロックは地形と一緒に置かれ({@link AlliedBasePiece})、
 * <b>プレイヤーが初めて中心のチャンクを読み込んだとき</b>に台帳に載る(生成は裏の作業場で進むので、その場では台帳に書けない)。
 * 載るときに:
 * <ul>
 *     <li>周りの汚染が、留守中の計算で落ちる量を超えていれば、最初から陥落した廃墟にする({@link BaseEconomy#overrun}。
 *     ユーザー指定 10-04「耐えられなければ廃墟」)。守備兵は出さず、生成で入れた機関砲台の弾は読み込まれたチャンクから空にする。</li>
 *     <li>そうでなければ定員の守備兵を出し、フェーズ 2 以上なら胞子除去装置の後付けをすぐ始める(生成では置かない)。</li>
 *     <li>貯蓄は次の精算で上限から始まる(補給値の起点の日 = 載った日)。</li>
 * </ul>
 * 同じ中心の基地は 2 回載せない。台帳から外した世界生成の基地({@code /ov base forget})も載せ直さない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BaseWorldgen {

    /** 台帳に載せる頼み(部品の中身の写し)。{@code emission} = 生成で作った計画(あれば使い回す)。 */
    public record Request(ResourceKey<Level> dimension, BlockPos center, int rotation, long seed, int level, BaseType type, int version,
                          short[] samples, short[] surface, @Nullable BaseSite emission) {
    }

    /** 中心のチャンクが読み込まれた基地(イベントの中では台帳に触らず積むだけ)。 */
    private static final List<Request> QUEUE = new ArrayList<>();

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        for (StructureStart start : chunk.getAllStarts().values()) {
            if (!(start.getStructure() instanceof AlliedBaseStructure) || !start.isValid()) {
                continue;
            }
            for (StructurePiece piece : start.getPieces()) {
                if (piece instanceof AlliedBasePiece p) {
                    QUEUE.add(p.request(level.dimension()));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (QUEUE.isEmpty()) {
            return;
        }
        List<Request> todo = List.copyOf(QUEUE);
        QUEUE.clear();
        for (Request r : todo) {
            register(event.getServer(), r);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUE.clear();
    }

    /** 台帳に載せる。もう載っている・外した基地なら何もしない(null)。 */
    @Nullable
    public static BaseSite register(MinecraftServer server, Request r) {
        BaseSites data = BaseSites.get(server);
        if (data.byCenter(r.dimension(), r.center()) != null || data.ignoredGenerated(r.center())) {
            return null;
        }
        ServerLevel level = server.getLevel(r.dimension());
        if (level == null) {
            return null;
        }
        BaseSite site = data.addGenerated(r.dimension(), r.center(), r.rotation(), r.seed(), r.level(), r.samples(), r.surface(),
                r.version(), r.type());
        site.adoptPlans(r.emission());
        double ratio = BaseEconomy.surroundRatio(server, site);
        boolean overrun = BaseEconomy.overrun(site, ratio);
        if (overrun) {
            BaseEconomy.fallAtBirth(server, data, site);
        } else {
            if (BaseGarrison.enabled) {
                BaseGarrison.reinforce(level, site);
            }
            if (BasePurifiers.allowed(server.overworld())) {
                BasePurifiers.begin(server, data, site);
            }
        }
        OvergrownVegetation.LOGGER.info("世界生成の基地 #{} を台帳に載せた({}・レベル {}・中心 {}・周りの汚染 {}%{})", site.id, site.type.label,
                site.level(), site.center.toShortString(), Math.round(ratio * 100.0D), overrun ? "・最初から陥落" : "");
        return site;
    }

    private BaseWorldgen() {
    }
}
