package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 同盟軍の基地の台帳(2026-10-01)。ワールドに 1 つ(オーバーワールドの data に保存)。
 * 基地 = 点(道の台帳の頂点にもなる予定、メモリ「同盟軍と壁付きの基地」)。今は {@code /ov base place} と世界生成の基地が載る。
 */
public final class BaseSites extends SavedData {

    private static final String ID = "overgrown_vegetation_base_sites";

    public static final SavedData.Factory<BaseSites> FACTORY =
            new SavedData.Factory<>(BaseSites::new, (tag, registries) -> load(tag), null);

    private final List<BaseSite> sites = new ArrayList<>();
    private int nextId = 1;
    /**
     * フェーズ 2 以上を最初に見た汚染の暦の時刻(−1 = まだ)。フェーズの台帳にフェーズ 2 以上へ入った時刻が無いとき
     * (その記録より前の保存)だけ、胞子除去装置の後付けの起点に使う({@link BasePurifiers})。
     */
    long phase2Seen = -1L;
    /** 台帳から外した世界生成の基地の中心(y = 0 の {@link BlockPos#asLong})。 */
    private final LongOpenHashSet ignoredGenerated = new LongOpenHashSet();

    private BaseSites() {
    }

    public static BaseSites get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static BaseSites load(CompoundTag tag) {
        BaseSites data = new BaseSites();
        data.nextId = Math.max(1, tag.getInt("nextId"));
        data.phase2Seen = tag.contains("phase2Seen") ? tag.getLong("phase2Seen") : -1L;
        for (long c : tag.getLongArray("ignoredGenerated")) {
            data.ignoredGenerated.add(c);
        }
        ListTag list = tag.getList("sites", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            data.sites.add(BaseSite.load(list.getCompound(i)));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("nextId", nextId);
        tag.putLong("phase2Seen", phase2Seen);
        tag.putLongArray("ignoredGenerated", ignoredGenerated.toLongArray());
        ListTag list = new ListTag();
        for (BaseSite s : sites) {
            list.add(s.save());
        }
        tag.put("sites", list);
        return tag;
    }

    /**
     * 新しい基地を載せる(まだ建てない)。{@code type} = 種類(広げ方)、{@code purifiers} = 胞子除去装置を最初から置くか(ふつうは {@link BasePurifiers#allowed}:
     * フェーズ 2 以上なら置く)。
     */
    public BaseSite add(ResourceKey<Level> dim, BlockPos center, int rotation, long seed, int level, BaseTerrain terrain, boolean purifiers,
                        BaseType type) {
        BaseSite site = new BaseSite(nextId++, dim, center, rotation, seed, level, terrain.samples(), terrain.surfaceSamples(),
                BaseSite.VERSION, type);
        site.purifiers = purifiers;
        sites.add(site);
        setDirty();
        return site;
    }

    /**
     * 世界生成で生まれた基地を載せる(もう建っているので工事はしない。順 2 の着手順 2、2026-10-04)。{@code version} は生成したときの配置の版
     * (生まれてから載るまでの間に版が上がっても、建っているブロックの版で載せる)。胞子除去装置は生成では置かないので、最初は「なし」。
     */
    public BaseSite addGenerated(ResourceKey<Level> dim, BlockPos center, int rotation, long seed, int level,
                                 short[] samples, short[] surface, int version, BaseType type) {
        BaseSite site = new BaseSite(nextId++, dim, center, rotation, seed, level, samples, surface, version, type);
        site.purifiers = false;
        site.generated = true;
        sites.add(site);
        setDirty();
        return site;
    }

    /** 中心がちょうど {@code center} の基地(世界生成の基地を 2 回載せない)。 */
    @Nullable
    public BaseSite byCenter(ResourceKey<Level> dim, BlockPos center) {
        for (BaseSite s : sites) {
            if (s.dimension.equals(dim) && s.center.getX() == center.getX() && s.center.getZ() == center.getZ()) {
                return s;
            }
        }
        return null;
    }

    /** 台帳から外した世界生成の基地か(外した基地の中心のチャンクをまた読み込んでも、載せ直さない)。 */
    public boolean ignoredGenerated(BlockPos center) {
        return ignoredGenerated.contains(BlockPos.asLong(center.getX(), 0, center.getZ()));
    }

    public void remove(BaseSite site) {
        sites.remove(site);
        if (site.generated) {
            ignoredGenerated.add(BlockPos.asLong(site.center.getX(), 0, site.center.getZ()));
        }
        setDirty();
    }

    /** 外した世界生成の基地の覚えを消す(GameTest の後始末)。 */
    public void unignoreGenerated(BlockPos center) {
        if (ignoredGenerated.remove(BlockPos.asLong(center.getX(), 0, center.getZ()))) {
            setDirty();
        }
    }

    public List<BaseSite> all() {
        return Collections.unmodifiableList(sites);
    }

    @Nullable
    public BaseSite byId(int id) {
        for (BaseSite s : sites) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }

    /** {@code pos} から水平 {@code maxDist} 以内でいちばん近い基地(中心までの距離)。 */
    @Nullable
    public BaseSite nearest(ResourceKey<Level> dim, BlockPos pos, double maxDist) {
        BaseSite best = null;
        double bestSq = maxDist * maxDist;
        for (BaseSite s : sites) {
            if (!s.dimension.equals(dim)) {
                continue;
            }
            double dx = s.center.getX() - pos.getX();
            double dz = s.center.getZ() - pos.getZ();
            double sq = dx * dx + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = s;
            }
        }
        return best;
    }

    /** 中心どうしが {@code distance} より近い基地があるか(世界生成の基地を重ねない)。 */
    public boolean anyWithin(ResourceKey<Level> dim, BlockPos pos, double distance) {
        return nearest(dim, pos, distance) != null;
    }

    void changed() {
        setDirty();
    }
}
