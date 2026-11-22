package com.yayuy.overgrownvegetation.client.atmosphere;

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
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 風で揺らすブロックの模型に<b>揺れの印</b>を付ける(2026-10-15 夜。案 B の葉に続けて、ユーザー「茂み・シダ・蔦も同じ風で揺らして」)。
 * 2026-10-30 夜 第 2 段「動き」で parked から client/atmosphere へ戻した(中身は同じ)。
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
 * 太い蔓(リアナ)は縄を別の描画で描くので、ここではなく {@code client.CorruptedLianaRenderer} が同じ風で動かす。数値はすべて仮決め。
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
        /** 3 段の株(シャジクモ)の下・中・上の段(0 → 1/3、1/3 → 2/3、2/3 → 1)。 */
        THIRD_LOW,
        THIRD_MID,
        THIRD_TOP,
        /** 茎からの水平の距離(茎のそば 0 → 葉先 1)。 */
        RADIAL,
        /** 上の面からぶら下がる草(付け根 = 上 0 → 先 = 下 1)。 */
        HANGING,
        /** 2 段の垂れ物の上の段(付け根 0 → 0.5)と下の段(0.5 → 1)。ひげ地衣(2026-11-01)。 */
        HANG_UPPER,
        HANG_LOWER,
        /**
         * 水の中の草(2026-11-13、水まわり ⑥ ユーザー選択「水草が流れに揺れる」): 風ではなく水のゆっくりしたふらつき(胞子昆布の葉と同じ
         * {@code ov_sway.glsl} の印 53〜60)。重みは根元 0 → 上 1・2 段の下と上・3 段・全体 1。
         */
        WATER_GROUND,
        WATER_LOWER,
        WATER_UPPER,
        WATER_THIRD_LOW,
        WATER_THIRD_MID,
        WATER_THIRD_TOP,
        WATER_WHOLE;

        boolean water() {
            return ordinal() >= WATER_GROUND.ordinal();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        int wrapped = 0;
        wrapped += wrap(models, ModBlocks.OVERGROWN_LEAVES, state -> Mode.WHOLE);
        wrapped += wrap(models, ModBlocks.GIANT_CANOPY_LEAVES, state -> Mode.WHOLE); // 巨大樹の葉(11-16。コルダイテスの葉と同じ揺れ)
        wrapped += wrap(models, ModBlocks.OVERGROWN_BUSH, state -> Mode.WHOLE);
        wrapped += wrap(models, ModBlocks.OVERGROWN_GLOW_VINES, state -> Mode.WHOLE);
        wrapped += wrap(models, ModBlocks.OVERGROWN_GLOW_VINES_PLANT, state -> Mode.WHOLE);
        wrapped += wrap(models, ModBlocks.WALL_LYCOPSID, state -> Mode.PLANAR);
        wrapped += wrap(models, ModBlocks.HANGING_MOSS, state -> Mode.HANGING);
        wrapped += wrap(models, ModBlocks.UNDEVELOPED_BUSH, state -> Mode.GROUND);
        wrapped += wrap(models, ModBlocks.OVERGROWN_TALL_FERN, state ->
                state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                        ? Mode.TALL_UPPER : Mode.TALL_LOWER);
        wrapped += wrap(models, ModBlocks.OVERGROWN_CURLED_FERN, state -> Mode.RADIAL);
        // シャジクモ(2026-11-01): 株の高さごとの段で重みをつなぐ(1 段 = 根元 0 → 先 1)
        // 11-13(水まわり ⑥): 水の中の草なので、風ではなく水の揺れに(前は陸の草と同じ風の揺れ)。
        wrapped += wrap(models, ModBlocks.CHARA, state -> switch (state.getValue(com.yayuy.overgrownvegetation.block.CharaBlock.PART)) {
            case SINGLE -> Mode.WATER_GROUND;
            case BOTTOM_OF_2 -> Mode.WATER_LOWER;
            case TOP_OF_2 -> Mode.WATER_UPPER;
            case BOTTOM_OF_3 -> Mode.WATER_THIRD_LOW;
            case MIDDLE_OF_3 -> Mode.WATER_THIRD_MID;
            case TOP_OF_3 -> Mode.WATER_THIRD_TOP;
        });
        // バニラの水草(11-13、水まわり ⑥ ユーザー選択「水草が流れに揺れる」): 海草は根元 0 → 先 1、背の高い海草は 2 段、昆布は全体 1
        // (昆布は縦に積む柱なので、どの段も同じ重みにして段の境目を割らない)。
        wrapped += wrap(models, () -> net.minecraft.world.level.block.Blocks.SEAGRASS, state -> Mode.WATER_GROUND);
        wrapped += wrap(models, () -> net.minecraft.world.level.block.Blocks.TALL_SEAGRASS, state ->
                state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? Mode.WATER_UPPER : Mode.WATER_LOWER);
        wrapped += wrap(models, () -> net.minecraft.world.level.block.Blocks.KELP, state -> Mode.WATER_WHOLE);
        wrapped += wrap(models, () -> net.minecraft.world.level.block.Blocks.KELP_PLANT, state -> Mode.WATER_WHOLE);
        for (var hanging : ModBlocks.hangingGrowthBlocks()) {
            wrapped += wrap(models, hanging, state -> Mode.HANGING);
        }
        // 11-01 夜(見え方の直し 1): 封印木・木生シダの芯の葉は今の葉と同じ(全体が重み 1)。外側の板の葉と胞子昆布の葉は向きに沿って({@link Fringe})。
        wrapped += wrap(models, ModBlocks.SIGILLARIA_LEAVES, state -> Mode.WHOLE);
        wrapped += wrap(models, ModBlocks.TREE_FERN_LEAVES, state -> Mode.WHOLE);
        wrapped += wrapFringe(models, ModBlocks.SIGILLARIA_LEAVES_FRINGE, false);
        wrapped += wrapFringe(models, ModBlocks.OVERGROWN_LEAVES_TUFT, false);
        wrapped += wrapFringe(models, ModBlocks.TREE_FERN_FROND, false);
        wrapped += wrapFringe(models, ModBlocks.SPORE_KELP_FROND, true);
        // 側面・底面(2026-11-01): 気根の鎖と胞子嚢は光る蔦と同じ全体 1(段の境目が割れない。付け根の葉も揺れる)、ひげ地衣は付け根 0 → 先 1。
        wrapped += wrap(models, ModBlocks.AERIAL_ROOT, state -> Mode.WHOLE);
        wrapped += wrap(models, ModBlocks.HANGING_SPORE_SAC, state -> Mode.HANGING);
        wrapped += wrap(models, ModBlocks.BEARD_LICHEN, state -> switch (state.getValue(com.yayuy.overgrownvegetation.block.BeardLichenBlock.PART)) {
            case SHORT -> Mode.HANGING;
            case TOP -> Mode.HANG_UPPER;
            case TIP -> Mode.HANG_LOWER;
        });
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

    private static int wrapFringe(Map<ModelResourceLocation, BakedModel> models, Supplier<? extends Block> block, boolean kelp) {
        int n = 0;
        for (BlockState state : block.get().getStateDefinition().getPossibleStates()) {
            ModelResourceLocation key = BlockModelShaper.stateToModelLocation(state);
            BakedModel base = models.get(key);
            if (base == null || base instanceof Fringe) {
                continue;
            }
            models.put(key, new Fringe(base, state, kelp));
            n++;
        }
        return n;
    }

    /** 板の葉の隣の様子(チャンクを組むときに {@link Fringe#getModelData} が読む)。 */
    private static final ModelProperty<Integer> NEIGHBOURS = new ModelProperty<>();
    /** 付け根の芯が揺れる葉(芯の葉。原木・枝・柱なら揺れない)。 */
    private static final int CORE_MOVES = 1;
    /** 垂れる昆布の葉の下にもう 1 枚垂れている(この葉は上の 1 枚)。 */
    private static final int HANG_BELOW = 2;
    /** 垂れる昆布の葉の上にもう 1 枚垂れている(この葉は下の 1 枚)。 */
    private static final int HANG_ABOVE = 4;

    /**
     * 封印木・木生シダの外側の板の葉と胞子昆布の葉(2026-11-01 夜 見え方の直し 1)。印は付け根 0 → 先 1 の位置 t と揺れ方({@code ov_sway.glsl} の 33〜60):
     * 立ち上がる房・垂れる葉 = 先ほど風で余分に曲がる、横・角の板 = 先が上下する、昆布 = 風によらない水の揺れ。付け根が芯の葉なら芯と一緒に動き、
     * 原木・枝・柱なら止まる(隣は {@link #getModelData} で見る)。昆布の垂れる葉が 2 枚続くときは上の 1 枚を 0 → 0.5、下を 0.5 → 1 にしてつなぐ。
     */
    static final class Fringe extends BakedModelWrapper<BakedModel> {
        private final Direction facing;
        private final boolean corner;
        private final boolean kelp;
        @SuppressWarnings("unchecked")
        private final Map<BakedQuad, BakedQuad>[] marked = new Map[8];

        Fringe(BakedModel original, BlockState state, boolean kelp) {
            super(original);
            this.facing = state.getValue(com.yayuy.overgrownvegetation.block.SigillariaFringeBlock.FACING);
            this.corner = state.getValue(com.yayuy.overgrownvegetation.block.SigillariaFringeBlock.CORNER);
            this.kelp = kelp;
            for (int i = 0; i < this.marked.length; i++) {
                this.marked[i] = new ConcurrentHashMap<>();
            }
        }

        @Override
        public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
            int flags = 0;
            BlockState core = level.getBlockState(pos.relative(this.facing));
            if (core.getBlock() instanceof com.yayuy.overgrownvegetation.block.OvergrownLeavesBlock
                    && !(core.getBlock() instanceof com.yayuy.overgrownvegetation.block.SigillariaFringeBlock)) {
                flags |= CORE_MOVES;
            }
            if (this.kelp && this.facing == Direction.UP) {
                if (isHangingFrond(level.getBlockState(pos.below()), state)) {
                    flags |= HANG_BELOW;
                }
                if (isHangingFrond(core, state)) {
                    flags |= HANG_ABOVE;
                }
            }
            return modelData.derive().with(NEIGHBOURS, flags).build();
        }

        private static boolean isHangingFrond(BlockState other, BlockState self) {
            return other.is(self.getBlock()) && other.getValue(com.yayuy.overgrownvegetation.block.SigillariaFringeBlock.FACING) == Direction.UP;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            return mark(state, super.getQuads(state, side, rand), 0);
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData data,
                                        @Nullable RenderType renderType) {
            Integer flags = data.get(NEIGHBOURS);
            return mark(state, super.getQuads(state, side, rand, data, renderType), flags != null ? flags & 7 : 0);
        }

        private List<BakedQuad> mark(@Nullable BlockState state, List<BakedQuad> quads, int flags) {
            if (state == null || quads.isEmpty()) {
                return quads;
            }
            Map<BakedQuad, BakedQuad> memo = this.marked[flags];
            if (memo.size() > 4096) {
                memo.clear();
            }
            List<BakedQuad> out = new ArrayList<>(quads.size());
            for (BakedQuad quad : quads) {
                out.add(memo.computeIfAbsent(quad, q -> markFringe(q, flags)));
            }
            return out;
        }

        private BakedQuad markFringe(BakedQuad quad, int flags) {
            int[] v = quad.getVertices().clone();
            int stride = v.length / 4;
            boolean moves = (flags & CORE_MOVES) != 0;
            for (int i = 0; i < 4; i++) {
                int at = i * stride;
                float t = tip(Float.intBitsToFloat(v[at]), Float.intBitsToFloat(v[at + 1]), Float.intBitsToFloat(v[at + 2]), flags);
                int code;
                if (this.kelp) {
                    code = 53 + Math.round(t * 7.0F);
                } else if (this.facing.getAxis() == Direction.Axis.Y) {
                    code = (moves ? 33 : 39) + Math.round(t * 5.0F);
                } else {
                    code = (moves ? 45 : 49) + Math.round(t * 3.0F);
                }
                v[at + 3] = (v[at + 3] & 0x00FFFFFF) | ((255 - code) << 24);
            }
            return new BakedQuad(v, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
        }

        /** 頂点の付け根からの位置(0 = 付け根、1 = 先)。位置はブロックの中の座標(blockstate の回しは済んでいる)。 */
        private float tip(float x, float y, float z, int flags) {
            float h = Math.max(0.0F, Math.min(1.0F, y));
            float t = switch (this.facing) {
                case DOWN -> h;
                case UP -> {
                    float down = 1.0F - h;
                    if ((flags & HANG_ABOVE) != 0) {
                        yield 0.5F + 0.5F * down;
                    }
                    yield (flags & HANG_BELOW) != 0 ? 0.5F * down : down;
                }
                default -> {
                    if (this.corner) {
                        // 芯の縦の辺(north なら北西の角)からの水平の距離。
                        float cx = this.facing == Direction.EAST || this.facing == Direction.SOUTH ? 1.0F : 0.0F;
                        float cz = this.facing == Direction.SOUTH || this.facing == Direction.WEST ? 1.0F : 0.0F;
                        float dx = x - cx;
                        float dz = z - cz;
                        yield (float) Math.sqrt(dx * dx + dz * dz) / 1.2F;
                    }
                    yield switch (this.facing) {
                        case NORTH -> z;
                        case SOUTH -> 1.0F - z;
                        case WEST -> x;
                        default -> 1.0F - x;
                    };
                }
            };
            return Math.max(0.0F, Math.min(1.0F, t));
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
            int code = mode == Mode.PLANAR ? planarCode : (mode.water() ? 53 + Math.round(weight * 7.0F) : 1 + Math.round(weight * 31.0F));
            int alpha = 255 - (code);
            v[at + 3] = (v[at + 3] & 0x00FFFFFF) | (alpha << 24);
        }
        return new BakedQuad(v, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }

    /**
     * 面の法線にいちばん近い軸(0 = x、1 = y、2 = z)。面の向きの印(61〜63)に使う —— Sodium の頂点には法線が無いので、
     * 「壁に沿ってだけ揺れる」の壁の向きを印で運ぶ(2026-10-22、Sodium 用の差し込み {@code client.shader.SodiumTerrain})。
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
            case WHOLE, PLANAR, WATER_WHOLE -> 1.0F;
            case GROUND, WATER_GROUND -> h;
            case WATER_LOWER -> 0.5F * h;
            case WATER_UPPER -> 0.5F + 0.5F * h;
            case WATER_THIRD_LOW -> h / 3.0F;
            case WATER_THIRD_MID -> (1.0F + h) / 3.0F;
            case WATER_THIRD_TOP -> (2.0F + h) / 3.0F;
            case HANGING -> 1.0F - h;
            case HANG_UPPER -> 0.5F * (1.0F - h);
            case HANG_LOWER -> 0.5F + 0.5F * (1.0F - h);
            case TALL_LOWER -> 0.5F * h;
            case TALL_UPPER -> 0.5F + 0.5F * h;
            case THIRD_LOW -> h / 3.0F;
            case THIRD_MID -> (1.0F + h) / 3.0F;
            case THIRD_TOP -> (2.0F + h) / 3.0F;
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
