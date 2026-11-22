package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.network.SporeDustPayload;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

/**
 * 胞子の粉の見た目の<b>写し</b>(2026-10-31 合間の小物 3。元は parked の同名のクラス、2026-10-23 胞子の場と風 第 2 段 E)。サーバーが送る
 * チャンクごとの降り積もり %(0〜100)と、シャベルで払った升の印を覚え、変わったチャンクの粉のある高さの区画だけを描き直させる
 * (描くのは {@link SporeDustRenderer})。シャベルで払える所か({@link #hasDustAt})は画面側の予測にも使う({@code spore/field/SporeSweeping})。
 *
 * <p>受け取りはワールドに入る途中にも来るので、受け取る所でワールドの替わりを見る(memory client-payload-before-first-tick)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class ClientSporeDust {

    private static final Long2ByteOpenHashMap LEVELS = new Long2ByteOpenHashMap();
    private static final Long2IntOpenHashMap MASKS = new Long2IntOpenHashMap();
    private static ClientLevel lastLevel;

    private ClientSporeDust() {
    }

    public static void accept(SporeDustPayload p) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            LEVELS.clear();
            MASKS.clear();
            lastLevel = level;
        }
        LongOpenHashSet dirty = new LongOpenHashSet();
        for (int dz = 0; dz < p.size(); dz++) {
            for (int dx = 0; dx < p.size(); dx++) {
                int cx = p.minCx() + dx;
                int cz = p.minCz() + dz;
                long key = ChunkPos.asLong(cx, cz);
                int i = dz * p.size() + dx;
                byte v = p.levels()[i];
                int mask = p.masks()[i];
                if (v == LEVELS.get(key) && mask == MASKS.get(key)) {
                    continue;
                }
                if (v == 0) {
                    LEVELS.remove(key);
                } else {
                    LEVELS.put(key, v);
                }
                if (mask == 0) {
                    MASKS.remove(key);
                } else {
                    MASKS.put(key, mask);
                }
                // 隣のチャンクの列も混ぜて描くので、まわり 1 チャンクも描き直す。
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oz = -1; oz <= 1; oz++) {
                        dirty.add(ChunkPos.asLong(cx + ox, cz + oz));
                    }
                }
            }
        }
        if (level != null) {
            for (long k : dirty) {
                markDirty(level, ChunkPos.getX(k), ChunkPos.getZ(k));
            }
        }
        if (LEVELS.size() > 20000) {
            LEVELS.clear();
            MASKS.clear();
        }
    }

    /**
     * チャンクの粉が載る高さの区画だけを描き直させる(全部の区画を描き直すと重い)。オーバーワールドは列のいちばん上の高さの最小〜最大、
     * ネザーは空洞の床の範囲。読み込まれていないチャンクは何もしない(読み込まれたときに組み立てられる)。
     */
    private static void markDirty(ClientLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
        if (chunk == null) {
            return;
        }
        int lo;
        int hi;
        if (level.dimension() == Level.NETHER) {
            lo = com.yayuy.overgrownvegetation.corruption.NetherSurface.bottomY(level);
            hi = com.yayuy.overgrownvegetation.corruption.NetherSurface.topY(level);
        } else {
            lo = Integer.MAX_VALUE;
            hi = Integer.MIN_VALUE;
            for (int i = 0; i < 256; i++) {
                int h = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, i & 15, i >> 4);
                lo = Math.min(lo, h);
                hi = Math.max(hi, h);
            }
        }
        for (int sy = (lo - 1) >> 4; sy <= (hi + 1) >> 4; sy++) {
            Minecraft.getInstance().levelRenderer.setSectionDirty(cx, sy, cz);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        LEVELS.clear();
        MASKS.clear();
        lastLevel = null;
    }

    /** 今のワールドの写しか(前のワールド・別の次元の値で描かない)。 */
    static boolean current(Level level) {
        return !LEVELS.isEmpty() && level == lastLevel;
    }

    /** チャンクとまわり 1 チャンクのどこかに粉があるか。 */
    static boolean nearDust(int cx, int cz) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (LEVELS.get(ChunkPos.asLong(cx + ox, cz + oz)) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 列ごとの降り積もり %(0〜100 の連続値)。まわり 4 つのチャンクの真ん中の値を距離で混ぜる(チャンクの境が四角く見えないように)。 */
    static float columnLevel(int x, int z) {
        float fx = (x + 0.5F) / 16.0F - 0.5F;
        float fz = (z + 0.5F) / 16.0F - 0.5F;
        int i0 = Mth.floor(fx);
        int j0 = Mth.floor(fz);
        float tx = fx - i0;
        float tz = fz - j0;
        float a = LEVELS.get(ChunkPos.asLong(i0, j0));
        float b = LEVELS.get(ChunkPos.asLong(i0 + 1, j0));
        float c = LEVELS.get(ChunkPos.asLong(i0, j0 + 1));
        float d = LEVELS.get(ChunkPos.asLong(i0 + 1, j0 + 1));
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
    }

    /** その列が払った升に入るか。jitter なら升の境を世界座標の雑音で ±1 ブロックずらして見る(4×4 の四角をぼかす)。 */
    static boolean swept(int x, int z, boolean jitter) {
        if (MASKS.isEmpty()) {
            return false;
        }
        if (jitter) {
            int h = hash(x * 31 + 7, z * 17 + 3);
            x += Math.floorMod(h, 3) - 1;
            z += Math.floorMod(h >>> 8, 3) - 1;
        }
        int mask = MASKS.get(ChunkPos.asLong(x >> 4, z >> 4));
        return mask != 0 && (mask & (1 << (((z & 15) >> 2) * 4 + ((x & 15) >> 2)))) != 0;
    }

    /** その上面に粉を載せるか: 汚染済み(#corrupted)・覆われた上面・過繁茂のバイオームには載せない(2026-10-24 ユーザー指定、サーバーの SporeSweeping#dustSurface と同じ)。 */
    static boolean dustSurface(Level level, LevelChunk chunk, BlockPos pos, BlockState state) {
        if (state.is(com.yayuy.overgrownvegetation.ModTags.CORRUPTED) || OvergrownLayerData.isCovered(chunk, pos, state)) {
            return false;
        }
        return !ModBiomes.isOvergrownLike(level.getBiome(pos.above()));
    }

    /** その上面に粉が見えているか(シャベルで払えるか。画面側の予測用)。 */
    public static boolean hasDustAt(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level != lastLevel || LEVELS.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)) <= 0) {
            return false;
        }
        if (!dustSurface(level, level.getChunkAt(pos), pos, level.getBlockState(pos))) {
            return false;
        }
        return columnLevel(pos.getX(), pos.getZ()) >= DesignData.atmosphere().dust().minPercent() && !swept(pos.getX(), pos.getZ(), false);
    }

    /** 世界座標の雑音。 */
    static int hash(int x, int z) {
        int h = x * 0x27D4EB2D ^ z * 0x165667B1;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h;
    }
}
