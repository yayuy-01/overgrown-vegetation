package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CoverCreep;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.CoverTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.minecraftforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * <b>記録で覆ったマスに覆いの絵を貼る</b>(2026-10-30。作り直し側 overgrown_vegetation/mod の {@code CoverRenderer} = D2 を手本に、
 * 前の {@code OvergrownLayerRenderer} + {@code OvergrownLayerQuads}(苔 1 枚を模型の面の形に切り抜いて貼る)を置き換えた)。
 *
 * <p>地形のセクションを組み立てるときに({@link AddSectionGeometryEvent}。Sodium 0.8 もこの口を呼ぶ)、覆われたマス
 * ({@link OvergrownLayerData} の記録)の模型の面を写して、面全体にユーザーの絵({@code textures/block/cover/cover_1..5})のどれかを
 * 貼った面({@link CoverQuads})を {@code cutout_mipped} の層に足す。隣に塞がれた面・明るさ・AO は
 * {@code ModelBlockRenderer.tesselateBlock} に任せる(元のブロックと同じ計算)。遠くの見え方は絵のミップで決まる
 * ({@code corruption/CoverMipmap}。遠くでも緑に塗りつぶさず元の色が透ける)。
 *
 * <h2>スレッド</h2>
 * <p>イベントは本スレッド、{@code render} は組み立てのスレッド。記録の写し({@link ClientOvergrownLayer#coveredCells})をイベント側で取って
 * 手渡し、組み立て側は記録そのものを読まない。
 *
 * <p><b>記録が空のセクションでは必ず {@code addRenderer} を呼ばない</b>(1 つでも登録すると、バニラはそのセクションの
 * {@code RenderChunkRegion} を隣のチャンク込みで作り直す)。汚染されていない世界では 1 回も登録されないこと。
 * 2026-10-31 から「記録が空で、かつ 1 ブロックの殻(下のセクションと横 4 つのチャンクの同じ高さ)にも覆われたマスが無い」に広げた
 * (這い上がる苔 = 覆われたマスに接する、覆われていないブロックの辺に覆いの絵の帯を描く。{@link CoverCreep}・{@link CreepModel})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CoverRenderer {

    /** 覆いを足す層。 */
    private static final RenderType LAYER = RenderType.cutoutMipped();

    private CoverRenderer() {
    }

    @SubscribeEvent
    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        CoverSprites.Set sprites = CoverSprites.forLevel(event.getLevel());
        if (sprites == null) {
            return;
        }
        Level level = event.getLevel();
        BlockPos origin = event.getSectionOrigin();
        int cx = SectionPos.blockToSectionCoord(origin.getX());
        int sy = SectionPos.blockToSectionCoord(origin.getY());
        int cz = SectionPos.blockToSectionCoord(origin.getZ());
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
            return;
        }
        int index = chunk.getSectionIndex(origin.getY());
        LevelChunkSection[] sections = chunk.getSections();
        if (index < 0 || index >= sections.length || sections[index].hasOnlyAir()) {
            return;
        }
        boolean own = OvergrownLayerData.size(chunk) > 0;
        long[] cells = own ? ClientOvergrownLayer.coveredCells(chunk, sy) : null;
        CoverTable design = DesignData.cover();
        // 這い上がる苔(28): 覆いに接して置かれたブロックの記録(2026-11-01 から。自然のブロックには出さない)
        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap creepMap =
                OvAttach.getExistingData(chunk, com.yayuy.overgrownvegetation.registry.ModAttachments.COVER_CREEP.get()).orElse(null);
        int[][] creep = creepMap == null || creepMap.isEmpty() ? null : gatherCreep(level, chunk, origin, cx, sy, cz, cells, creepMap, design);
        if (cells == null && creep == null) {
            return; // このセクションには覆いも這い上がる苔も無い。追加の描画は登録しない。
        }
        // origin はセクションの持ち物で書き換わるので、ここで固定して渡す。
        BlockPos fixedOrigin = origin.immutable();
        event.addRenderer(context -> render(context, fixedOrigin, cells, creep, sprites, design));
    }

    @Nullable
    private static long[] cellsOf(Level level, int cx, int cz, int sy) {
        LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
        return c == null || OvergrownLayerData.size(c) == 0 ? null : ClientOvergrownLayer.coveredCells(c, sy);
    }

    private static boolean bit(@Nullable long[] bits, int x, int y, int z) {
        if (bits == null) {
            return false;
        }
        int cell = (y << 8) | (z << 4) | x;
        return (bits[cell >> 6] & 1L << (cell & 63)) != 0L;
    }

    /**
     * 這い上がる苔を描くマス(本スレッド): このセクションの記録のうち、置いたときに覆われていた向きで今も隣が覆われているもの。
     * 隣は 1 ブロックの殻(下のセクション・横 4 つのチャンクの同じ高さ)の覆いも見る。{@code {マスの番号, 向きごとの段階(4 ビット × ordinal)}} の 2 列。
     * 段階は置いた時刻(ゲーム時刻)からの経過で({@link CoverCreep#stage})。1 つも無ければ null。
     */
    @Nullable
    private static int[][] gatherCreep(Level level, LevelChunk chunk, BlockPos origin, int cx, int sy, int cz, @Nullable long[] cells,
                                       it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap creepMap, CoverTable design) {
        long[][] shell = null;
        CoverCreep.Cells covered = null;
        it.unimi.dsi.fastutil.ints.IntArrayList at = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.ints.IntArrayList stages = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (var e : creepMap.long2LongEntrySet()) {
            long packed = e.getLongKey();
            int wy = BlockPos.getY(packed);
            if (wy >> 4 != sy) {
                continue;
            }
            if (covered == null) {
                long[] below = OvergrownLayerData.size(chunk) > 0 ? ClientOvergrownLayer.coveredCells(chunk, sy - 1) : null;
                shell = new long[][]{below, cellsOf(level, cx, cz - 1, sy), cellsOf(level, cx, cz + 1, sy), cellsOf(level, cx - 1, cz, sy),
                        cellsOf(level, cx + 1, cz, sy)};
                long[][] s = shell;
                covered = (x, y, z) -> {
                    if (y < 0) {
                        return bit(s[0], x, 15, z);
                    }
                    if (z < 0) {
                        return bit(s[1], x, y, 15);
                    }
                    if (z > 15) {
                        return bit(s[2], x, y, 0);
                    }
                    if (x < 0) {
                        return bit(s[3], 15, y, z);
                    }
                    if (x > 15) {
                        return bit(s[4], 0, y, z);
                    }
                    return y <= 15 && bit(cells, x, y, z);
                };
            }
            int x = BlockPos.getX(packed) & 15;
            int y = wy & 15;
            int z = BlockPos.getZ(packed) & 15;
            int recorded = com.yayuy.overgrownvegetation.corruption.CoverCreepData.sides(e.getLongValue());
            int mask = 0;
            if (!covered.covered(x, y, z)) {
                // 隣が記録で覆われているか、汚染ブロック(#corrupted の不透明なもの、11-01 ユーザー指定)なら
                for (Direction d : CoverCreep.FROM) {
                    if ((recorded & 1 << d.ordinal()) != 0 && (covered.covered(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())
                            || com.yayuy.overgrownvegetation.corruption.CoverCreepData.source(level.getBlockState(BlockPos.containing(
                            origin.getX() + x + d.getStepX(), origin.getY() + y + d.getStepY(), origin.getZ() + z + d.getStepZ()))))) {
                        mask |= 1 << d.ordinal();
                    }
                }
            }
            if (mask == 0) {
                continue;
            }
            int stage = CoverCreep.stage(level.getGameTime() - com.yayuy.overgrownvegetation.corruption.CoverCreepData.startTick(e.getLongValue()), design);
            int packedStages = 0;
            for (Direction d : CoverCreep.FROM) {
                if ((mask & 1 << d.ordinal()) != 0) {
                    packedStages |= stage << (d.ordinal() * 4);
                }
            }
            at.add((y << 8) | (z << 4) | x);
            stages.add(packedStages);
        }
        return at.isEmpty() ? null : new int[][]{at.toIntArray(), stages.toIntArray()};
    }

    /** セクション 1 つぶんの覆いと這い上がる苔の帯を描く(組み立てのスレッド)。 */
    private static void render(AddSectionGeometryEvent.SectionRenderingContext context, BlockPos origin, @Nullable long[] cells,
                               @Nullable int[][] creep, CoverSprites.Set sprites, CoverTable design) {
        BlockAndTintGetter region = context.getRegion();
        PoseStack pose = context.getPoseStack();
        Minecraft mc = Minecraft.getInstance();
        ModelBlockRenderer renderer = mc.getBlockRenderer().getModelRenderer();
        RandomSource random = RandomSource.create();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // バッファは 1 つでも覆うブロックが見つかってから作る(空のバッファを開かない)。
        VertexConsumer out = null;
        if (creep != null) {
            out = renderCreep(context, origin, creep, sprites, design, region, pose, renderer, random);
        }
        if (cells == null) {
            return;
        }
        // 小さいビットから順に読む = y → z → x の順。
        for (int word = 0; word < cells.length; word++) {
            for (long bits = cells[word]; bits != 0L; bits &= bits - 1L) {
                int cell = (word << 6) | Long.numberOfTrailingZeros(bits);
                p.set(origin.getX() + (cell & 15), origin.getY() + (cell >> 8), origin.getZ() + ((cell >> 4) & 15));
                BlockState state = region.getBlockState(p);
                // 記録にあっても、いまそこにあるブロックに覆われる資格が無ければ描かない(OvergrownLayerData#isCovered と同じ判定)。
                if (state.getRenderShape() != RenderShape.MODEL || !OvergrownLayerData.canCover(state)) {
                    continue;
                }
                if (out == null) {
                    out = context.getOrCreateChunkBuffer(LAYER);
                }
                BakedModel base = mc.getBlockRenderer().getBlockModel(state);
                long seed = state.getSeed(p);
                CoverModel model = new CoverModel(base, sprites, design, p.getX(), p.getY(), p.getZ(), seed);
                model.waterMask = waterMask(region, p, state);
                model.groundTop = sprites.groundTop() != null && state.is(com.yayuy.overgrownvegetation.ModTags.NETHER_COVER_GROUND_TOP);
                model.sea = model.waterMask != 0 && mc.level != null
                        && com.yayuy.overgrownvegetation.corruption.UnderwaterPlants.isSea(mc.level.getBiome(p));
                if (state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock) {
                    // 透けるブロックが下に何段重なっているか(0 = 窓の一番下。帯を一番下だけ厚くする、2026-11-03 ユーザー指定)。2 段目以上は 2 で打ち切り
                    BlockPos.MutableBlockPos below = p.mutable();
                    while (model.seeThroughDepth < 2
                            && region.getBlockState(below.move(Direction.DOWN)).getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock) {
                        model.seeThroughDepth++;
                    }
                }
                pose.pushPose();
                pose.translate(p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ());
                renderer.tesselateBlock(region, model, state, p, pose, out, true, random, seed, OverlayTexture.NO_OVERLAY,
                        ModelData.EMPTY, LAYER);
                pose.popPose();
            }
        }
    }

    /**
     * 水に接した面の向きのビット(2026-11-01、水中の藻の覆い)。自分が水を含んでいれば全部の面、でなければ隣のマスが水の面。
     */
    private static int waterMask(BlockAndTintGetter region, BlockPos p, BlockState state) {
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            return 0b111111;
        }
        int mask = 0;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            if (region.getFluidState(n.setWithOffset(p, d)).is(net.minecraft.tags.FluidTags.WATER)) {
                mask |= 1 << d.ordinal();
            }
        }
        return mask;
    }

    /**
     * 這い上がる苔の帯を描く(組み立てのスレッド)。帯を描くのは不透明なブロック({@code canOcclude}、仮決め: 草・柵・ガラスには描かない)の、
     * 覆いとして描かれている隣(資格のあるブロック)に接する辺だけ。返すのは開いたバッファ(開かなければ null)。
     */
    @Nullable
    private static VertexConsumer renderCreep(AddSectionGeometryEvent.SectionRenderingContext context, BlockPos origin, int[][] creep,
                                              CoverSprites.Set sprites, CoverTable design, BlockAndTintGetter region, PoseStack pose,
                                              ModelBlockRenderer renderer, RandomSource random) {
        Minecraft mc = Minecraft.getInstance();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        VertexConsumer out = null;
        for (int k = 0; k < creep[0].length; k++) {
            int cell = creep[0][k];
            p.set(origin.getX() + (cell & 15), origin.getY() + (cell >> 8), origin.getZ() + ((cell >> 4) & 15));
            BlockState state = region.getBlockState(p);
            if (!com.yayuy.overgrownvegetation.corruption.CoverCreepData.eligible(state)) {
                continue; // 汚染で置き換わった(知らせを出さない置き換えでは記録が残ることがある、Codex の指摘 11-01)・壊された
            }
            int stages = creep[1][k];
            for (Direction d : CoverCreep.FROM) {
                if ((stages >> (d.ordinal() * 4) & 15) == 0) {
                    continue;
                }
                BlockState neighbor = region.getBlockState(n.setWithOffset(p, d));
                if (!com.yayuy.overgrownvegetation.corruption.CoverCreepData.source(neighbor)
                        && (neighbor.getRenderShape() != RenderShape.MODEL || !OvergrownLayerData.canCover(neighbor))) {
                    stages &= ~(15 << (d.ordinal() * 4)); // 記録はあっても覆いとして描かれていない
                }
            }
            if (stages == 0) {
                continue;
            }
            if (out == null) {
                out = context.getOrCreateChunkBuffer(LAYER);
            }
            BakedModel base = mc.getBlockRenderer().getBlockModel(state);
            long seed = state.getSeed(p);
            CreepModel model = new CreepModel(base, sprites, design, p.getX(), p.getY(), p.getZ(), seed, stages);
            pose.pushPose();
            pose.translate(p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ());
            renderer.tesselateBlock(region, model, state, p, pose, out, true, random, seed, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, LAYER);
            pose.popPose();
        }
        return out;
    }

    /**
     * 元の模型の面に、覆われた隣に接する辺から這い上がる苔の段階の絵を面全体に貼る模型(28)。2026-11-01 から元の面の暗い写しは足さない
     * (同じ頂点で競ってちらついた。苔の指の間から見える面は覆われていないので明るいままでよい)。絵は {@code creepPush} だけ面の外へ出す。
     */
    private static final class CreepModel extends CoverModel {

        private final int stages;

        CreepModel(BakedModel base, CoverSprites.Set sprites, CoverTable design, int x, int y, int z, long seed, int stages) {
            super(base, sprites, design, x, y, z, seed);
            this.stages = stages;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random, ModelData data,
                                        @Nullable RenderType renderType) {
            List<BakedQuad> out = new ArrayList<>();
            for (RenderType layer : base.getRenderTypes(state, random, data)) {
                random.setSeed(seed);
                if (layer == RenderType.translucent()) {
                    continue;
                }
                for (BakedQuad q : base.getQuads(state, side, random, data, layer)) {
                    int drawn = 0;
                    for (Direction d : CoverCreep.FROM) {
                        int s = stages >> (d.ordinal() * 4) & 15;
                        if (s == 0 || !CoverCreep.bandOnFace(d, q.getDirection())) {
                            continue;
                        }
                        // 角で 2 辺が接しているときは 2 枚目を少しだけ外へ(競らせない)
                        BakedQuad strip = CoverQuads.creep(q, x, y, z, sprites, design, d, s, drawn++);
                        if (strip != null) {
                            out.add(strip);
                        }
                    }
                }
            }
            return out;
        }
    }

    /** 元の模型の面を、覆いの絵を貼った面に写して返す模型(1 ブロック 1 つ、組み立ての間だけ)。 */
    private static class CoverModel implements BakedModel {

        final BakedModel base;
        final CoverSprites.Set sprites;
        final CoverTable design;
        final int x;
        final int y;
        final int z;
        final long seed;
        /** 水に接した面(1 << 向き)と、海か(水中の絵の組を選ぶ。2026-11-01)。 */
        int waterMask;
        boolean sea;
        /** 透けるブロックが下に重なっている段数(0〜2、{@link com.yayuy.overgrownvegetation.corruption.CoverLook#seeThroughMossHeight})。 */
        int seeThroughDepth;
        /** 下地が上面だけの絵を使うブロックか(ネザーラックなど、2026-11-11 ネザーの覆い)。 */
        boolean groundTop;

        CoverModel(BakedModel base, CoverSprites.Set sprites, CoverTable design, int x, int y, int z, long seed) {
            this.base = base;
            this.sprites = sprites;
            this.design = design;
            this.x = x;
            this.y = y;
            this.z = z;
            this.seed = seed;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random, ModelData data,
                                        @Nullable RenderType renderType) {
            List<BakedQuad> out = new ArrayList<>();
            // 透けるブロック(ガラス板・色付きガラス板・鉄格子 = IronBarsBlock)は面の下 5〜6 割だけ苔、上はガラスのまま(2026-11-03)
            boolean seeThrough = state != null && state.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock;
            float mossHeight = seeThrough ? com.yayuy.overgrownvegetation.corruption.CoverLook.seeThroughMossHeight(x, y, z, seeThroughDepth) : 1.0F;
            for (RenderType layer : base.getRenderTypes(state, random, data)) {
                random.setSeed(seed);
                boolean flush = CoverQuads.drawnBeforeCover(layer);
                for (BakedQuad q : base.getQuads(state, side, random, data, layer)) {
                    if (seeThrough) {
                        out.add(CoverQuads.overlayLower(q, flush, x, y, z, sprites, design, mossHeight)); // ガラスは暗くしない
                        continue;
                    }
                    BakedQuad dark = CoverQuads.darkened(q, layer, design); // 苔の間から見える元の面を暗く(置き換えブロックの絵と揃える)
                    if (dark != null) {
                        out.add(dark);
                    }
                    out.add(CoverQuads.overlay(q, flush, x, y, z, sprites, design, waterMask, sea, groundTop));
                }
            }
            return out;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random) {
            return getQuads(state, side, random, ModelData.EMPTY, null);
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
            return ChunkRenderTypeSet.of(LAYER);
        }

        @Override
        public boolean useAmbientOcclusion() {
            return base.useAmbientOcclusion();
        }

        @Override
        public boolean isGui3d() {
            return false;
        }

        @Override
        public boolean usesBlockLight() {
            return base.usesBlockLight();
        }

        @Override
        public boolean isCustomRenderer() {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return base.getParticleIcon();
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }
    }
}
