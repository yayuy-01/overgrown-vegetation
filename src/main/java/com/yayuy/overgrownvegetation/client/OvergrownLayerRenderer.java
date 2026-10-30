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
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
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
 * <p><b>ワーカースレッドは記録そのものを読まない</b>(2026-09-24)。記録はメインスレッドが書き換える
 * {@code LongOpenHashSet} なので、イベント側でそのセクションぶんの写し
 * ({@link ClientOvergrownLayer#coveredCells})を受け取り、{@code render} へ手渡す。
 * {@code render} は写しにあるマスだけを、以前の 16x16x16 の走査と<b>同じ順(y → z → x)</b>で見て、
 * 同じ判定({@link OvergrownLayerData#canCover})で描く。覆われたマスの無いセクションには登録しない。
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
        long[] cells = ClientOvergrownLayer.coveredCells(chunk, SectionPos.blockToSectionCoord(origin.getY()));
        if (cells == null) {
            return; // このセクションには覆われたマスが無い。
        }
        // origin はセクションの持ち物で書き換わる(動かされたときは積み直しごと取り消される)ので、ここで固定して渡す。
        BlockPos fixedOrigin = origin.immutable();
        event.addRenderer(context -> render(context, fixedOrigin, cells));
    }

    /**
     * セクション 1 つぶんの苔を描く(ワーカースレッド)。
     *
     * @param cells メインスレッドで写した、このセクションの覆われたマス({@link ClientOvergrownLayer#coveredCells})
     */
    private static void render(AddSectionGeometryEvent.SectionRenderingContext context,
                               BlockPos origin, long[] cells) {
        BlockAndTintGetter region = context.getRegion();
        PoseStack pose = context.getPoseStack();
        RandomSource random = RandomSource.create();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // バッファは 1 つでも覆うブロックが見つかってから作る(空のバッファを開かない)。
        VertexConsumer buffer = null;

        // 小さいビットから順に読む = y → z → x の順(以前の 16x16x16 の走査と同じ順に面を積む)。
        for (int word = 0; word < cells.length; word++) {
            for (long bits = cells[word]; bits != 0L; bits &= bits - 1L) {
                int cell = (word << 6) | Long.numberOfTrailingZeros(bits);
                pos.set(origin.getX() + (cell & 15), origin.getY() + (cell >> 8), origin.getZ() + ((cell >> 4) & 15));
                BlockState state = region.getBlockState(pos);
                // 記録にあっても、いまそこにあるブロックに覆われる資格が無ければ描かない
                // ({@link OvergrownLayerData#isCovered} がクライアントでする判定と同じ)。
                if (!OvergrownLayerData.canCover(state)) {
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
        // 汚染したスポナーは檻の棒の上だけに苔を置く(檻の隙間を埋めると中で回る寄生体が見えない。2026-10-09)。
        boolean mask = com.yayuy.overgrownvegetation.corruption.SpawnerCorruption.isSpawner(state)
                || !OvergrownLayerField.isPlainCube(region, pos, state);
        random.setSeed(seed);
        boolean flush = drawnBeforeMoss(model.getRenderTypes(state, random, ModelData.EMPTY));
        // 模型が同じ並びを返し続けるなら、苔の面の並びを覚えて使い回す(2026-10-10、CoveredBlockModels.DressedLists)。
        boolean stable = CoveredBlockModels.DressedLists.stableLists(model);
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.values()) {
            neighbour.setWithOffset(pos, direction);
            if (!Block.shouldRenderFace(state, region, pos, direction, neighbour)) {
                continue; // 隣のブロックに塞がれている面。元の模型と同じ基準で間引く。
            }
            random.setSeed(seed);
            emit(region, state, model.getQuads(state, direction, random, ModelData.EMPTY, null),
                    neighbour, mask, flush, stable, pose, buffer);
        }
        // 向きを持たない面(交差した草の形など、隣で間引けない面)。
        random.setSeed(seed);
        emit(region, state, model.getQuads(state, null, random, ModelData.EMPTY, null),
                pos, mask, flush, stable, pose, buffer);
    }

    /**
     * 元のブロックの面が、苔({@code cutoutMipped} の溜め場所の、ブロックを積み終えた後ろ)より<b>先に描かれる</b>か
     * —— {@code solid} は層ごと先、{@code cutoutMipped} は同じ溜め場所の前。そうなら苔を押し出さずに同じ位置へ重ねられる
     * (2026-10-07、遠くでちかちかした件。{@link OvergrownLayerQuads} の {@code PUSH})。
     * ガラスのような {@code cutout}・{@code translucent} は苔の後に描かれるので、重ねると元の面が上に出る —— 今までどおり押し出す。
     */
    private static boolean drawnBeforeMoss(ChunkRenderTypeSet types) {
        for (RenderType type : types) {
            if (type != RenderType.solid() && type != RenderType.cutoutMipped()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 元の面の並びから苔の面を作って積む。{@code stable} なら元の面 + 苔の面を覚えた並び
     * ({@link CoveredBlockModels.DressedLists})を引いて、その後ろ(苔の面)だけを積む —— 積む面と順番は毎回作るのと同じ。
     */
    private static void emit(BlockAndTintGetter region, BlockState state, List<BakedQuad> quads,
                             BlockPos lightPos, boolean mask, boolean flush, boolean stable,
                             PoseStack pose, VertexConsumer buffer) {
        if (quads.isEmpty()) {
            return;
        }
        List<BakedQuad> dressed;
        int from;
        if (stable) {
            dressed = CoveredBlockModels.DressedLists.of(quads, mask, flush);
            from = quads.size(); // 先頭は元の面
        } else {
            dressed = new ArrayList<>(quads.size());
            OvergrownLayerQuads.dressAll(quads, mask, flush, dressed);
            from = 0;
        }
        if (dressed.size() == from) {
            return;
        }
        int light = LevelRenderer.getLightColor(region, state, lightPos);
        for (int i = from; i < dressed.size(); i++) {
            BakedQuad quad = dressed.get(i);
            float shade = region.getShade(quad.getDirection(), quad.isShade());
            buffer.putBulkData(pose.last(), quad,
                    shade, shade, shade, 1.0F, light, OverlayTexture.NO_OVERLAY);
        }
    }
}
