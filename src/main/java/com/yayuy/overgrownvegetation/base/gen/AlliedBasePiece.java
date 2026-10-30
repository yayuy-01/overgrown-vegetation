package com.yayuy.overgrownvegetation.base.gen;

import com.yayuy.overgrownvegetation.base.build.BasePlacer;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import javax.annotation.Nullable;

/**
 * 世界生成の同盟軍の基地の部品(順 2 の着手順 2、2026-10-04)。基地まるごと 1 つの部品で、生成されるチャンクごとに自分のチャンクの分だけを
 * {@link BasePlacer#placeGenerated} で書き出す(計画はチャンクごとに書き出してもまとめてと同じ —— {@code tools/base-preview/PlanCheck})。
 *
 * <p>中身は台帳の基地と同じ(中心・向き・種・レベル・種類・版・地形の標本)。標本は部品と一緒に保存する(生成の途中でワールドを閉じても同じ計画で続きを書き出し、
 * 台帳に載るときにも使う)。箱は計画が届きうる最大({@link BasePlan#GRID})で、開始のチャンクから ±8 チャンクに収まる。
 */
public final class AlliedBasePiece extends StructurePiece {

    private final BlockPos center;
    private final int rotation;
    private final long seed;
    private final int level;
    private final BaseType type;
    private final int version;
    private final short[] samples;
    private final short[] surface;
    /** 書き出し用の基地(計画のかたまり。チャンクごとの書き出しで使い回す。作り直せる)。 */
    private volatile BaseSite emission;

    public AlliedBasePiece(BlockPos center, int rotation, long seed, int level, BaseType type, int version,
                    short[] samples, short[] surface, LevelHeightAccessor heights) {
        super(BaseStructures.ALLIED_BASE_PIECE.get(), 0, box(center, samples, surface, heights));
        this.center = center.immutable();
        this.rotation = rotation;
        this.seed = seed;
        this.level = level;
        this.type = type;
        this.version = version;
        this.samples = samples;
        this.surface = surface;
        setOrientation(null);
    }

    public AlliedBasePiece(CompoundTag tag) {
        super(BaseStructures.ALLIED_BASE_PIECE.get(), tag);
        this.center = new BlockPos(tag.getInt("cx"), tag.getInt("cy"), tag.getInt("cz"));
        this.rotation = tag.getInt("rot");
        this.seed = tag.getLong("seed");
        this.level = Math.max(1, Math.min(BasePlan.MAX_LEVEL, tag.getInt("level")));
        this.type = BaseType.byId(tag.getString("type"));
        this.version = tag.getInt("ver");
        this.samples = narrow(tag.getIntArray("samples"));
        this.surface = narrow(tag.getIntArray("surface"));
        setOrientation(null);
    }

    /** 計画が届きうる最大の四角(中心から {@link BasePlan#GRID})。高さは地形の標本から(上は建物の分も空ける)。 */
    private static BoundingBox box(BlockPos center, short[] samples, short[] surface, LevelHeightAccessor heights) {
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int i = 0; i < samples.length; i++) {
            lo = Math.min(lo, samples[i]);
            hi = Math.max(hi, Math.max(samples[i], surface[i]));
        }
        int y0 = Math.max(heights.getMinBuildHeight() + 1, lo - 16);
        int y1 = Math.min(heights.getMaxBuildHeight() - 1, hi + 64);
        int r = BasePlan.GRID;
        return new BoundingBox(center.getX() - r, Math.min(y0, y1), center.getZ() - r, center.getX() + r, Math.max(y0, y1), center.getZ() + r);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putInt("cx", center.getX());
        tag.putInt("cy", center.getY());
        tag.putInt("cz", center.getZ());
        tag.putInt("rot", rotation);
        tag.putLong("seed", seed);
        tag.putInt("level", level);
        tag.putString("type", type.id);
        tag.putInt("ver", version);
        tag.putIntArray("samples", widen(samples));
        tag.putIntArray("surface", widen(surface));
    }

    @Override
    public void postProcess(WorldGenLevel worldGenLevel, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
                            BoundingBox box, ChunkPos chunkPos, BlockPos pivot) {
        BasePlacer.placeGenerated(worldGenLevel, emission(worldGenLevel.getLevel().dimension()), chunkPos);
    }

    /** 書き出し用の基地(初めて使うときに作る。同時に何チャンクも生成されるので、作るのは 1 回だけ)。 */
    public BaseSite emission(ResourceKey<Level> dimension) {
        BaseSite e = emission;
        if (e == null) {
            synchronized (this) {
                e = emission;
                if (e == null) {
                    e = BaseSite.forEmission(dimension, center, rotation, seed, level, samples, surface, version, type);
                    emission = e;
                }
            }
        }
        return e;
    }

    /** もう作ってある書き出し用の基地(台帳に載せるときに計画を作り直さずに済む)。無ければ null。 */
    @Nullable
    BaseSite emissionIfBuilt() {
        return emission;
    }

    /** 台帳に載せる頼み({@link BaseWorldgen})。 */
    public BaseWorldgen.Request request(ResourceKey<Level> dimension) {
        return new BaseWorldgen.Request(dimension, center, rotation, seed, level, type, version, samples.clone(), surface.clone(), emissionIfBuilt());
    }

    public BlockPos center() {
        return center;
    }

    /** 地形(保存した標本から作り直す)。 */
    public com.yayuy.overgrownvegetation.base.plan.BaseTerrain terrain() {
        return com.yayuy.overgrownvegetation.base.plan.BaseTerrain.fromSamples(samples, surface);
    }

    public int rotation() {
        return rotation;
    }

    public long seed() {
        return seed;
    }

    public int level() {
        return level;
    }

    public BaseType baseType() {
        return type;
    }

    public int version() {
        return version;
    }

    private static int[] widen(short[] a) {
        int[] r = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i];
        }
        return r;
    }

    private static short[] narrow(int[] a) {
        short[] r = new short[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = (short) a[i];
        }
        return r;
    }
}
