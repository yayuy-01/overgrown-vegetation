package com.yayuy.overgrownvegetation.base.gen;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.base.build.BaseNoise;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.plan.BasePlanner;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * 世界生成の同盟軍の基地(順 2 の着手順 2、2026-10-04)。チャンクの真ん中を中心に、{@code /ov base place} と同じ配置計画の基地が
 * 地形と一緒に建つ(部品 {@link AlliedBasePiece} が自分のチャンクの分だけ書き出す)。
 *
 * <p><b>ユーザー指定</b>: 最初から基地がある・村より少し多いくらい(間隔はデータの構造物の組)・レベルはランダム・最初は海と山以外
 * (バイオームのタグ)・浄化基地は最初から無い(10-01〜02)/ 種類の割合は研究をまれに(10-04、{@code defense.json} の {@code alliedBase})。
 * <br>生まない所(Claude 仮決め): 中心(半幅 36 の浄化圏)とその周り 6 マスに水がある・中心が荒い({@link BasePlanner#coreCheck})/
 * 村・森の洋館が来うる場所の近く(基地の書き出しは家を避けない)。
 *
 * <p>この判定は生成の裏の作業場で走る(台帳・フェーズに触らない)。{@code /locate} もここを通る(本体のスレッドで候補を順に判定するので、
 * 部品は作らず、中心の判定の点を並べて読む —— 2026-10-04 に約 18 秒 → 約 5 秒。答えは同じ)。
 */
public final class AlliedBaseStructure extends Structure {

    public static final MapCodec<AlliedBaseStructure> CODEC = simpleCodec(AlliedBaseStructure::new);

    /** 生まれるかの判定の答え(開発用の数え上げ {@code /ov base survey} にも出す)。 */
    public enum Verdict {
        /** 生まれる。 */
        OK,
        /** 中心のバイオームが生まれる所でない(海・川・砂浜・山など)。 */
        BIOME,
        /** 村・森の洋館が来うる場所の近く。 */
        NEIGHBOUR,
        /** 中心か、その周りに水がある。 */
        WATER,
        /** 中心が荒い。 */
        STEEP,
        /** 先に決まる隣の候補(生まれる)と重なる。 */
        CROWDED
    }

    /**
     * 基地が手を入れる範囲の中心からの届き(チェビシェフ距離)の上限: レベル 1 は 55、レベル 2 以上は 119(2026-10-04 の測り。種類 7 × レベル 5 ×
     * 試験の地形 36 計画で、L1 は必ず 55・L2 以上の最大 119 = 届く範囲 108 + 払う幅 10 + 1)。隣の候補と重なるかはこれで見る(計画を作らずに済む)。
     * {@code tools/base-preview/PlanCheck} が超えないことを点検する。
     */
    public static int reach(int level) {
        return level <= 1 ? 55 : 119;
    }

    /** 隣の基地の手を入れる範囲との間に空ける幅(ブロック)。 */
    private static final int CROWD_MARGIN = 4;

    /** 判定と、生まれるなら部品。 */
    public record Candidate(Verdict verdict, @Nullable AlliedBasePiece piece) {
    }

    public AlliedBaseStructure(StructureSettings settings) {
        super(settings);
    }

    /**
     * 生まれるかを決め、部品は本当に建てるときに作る(2026-10-04、{@code /locate} を軽くするため)。{@code /locate} と構造物の下調べ
     * ({@code StructureCheck})は「生まれるか」しか見ないので、地形の標本の残り(35 × 35 のうち中心の判定で読んでいない 8 割)を読まずに済む。
     * 建てるとき(生成の構造物の段)は、すぐあとで同じスレッドが部品を作る(答えも部品も {@link #evaluate} と同じ)。
     */
    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        if (!ExperimentalFeatures.worldgenEnabled()) {
            return Optional.empty(); // 実験的要素がオフのワールドには生まれない(2026-10-09 ユーザー指定)
        }
        RandomSpreadStructurePlacement placement = dataPlacement(context);
        Rolled r = roll(context);
        if (r.verdict() != Verdict.OK || placement != null && crowded(context, placement, r.level())) {
            return Optional.empty();
        }
        return Optional.of(new GenerationStub(r.center(), builder -> builder.addPiece(piece(context, r))));
    }

    /** 生まれると決まった候補 {@code r} の部品(地形の標本を全部読む)。 */
    private static AlliedBasePiece piece(GenerationContext context, Rolled r) {
        BaseTerrain terrain = BaseTerrain.sample(r.noise());
        return new AlliedBasePiece(r.center(), r.rotation(), r.seed(), r.level(), r.type(), BaseSite.VERSION,
                terrain.samples(), terrain.surfaceSamples(), context.heightAccessor());
    }

    /** データの構造物の組の置き方(無い・散らばる形でないときは null = 隣の候補を見ない)。 */
    @Nullable
    public static RandomSpreadStructurePlacement dataPlacement(GenerationContext context) {
        StructureSet set = context.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).get(BaseStructures.ALLIED_BASE_SET);
        return set != null && set.placement() instanceof RandomSpreadStructurePlacement p ? p : null;
    }

    @Override
    public StructureType<?> type() {
        return BaseStructures.ALLIED_BASE.get();
    }

    /**
     * チャンク {@code context.chunkPos()} に基地が生まれるか。向き・種・種類・レベルは地形を見る前に引く(地形で断っても乱数の進みが変わらない)。
     * 生まれるなら、地形の標本まで取った部品を返す。
     */
    public Candidate evaluate(GenerationContext context) {
        return evaluate(context, dataPlacement(context));
    }

    /**
     * {@code placement} = 候補の並び(隣の候補と重なるかを見る。null なら見ない —— 数え上げで間隔を試すときは試す並びを渡す)。
     * 隣と重なるときは、並びの番号(セルの x、同じなら z)が小さい方が先に決まる: 小さい側の隣の候補が<b>単独なら生まれる</b>うえで重なるなら、こちらが譲る
     * ({@link Verdict#CROWDED})。どのチャンクから先に生成されても答えは同じで、生まれた基地どうしは重ならない(譲られた側が別の理由で生まれなくても、
     * こちらは譲ったまま —— 少し減るが重ならない方を取る)。重なりは届き {@link #reach} どうしの和 + {@link #CROWD_MARGIN} で見る。
     */
    public Candidate evaluate(GenerationContext context, @Nullable RandomSpreadStructurePlacement placement) {
        Rolled r = roll(context);
        if (r.verdict() != Verdict.OK) {
            return new Candidate(r.verdict(), null);
        }
        if (placement != null && crowded(context, placement, r.level())) {
            return new Candidate(Verdict.CROWDED, null);
        }
        return new Candidate(Verdict.OK, piece(context, r));
    }

    /** 隣を見ない判定の答えと、引いた中身(地形のノイズの読み口は中心の分を読み終えている)。 */
    private record Rolled(Verdict verdict, @Nullable BlockPos center, int rotation, long seed, @Nullable BaseType type, int level,
                          @Nullable BaseTerrain.Sampler noise) {
        static Rolled no(Verdict v) {
            return new Rolled(v, null, 0, 0L, null, 0, null);
        }
    }

    /** 隣の候補を見ずに、このチャンクに生まれるか(バイオーム・村の近く・中心の水と起伏)と、向き・種・種類・レベル。 */
    private Rolled roll(GenerationContext context) {
        DefenseTable t = DesignData.defense();
        ChunkPos cp = context.chunkPos();
        int x = cp.getMiddleBlockX();
        int z = cp.getMiddleBlockZ();
        ChunkGenerator gen = context.chunkGenerator();
        int y = gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
        if (!context.validBiome().test(biomeAt(context, x, y, z))) {
            return Rolled.no(Verdict.BIOME);
        }
        if (near(context, BuiltinStructureSets.VILLAGES, t.baseWorldgenAvoidVillageChunks())
                || near(context, BuiltinStructureSets.WOODLAND_MANSIONS, t.baseWorldgenAvoidMansionChunks())) {
            return Rolled.no(Verdict.NEIGHBOUR);
        }
        RandomSource rnd = context.random();
        int rotation = rnd.nextInt(4);
        long seed = rnd.nextLong();
        BaseType type = rollType(rnd, t);
        int level = rollLevel(rnd, t);
        BlockPos center = new BlockPos(x, y, z);
        // 中心の判定に効く点だけ先に読み(約 2 割)、通ったら残りを読む(同じ点は読み直さない)。断る候補が多いので /locate と数え上げが軽くなる
        BaseTerrain.Sampler noise = BaseNoise.sampler(gen, context.randomState(), context.heightAccessor(), center, rotation);
        int core = coreVerdict(noise, t.baseWorldgenCoreMaxRelief());
        if (core == BasePlanner.CORE_WATER) {
            return Rolled.no(Verdict.WATER);
        }
        if (core == BasePlanner.CORE_STEEP) {
            return Rolled.no(Verdict.STEEP);
        }
        return new Rolled(Verdict.OK, center, rotation, seed, type, level, noise);
    }

    /** 並びの番号が小さい側の隣の候補のうち、単独なら生まれて、レベル {@code level} のこの基地と重なるものがあるか。 */
    private boolean crowded(GenerationContext context, RandomSpreadStructurePlacement placement, int level) {
        ChunkPos cp = context.chunkPos();
        int spacing = placement.spacing();
        int gx = Math.floorDiv(cp.x, spacing);
        int gz = Math.floorDiv(cp.z, spacing);
        int maxChunks = (reach(level) + reach(5) + CROWD_MARGIN) / 16 + 1; // これより遠い候補とは重ならない
        int cells = maxChunks / spacing + 1;
        for (int dx = -cells; dx <= cells; dx++) {
            for (int dz = -cells; dz <= cells; dz++) {
                int nx = gx + dx;
                int nz = gz + dz;
                if (!(nx < gx || nx == gx && nz < gz)) {
                    continue; // 番号が大きい側(と自分)は向こうがこちらを見て譲る
                }
                ChunkPos at = placement.getPotentialStructureChunk(context.seed(), nx * spacing, nz * spacing);
                if (Math.max(Math.abs(at.x - cp.x), Math.abs(at.z - cp.z)) > maxChunks) {
                    continue;
                }
                Rolled other = roll(new GenerationContext(context.registryAccess(), context.chunkGenerator(), context.biomeSource(),
                        context.randomState(), context.structureTemplateManager(), context.seed(), at, context.heightAccessor(), context.validBiome()));
                if (other.verdict() != Verdict.OK) {
                    continue;
                }
                int d = Math.max(Math.abs(at.getMiddleBlockX() - cp.getMiddleBlockX()), Math.abs(at.getMiddleBlockZ() - cp.getMiddleBlockZ()));
                if (d < reach(level) + reach(other.level()) + CROWD_MARGIN) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 中心の判定に効く標本の範囲: 中心の半幅 36 + 水を見る周り 6 + なだらかな面の均し 2 回ぶん(標本 2 つ = 16)。
     * この中だけ読んだ標本でも、全部読んだときと同じ判定になる(GameTest {@code coreCheckRejectsWaterAndSlopes} が確かめる)。
     */
    public static final int CORE_SAMPLE_RADIUS = BasePlanner.CORE_R + 6 + 2 * BaseTerrain.STEP;

    /** 中心の判定で水を見る点のうち、先に見る粗い 1/4(16 マスおき)。 */
    private static final List<int[]> WATER_COARSE = BasePlanner.coreWaterPoints(2 * BaseTerrain.STEP);
    /** 中心の判定に効く全部の点(15 × 15)。 */
    private static final List<int[]> CORE_POINTS = BaseNoise.gridPoints(CORE_SAMPLE_RADIUS);

    /**
     * 中心の判定({@link BasePlanner#coreCheck} を中心だけの標本でかけたのと同じ答え)を安く(2026-10-04、{@code /locate} を軽くするため):
     * 水を見る点のうち粗い 1/4 を先に読み、水があればそこで断る(水は起伏より先に決まり、粗い点は {@code coreCheck} が見る点の一部なので答えは変わらない)。
     * 無ければ判定に効く 15 × 15 点を読んでいつもの判定。本体のスレッド({@code /locate})からは点を並べて読む({@link BaseNoise#warm})。
     * GameTest {@code coreCheckRejectsWaterAndSlopes} が、ばらばらの地形 300 通りで {@code coreCheck} と同じ答えになることを確かめる。
     */
    public static int coreVerdict(BaseTerrain.Sampler noise, int maxRelief) {
        BaseNoise.warm(noise, WATER_COARSE);
        if (BasePlanner.anyWater(noise, WATER_COARSE)) {
            return BasePlanner.CORE_WATER;
        }
        BaseNoise.warm(noise, CORE_POINTS);
        return BasePlanner.coreCheck(BaseNoise.sampleCore(noise, CORE_SAMPLE_RADIUS), maxRelief);
    }

    private static Holder<Biome> biomeAt(GenerationContext context, int x, int y, int z) {
        return context.biomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z),
                context.randomState().sampler());
    }

    /**
     * 構造物の組 {@code key}(散らばって置かれるもの)が、このチャンクから {@code chunks} 以内に来うるか: 組の置き場の候補のうち、
     * どれかの構造物のバイオームに合うもの(バニラの判定と同じくチャンクの真ん中の地表で見る)。村の家が実際に建つかまでは見ない。
     */
    static boolean near(GenerationContext context, ResourceKey<StructureSet> key, int chunks) {
        if (chunks <= 0) {
            return false;
        }
        Optional<Holder.Reference<StructureSet>> set = context.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).getHolder(key);
        if (set.isEmpty() || !(set.get().value().placement() instanceof RandomSpreadStructurePlacement p)) {
            return false;
        }
        ChunkPos cp = context.chunkPos();
        int spacing = p.spacing();
        for (int gx = Math.floorDiv(cp.x - chunks, spacing); gx <= Math.floorDiv(cp.x + chunks, spacing); gx++) {
            for (int gz = Math.floorDiv(cp.z - chunks, spacing); gz <= Math.floorDiv(cp.z + chunks, spacing); gz++) {
                ChunkPos at = p.getPotentialStructureChunk(context.seed(), gx * spacing, gz * spacing);
                if (Math.abs(at.x - cp.x) > chunks || Math.abs(at.z - cp.z) > chunks) {
                    continue;
                }
                int bx = at.getMiddleBlockX();
                int bz = at.getMiddleBlockZ();
                int by = context.chunkGenerator().getBaseHeight(bx, bz, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(),
                        context.randomState());
                Holder<Biome> biome = biomeAt(context, bx, by, bz);
                for (StructureSet.StructureSelectionEntry e : set.get().value().structures()) {
                    if (e.structure().value().biomes().contains(biome)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 種類を重みで引く(重みが全部 0 なら大型防衛)。 */
    public static BaseType rollType(RandomSource rnd, DefenseTable t) {
        int total = 0;
        for (BaseType bt : BaseType.values()) {
            total += t.baseWorldgenTypeWeight(bt.id);
        }
        if (total <= 0) {
            return BaseType.DEFENSE;
        }
        int r = rnd.nextInt(total);
        for (BaseType bt : BaseType.values()) {
            r -= t.baseWorldgenTypeWeight(bt.id);
            if (r < 0) {
                return bt;
            }
        }
        return BaseType.DEFENSE;
    }

    /** レベル 1〜5 を重みで引く(重みが全部 0 なら同じ割合)。 */
    public static int rollLevel(RandomSource rnd, DefenseTable t) {
        int total = 0;
        for (int lv = 1; lv <= 5; lv++) {
            total += t.baseWorldgenLevelWeight(lv);
        }
        if (total <= 0) {
            return 1 + rnd.nextInt(5);
        }
        int r = rnd.nextInt(total);
        for (int lv = 1; lv <= 5; lv++) {
            r -= t.baseWorldgenLevelWeight(lv);
            if (r < 0) {
                return lv;
            }
        }
        return 5;
    }
}
