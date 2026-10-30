package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CoverRenderer {

    /** 覆いを足す層。 */
    private static final RenderType LAYER = RenderType.cutoutMipped();

    private CoverRenderer() {
    }

    @SubscribeEvent
    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        CoverSprites.Set sprites = CoverSprites.current();
        if (sprites == null) {
            return;
        }
        Level level = event.getLevel();
        BlockPos origin = event.getSectionOrigin();
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(origin.getX()),
                SectionPos.blockToSectionCoord(origin.getZ()));
        if (chunk == null || OvergrownLayerData.size(chunk) == 0) {
            return; // このチャンクには覆われたマスが 1 つも無い。追加の描画は登録しない。
        }
        int index = chunk.getSectionIndex(origin.getY());
        LevelChunkSection[] sections = chunk.getSections();
        if (index < 0 || index >= sections.length || sections[index].hasOnlyAir()) {
            return;
        }
        long[] cells = ClientOvergrownLayer.coveredCells(chunk, SectionPos.blockToSectionCoord(origin.getY()));
        if (cells == null) {
            return;
        }
        // origin はセクションの持ち物で書き換わるので、ここで固定して渡す。
        BlockPos fixedOrigin = origin.immutable();
        CoverTable design = DesignData.cover();
        event.addRenderer(context -> render(context, fixedOrigin, cells, sprites, design));
    }

    /** セクション 1 つぶんの覆いを描く(組み立てのスレッド)。 */
    private static void render(AddSectionGeometryEvent.SectionRenderingContext context, BlockPos origin, long[] cells,
                               CoverSprites.Set sprites, CoverTable design) {
        BlockAndTintGetter region = context.getRegion();
        PoseStack pose = context.getPoseStack();
        Minecraft mc = Minecraft.getInstance();
        ModelBlockRenderer renderer = mc.getBlockRenderer().getModelRenderer();
        RandomSource random = RandomSource.create();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        // バッファは 1 つでも覆うブロックが見つかってから作る(空のバッファを開かない)。
        VertexConsumer out = null;
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
                pose.pushPose();
                pose.translate(p.getX() - origin.getX(), p.getY() - origin.getY(), p.getZ() - origin.getZ());
                renderer.tesselateBlock(region, model, state, p, pose, out, true, random, seed, OverlayTexture.NO_OVERLAY,
                        ModelData.EMPTY, LAYER);
                pose.popPose();
            }
        }
    }

    /** 元の模型の面を、覆いの絵を貼った面に写して返す模型(1 ブロック 1 つ、組み立ての間だけ)。 */
    private static final class CoverModel implements BakedModel {

        private final BakedModel base;
        private final CoverSprites.Set sprites;
        private final CoverTable design;
        private final int x;
        private final int y;
        private final int z;
        private final long seed;

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
            for (RenderType layer : base.getRenderTypes(state, random, data)) {
                random.setSeed(seed);
                boolean flush = CoverQuads.drawnBeforeCover(layer);
                for (BakedQuad q : base.getQuads(state, side, random, data, layer)) {
                    out.add(CoverQuads.overlay(q, flush, x, y, z, sprites, design));
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
