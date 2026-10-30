package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 風で揺らすブロックの模型に<b>揺れの印</b>を付ける(2026-10-15 夜。案 B の葉に続けて、ユーザー「茂み・シダ・蔦も同じ風で揺らして」)。
 * 揺らすのはシェーダー({@code assets/minecraft/shaders/include/ov_sway.glsl}・{@link LeafSway})で、ここはどの頂点をどれだけ動かすかを決めるだけ。
 *
 * <h2>印の付け方</h2>
 * 焼き上がった模型を包み、ブロックとして描くとき(状態がある呼び出し)だけ、面の頂点の色の透明度の下位に印を書く:
 * 255 − 透明度 = 1〜32 は「その重みで揺れる」(重み = (印 − 1) / 31)、33〜63 は「面に沿ってだけ揺れる」(壁の蔦。重み = (印 − 33) / 30)。
 * 壁の蔦には 2026-10-22 から 61〜63 = 「重み 1、面の法線が x / y / z 軸」を書く(Sodium の頂点に法線が無いため)。
 * シェーダーは印のある頂点だけを動かして透明度を 1 に戻すので、見た目は変わらない。NeoForge は焼いた面の透明度をチャンクまで運ぶ
 * ({@code VertexConsumer#putBulkData} の「Neo: also apply alpha that's coming from the baked quad」)。
 * 持ち物・手の模型は別の模型なので印は付かない(付くと半透明になる)。
 *
 * <p>前は葉だけを、テクスチャの位置(絵の座標を 0.05 ピクセル内側へ寄せた葉のモデル)で見分けていたが、焼くときにバニラが座標を
 * 少し中心へ寄せる(にじみ防止)うえ、絵ごとに uniform が要るので、シダ・蔦・茂みへ広げるのに向かず、こちらに替えた。
 *
 * <h2>重み(どこが動くか)</h2>
 * <ul>
 *     <li>葉・湿地の葉・茂み・光る蔦(垂れ下がる鎖): 全体が同じ重み 1(つながったブロックの間が割れないよう、動く量は位置だけで決める)。</li>
 *     <li>壁の蔦(這う蔦・棘の蔦): 重み 1、壁に沿ってだけ(壁へめり込まない)。</li>
 *     <li>地面の草(未熟な茂み): 根元 0 → 上 1。背の高いシダは下の段 0 → 0.5、上の段 0.5 → 1(段の境目が割れない)。</li>
 *     <li>巻きシダ: 茎からの水平の距離で、茎のそば 0 → 葉先 1(葉は茎から横へ広がる)。</li>
 *     <li>ぶら下がる草(滴る苔・垂れる触手・胞子の触手): 付け根(上)0 → 先(下)1(2026-10-16、ユーザー「垂れ苔と太い蔦も揺れるように」。
 *     同じ形の触手 2 つも一緒に)。</li>
 * </ul>
 * 太い蔓(リアナ)は縄を別の描画で描くので、ここではなく {@link CorruptedLianaRenderer} が同じ風で動かす。数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SwayModels {

    /** 揺れ方。 */
    enum Mode {
        /** 全体が同じ重み 1。 */
        WHOLE,
        /** 重み 1、面に沿ってだけ。 */
        PLANAR,
        /** 根元 0 → 上 1(ブロックの中の高さ)。 */
        GROUND,
        /** 2 段の草の下の段(0 → 0.5)。 */
        TALL_LOWER,
        /** 2 段の草の上の段(0.5 → 1)。 */
        TALL_UPPER,
        /** 茎からの水平の距離(茎のそば 0 → 葉先 1)。 */
        RADIAL,
        /** 上の面からぶら下がる草(付け根 = 上 0 → 先 = 下 1)。 */
        HANGING
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        int wrapped = 0;
        wrapped += wrap(models, ModBlocks.OVERGROWN_LEAVES, state -> Mode.WHOLE);
        wrapped += wrap(models, ModBlocks.OVERGROWN_SWAMP_LEAVES, state -> Mode.WHOLE);
        wrapped += wrap(models, ModBlocks.OVERGROWN_BUSH, state -> Mode.WHOLE);
        wrapped += wrap(models, ModBlocks.OVERGROWN_GLOW_VINES, state -> Mode.WHOLE);
        wrapped += wrap(models, ModBlocks.OVERGROWN_GLOW_VINES_PLANT, state -> Mode.WHOLE);
        wrapped += wrap(models, ModBlocks.OVERGROWN_CREEPING_VINE, state -> Mode.PLANAR);
        wrapped += wrap(models, ModBlocks.OVERGROWN_THORN_VINE, state -> Mode.PLANAR);
        wrapped += wrap(models, ModBlocks.UNDEVELOPED_BUSH, state -> Mode.GROUND);
        wrapped += wrap(models, ModBlocks.OVERGROWN_TALL_FERN, state ->
                state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                        ? Mode.TALL_UPPER : Mode.TALL_LOWER);
        wrapped += wrap(models, ModBlocks.OVERGROWN_CURLED_FERN, state -> Mode.RADIAL);
        for (var hanging : ModBlocks.hangingGrowthBlocks()) {
            wrapped += wrap(models, hanging, state -> Mode.HANGING);
        }
        OvergrownVegetation.LOGGER.debug("風で揺らす模型: {} 状態", wrapped);
    }

    private static int wrap(Map<ModelResourceLocation, BakedModel> models, Supplier<? extends Block> block,
                            java.util.function.Function<BlockState, Mode> mode) {
        int n = 0;
        for (BlockState state : block.get().getStateDefinition().getPossibleStates()) {
            ModelResourceLocation key = BlockModelShaper.stateToModelLocation(state);
            BakedModel base = models.get(key);
            if (base == null || base instanceof Swaying) {
                continue;
            }
            models.put(key, new Swaying(base, mode.apply(state)));
            n++;
        }
        return n;
    }

    /** 印を付けた面を返す模型。印を付けた面は元の面ごとに覚えて使い回す(チャンクを組むたびに作らない)。 */
    static final class Swaying extends BakedModelWrapper<BakedModel> {
        private final Mode mode;
        private final Map<BakedQuad, BakedQuad> marked = new ConcurrentHashMap<>();

        Swaying(BakedModel original, Mode mode) {
            super(original);
            this.mode = mode;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            return mark(state, super.getQuads(state, side, rand));
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData data,
                                        @Nullable RenderType renderType) {
            return mark(state, super.getQuads(state, side, rand, data, renderType));
        }

        private List<BakedQuad> mark(@Nullable BlockState state, List<BakedQuad> quads) {
            if (state == null || quads.isEmpty()) {
                return quads;
            }
            // 元の模型が毎回新しい面を作る場合に覚えた面が増え続けないよう、多すぎたら忘れる(ふつうは同じ面が返り、数百で止まる)。
            if (this.marked.size() > 4096) {
                this.marked.clear();
            }
            List<BakedQuad> out = new ArrayList<>(quads.size());
            for (BakedQuad quad : quads) {
                out.add(this.marked.computeIfAbsent(quad, q -> markQuad(q, this.mode)));
            }
            return out;
        }
    }

    /** 面の 4 頂点の透明度に印を書いた写しを作る(頂点は BLOCK 形式: 1 頂点 8 個の int、色は 4 個目)。 */
    static BakedQuad markQuad(BakedQuad quad, Mode mode) {
        int[] v = quad.getVertices().clone();
        int stride = v.length / 4;
        int planarCode = mode == Mode.PLANAR ? 61 + faceAxis(v, stride) : 0;
        for (int i = 0; i < 4; i++) {
            int at = i * stride;
            float x = Float.intBitsToFloat(v[at]);
            float y = Float.intBitsToFloat(v[at + 1]);
            float z = Float.intBitsToFloat(v[at + 2]);
            float weight = weight(mode, x, y, z);
            int code = mode == Mode.PLANAR ? planarCode : 1 + Math.round(weight * 31.0F);
            // 先に付いた植物の層の段(PlantLayers、64 / 128)は残す(2026-10-23)
            int alpha = 255 - (code + PlantLayers.groupOf(v[at + 3] >>> 24));
            v[at + 3] = (v[at + 3] & 0x00FFFFFF) | (alpha << 24);
        }
        return new BakedQuad(v, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }

    /**
     * 面の法線にいちばん近い軸(0 = x、1 = y、2 = z)。面の向きの印(61〜63)に使う —— Sodium の頂点には法線が無いので、
     * 「壁に沿ってだけ揺れる」の壁の向きを印で運ぶ(2026-10-22、Sodium 用の差し込み {@link SodiumTerrain})。
     */
    static int faceAxis(int[] v, int stride) {
        float ax = Float.intBitsToFloat(v[stride]) - Float.intBitsToFloat(v[0]);
        float ay = Float.intBitsToFloat(v[stride + 1]) - Float.intBitsToFloat(v[1]);
        float az = Float.intBitsToFloat(v[stride + 2]) - Float.intBitsToFloat(v[2]);
        float bx = Float.intBitsToFloat(v[2 * stride]) - Float.intBitsToFloat(v[0]);
        float by = Float.intBitsToFloat(v[2 * stride + 1]) - Float.intBitsToFloat(v[1]);
        float bz = Float.intBitsToFloat(v[2 * stride + 2]) - Float.intBitsToFloat(v[2]);
        float nx = Math.abs(ay * bz - az * by);
        float ny = Math.abs(az * bx - ax * bz);
        float nz = Math.abs(ax * by - ay * bx);
        return nx >= ny && nx >= nz ? 0 : (ny >= nz ? 1 : 2);
    }

    /** 頂点の重み(0〜1)。位置はブロックの中の座標(0〜1、模型によってははみ出す)。 */
    static float weight(Mode mode, float x, float y, float z) {
        float h = Math.max(0.0F, Math.min(1.0F, y));
        return switch (mode) {
            case WHOLE, PLANAR -> 1.0F;
            case GROUND -> h;
            case HANGING -> 1.0F - h;
            case TALL_LOWER -> 0.5F * h;
            case TALL_UPPER -> 0.5F + 0.5F * h;
            case RADIAL -> {
                float dx = x - 0.5F;
                float dz = z - 0.5F;
                float r = (float) Math.sqrt(dx * dx + dz * dz);
                yield Math.max(0.0F, Math.min(1.0F, (r - 0.05F) / 0.6F));
            }
        };
    }

    private SwayModels() {
    }
}
