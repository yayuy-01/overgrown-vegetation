package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>覆われたマスに苔を被せる</b>(2026-09-22、要望)。
 *
 * <h2>何をしているのか</h2>
 * <p>汚染の対応表に変換先が無いブロックのうち<b>中身を持たないもの</b>(金床・砥石・作業台・
 * コンクリート・ガラスなど)は<b>置き換えていない</b>。元のブロックの
 * <b>模型の面をそのままなぞって</b>{@code overgrown_layer} のテクスチャを貼り直す
 * ({@link OvergrownLayerQuads})。立方体でなくても —— 階段でも尖った鍾乳石でも金床でも ——
 * その形のまま苔に覆われる。
 *
 * <h2>どこに被せるかは「記録」だけで決める</h2>
 * <p>{@link OvergrownLayerData} —— サーバーが<b>汚染が実際に届いたマス</b>を控えて送ってくる。
 * バイオームや座標のハッシュから推し量っていた頃は、
 * <b>侵食がまだ来ていない地下の鍾乳石にまで苔が乗っていた</b>。
 *
 * <h2>中身を持つブロックはここではない</h2>
 * <p>他 MOD の機械をここで覆うと、<b>植物に覆われているのに歯車が回り続ける</b>。
 * その手のブロックは描画ではなく<b>本当に差し替える</b> ——
 * {@code block/OvergrownHuskBlock}(見た目は同じ・機能は無い・中身は保持)。
 *
 * <h2>スレッド</h2>
 * <p>{@link AddSectionGeometryEvent} は<b>メインスレッド</b>で飛ぶ(セクションの再構築を
 * 積むところ)。実際に描く {@code render} の中は<b>ワーカースレッド</b>なので、
 * 記録の有無はイベント側で確かめて、空なら何も登録しない。
 *
 * <p><b>記録が空のセクションでは必ず {@code addRenderer} を呼ばない。</b>
 * 追加の描画を 1 つでも登録すると、バニラはそのセクションの
 * {@code RenderChunkRegion} を「隣接チャンク込み」で作り直す(軽い単一チャンク版を使わなくなる)。
 * 汚染されていない世界では 1 回も登録されないこと。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class OvergrownLayerRenderer {

    private OvergrownLayerRenderer() {
    }

    @SubscribeEvent
    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        Level level = event.getLevel();
        BlockPos origin = event.getSectionOrigin();
        LevelChunk chunk = level.getChunk(SectionPos.blockToSectionCoord(origin.getX()),
                SectionPos.blockToSectionCoord(origin.getZ()));
        if (OvergrownLayerData.size(chunk) == 0) {
            return; // このチャンクには覆われたマスが 1 つも無い。追加の描画は登録しない。
        }
        int index = chunk.getSectionIndex(origin.getY());
        LevelChunkSection[] sections = chunk.getSections();
        if (index < 0 || index >= sections.length || sections[index].hasOnlyAir()) {
            return; // 空のセクション。
        }
        event.addRenderer(context -> render(context, chunk, origin));
    }

    /** セクション 1 つぶんの苔を描く(ワーカースレッド)。 */
    private static void render(AddSectionGeometryEvent.SectionRenderingContext context,
                               LevelChunk chunk, BlockPos origin) {
        BlockAndTintGetter region = context.getRegion();
        PoseStack pose = context.getPoseStack();
        RandomSource random = RandomSource.create();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // バッファは 1 つでも覆うブロックが見つかってから作る(空のバッファを開かない)。
        VertexConsumer buffer = null;

        for (int dy = 0; dy < 16; dy++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = region.getBlockState(pos);
                    if (!OvergrownLayerData.isCovered(chunk, pos, state)) {
                        continue;
                    }
                    if (buffer == null) {
                        buffer = context.getOrCreateChunkBuffer(RenderType.cutoutMipped());
                    }
                    pose.pushPose();
                    pose.translate(SectionPos.sectionRelative(pos.getX()),
                            SectionPos.sectionRelative(pos.getY()),
                            SectionPos.sectionRelative(pos.getZ()));
                    dressBlock(region, pos, state, random, pose, buffer);
                    pose.popPose();
                }
            }
        }
    }

    /**
     * そのブロックの模型の面をなぞって、苔のテクスチャを貼った面を積む。
     *
     * <p><b>立方体でなければテクスチャの形に切り抜く</b>(2026-09-23、報告)——
     * 松明や尖った鍾乳石は 16x16 の板にほとんど透明なテクスチャが貼ってあるだけなので、
     * 面をそのままなぞると<b>何も無い空中に苔の板</b>が浮く。
     * ガラスや葉(立方体)は透明でも「向こうが透けている」だけなので、そのまま全面を覆う
     * ({@link OvergrownLayerQuads} に理由)。
     */
    private static void dressBlock(BlockAndTintGetter region, BlockPos pos, BlockState state,
                                   RandomSource random, PoseStack pose, VertexConsumer buffer) {
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        long seed = state.getSeed(pos);
        boolean mask = !OvergrownLayerField.isPlainCube(region, pos, state);
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.values()) {
            neighbour.setWithOffset(pos, direction);
            if (!Block.shouldRenderFace(state, region, pos, direction, neighbour)) {
                continue; // 隣のブロックに塞がれている面。元の模型と同じ基準で間引く。
            }
            random.setSeed(seed);
            emit(region, state, model.getQuads(state, direction, random, ModelData.EMPTY, null),
                    neighbour, mask, pose, buffer);
        }
        // 向きを持たない面(交差した草の形など、隣で間引けない面)。
        random.setSeed(seed);
        emit(region, state, model.getQuads(state, null, random, ModelData.EMPTY, null),
                pos, mask, pose, buffer);
    }

    private static void emit(BlockAndTintGetter region, BlockState state, List<BakedQuad> quads,
                             BlockPos lightPos, boolean mask,
                             PoseStack pose, VertexConsumer buffer) {
        if (quads.isEmpty()) {
            return;
        }
        List<BakedQuad> dressed = new ArrayList<>(quads.size());
        OvergrownLayerQuads.dressAll(quads, mask, dressed);
        if (dressed.isEmpty()) {
            return;
        }
        int light = LevelRenderer.getLightColor(region, state, lightPos);
        for (BakedQuad quad : dressed) {
            float shade = region.getShade(quad.getDirection(), quad.isShade());
            buffer.putBulkData(pose.last(), quad,
                    shade, shade, shade, 1.0F, light, OverlayTexture.NO_OVERLAY);
        }
    }
}
