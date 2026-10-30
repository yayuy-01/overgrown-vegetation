package com.yayuy.overgrownvegetation.client;

import com.google.common.collect.MapMaker;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerField;
import com.yayuy.overgrownvegetation.covered.CoveredBlocks;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.util.TriState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 覆われたブロック({@link CoveredBlocks})の見た目(2026-10-05)。
 *
 * <p>blockstate は全部の状態を仮の模型({@code block/covered_placeholder})へ向けてあり、焼き上がったあとで
 * <b>元のブロックの同じ状態の模型 + 苔の面</b>に差し替える。苔の面は汚染が対応表に無いブロックを覆うときと同じ
 * {@link OvergrownLayerQuads} で作るので、汚染で覆われたバニラのブロックと見分けが付かない。
 * 立方体なら全面、そうでなければ元のテクスチャの色の付いた画素の上だけ(ドアの窓・鉄格子の隙間は抜ける)。
 *
 * <p>描画層は覆われて動かなくなったブロック({@link OvergrownHuskModel})と同じく {@code cutoutMipped} 1 つに寄せる
 * —— 面を 1 回だけ借りて、その同じ面から苔を作るため(理由は向こうの説明)。
 *
 * <p>持ち物も同じ形で差し替える。{@link BakedModelWrapper} の {@code applyTransform} と {@code getRenderPasses} は
 * <b>元の模型を返す</b>ので、そのままだと手や棚では苔が消える —— 両方とも自分を返すように上書きしてある。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CoveredBlockModels {

    private static final ChunkRenderTypeSet RENDER_TYPES = ChunkRenderTypeSet.of(RenderType.cutoutMipped());

    private CoveredBlockModels() {
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        int states = 0;
        int missing = 0;
        for (CoveredBlocks.Entry entry : CoveredBlocks.entries()) {
            Block covered = entry.block().get();
            for (BlockState state : covered.getStateDefinition().getPossibleStates()) {
                BlockState vanilla = CoveredBlocks.vanillaState(state);
                BakedModel base = models.get(BlockModelShaper.stateToModelLocation(vanilla));
                if (base == null) {
                    missing++;
                    continue;
                }
                models.put(BlockModelShaper.stateToModelLocation(state), new CoveredBlock(base, vanilla));
                states++;
            }
            BakedModel item = models.get(ModelResourceLocation.inventory(
                    BuiltInRegistries.ITEM.getKey(entry.vanilla().asItem())));
            if (item != null) {
                boolean mask = !item.isGui3d() || !OvergrownLayerField.isPlainCube(entry.vanilla().defaultBlockState());
                models.put(ModelResourceLocation.inventory(entry.item().getId()), new CoveredItem(item, mask));
            } else {
                missing++;
            }
        }
        if (missing > 0) {
            OvergrownVegetation.LOGGER.warn("覆われたブロック: 元の模型が見つからないものが {} 件(仮の模型のまま)", missing);
        }
        OvergrownVegetation.LOGGER.debug("覆われたブロック: {} 状態の模型を差し替えた", states);
    }

    /**
     * 面を借りて苔を足す。{@code mask} = 立方体でない(色の付いた画素の上だけに苔を置く)。
     * 苔は元の面の後ろに同じ描画層(cutoutMipped・持ち物は同じ種類)で積むので、押し出さずに同じ位置へ重ねる
     * (2026-10-07、遠くでちかちかした件。{@link OvergrownLayerQuads} の {@code PUSH})。
     * {@code stable} = 元の模型が同じ並びを返し続ける({@link DressedLists#stableLists})。そうなら出来上がりを覚えて使い回す。
     */
    private static List<BakedQuad> dress(List<BakedQuad> base, boolean mask, boolean stable) {
        if (base.isEmpty()) {
            return base;
        }
        if (stable) {
            return DressedLists.of(base, mask, true);
        }
        List<BakedQuad> out = new ArrayList<>(base.size() * 2);
        out.addAll(base);
        OvergrownLayerQuads.dressAll(base, mask, true, out);
        return out;
    }

    /**
     * <b>元の面の並び + 苔の面</b>を、元の並び(のインスタンス)ごとに覚えて使い回す(2026-10-10、0.1.9 の前の軽量化)。
     * 使うのは 3 か所 —— ここ(覆われたブロックとその持ち物)・{@link OvergrownHuskModel}・{@link OvergrownLayerRenderer}。
     *
     * <p>今までは呼ばれるたびに新しい並びを作って、元の面と苔の面を詰め直していた(チャンクを積むたびに 1 ブロック 7 回、
     * 持ち物は毎フレーム)。出来上がりは<b>元の並びの中身・{@code mask}・{@code flush}・苔のスプライト</b>だけで決まる
     * —— 苔の面は {@link OvergrownLayerQuads} が元の面ごとに覚えていて、苔の UV はブロック内の座標から引くので
     * 置かれた場所に左右されない。乱数・位置・ModelData は「どの並びが返ってくるか」にしか効かないので、並びを鍵にすれば足りる。
     *
     * <ul>
     *     <li><b>鍵は弱参照・同一性</b>({@code MapMaker.weakKeys})。並びを持っている模型が捨てられれば(焼き直し)消える。
     *     値は元の並びそのものを指さないので、鍵を生かし続けることは無い。ワーカースレッドから同時に引かれるので並行に使える表。</li>
     *     <li><b>覚えるのは並びを使い回す模型だけ</b>({@link #stableLists})。呼ぶたびに新しい並びを作る模型
     *     (塀・壁・板ガラスの {@code MultiPartBakedModel}、他 MOD の模型)は鍵が毎回変わって表が膨らむだけなので、呼ぶ側で今までどおり毎回作る。</li>
     *     <li><b>引いたら中身を確かめる</b>。同じ並びに別の面が入っていたら作り直す。面を前から同一性で比べるだけなので、
     *     新しい並びを作るよりずっと軽い。</li>
     *     <li>苔のスプライトが変わったら(アトラスの焼き直し)表ごと捨てる({@link OvergrownLayerQuads} と同じ)。</li>
     *     <li>出来上がりは何度も渡すので書き換えられない並びにする。中身と順番は今までと同じ(先頭 {@code base.size()} 枚が元の面、
     *     その後ろが苔の面)。</li>
     * </ul>
     */
    static final class DressedLists {

        /** 覚えた出来上がり。{@code baseSize} = 元の面の枚数(先頭のその枚数が元の面)。 */
        private record Dressed(int baseSize, List<BakedQuad> quads) {

            /** {@code base} の中身が覚えたときと同じか(枚数と、面の同一性を前から)。 */
            boolean matches(List<BakedQuad> base) {
                if (base.size() != this.baseSize) {
                    return false;
                }
                for (int i = 0; i < this.baseSize; i++) {
                    if (this.quads.get(i) != base.get(i)) {
                        return false;
                    }
                }
                return true;
            }
        }

        /** {@code mask}・{@code flush} の組ごとの表。苔のスプライトが変わったら作り直す。 */
        private record Tables(TextureAtlasSprite sprite,
                              Map<List<BakedQuad>, Dressed> plain,
                              Map<List<BakedQuad>, Dressed> masked,
                              Map<List<BakedQuad>, Dressed> plainFlush,
                              Map<List<BakedQuad>, Dressed> maskedFlush) {
        }

        private static volatile Tables tables;

        private DressedLists() {
        }

        /**
         * {@code getQuads} が同じ並び(のインスタンス)を返し続ける模型か。バニラの JSON の模型({@link SimpleBakedModel} は
         * 焼いたときの並びをそのまま返す)と、その重み付きの束({@link WeightedBakedModel} は選んだ模型の並びをそのまま返す)だけ。
         * 継いだクラスは {@code getQuads} を変えているかもしれないので数えない。
         */
        static boolean stableLists(BakedModel model) {
            Class<?> type = model.getClass();
            return type == SimpleBakedModel.class || type == WeightedBakedModel.class;
        }

        /**
         * {@code base} の後ろに苔の面を足した並び({@link OvergrownLayerQuads#dressAll} で作るのと同じ中身・同じ順)。
         * 空の並びはそのまま返す(使い回しの空リストのことがあるので覚えない)。
         */
        static List<BakedQuad> of(List<BakedQuad> base, boolean mask, boolean flush) {
            if (base.isEmpty()) {
                return base;
            }
            Map<List<BakedQuad>, Dressed> table = table(mask, flush);
            Dressed done = table.get(base);
            if (done != null && done.matches(base)) {
                return done.quads();
            }
            List<BakedQuad> out = new ArrayList<>(base.size() * 2);
            out.addAll(base);
            OvergrownLayerQuads.dressAll(base, mask, flush, out);
            Dressed made = new Dressed(base.size(), List.copyOf(out));
            table.put(base, made);
            return made.quads();
        }

        private static Map<List<BakedQuad>, Dressed> table(boolean mask, boolean flush) {
            TextureAtlasSprite sprite = OvergrownLayerQuads.sprite();
            Tables t = tables;
            if (t == null || t.sprite() != sprite) {
                t = new Tables(sprite, weakListMap(), weakListMap(), weakListMap(), weakListMap());
                tables = t;
            }
            return flush ? (mask ? t.maskedFlush() : t.plainFlush()) : (mask ? t.masked() : t.plain());
        }

        private static Map<List<BakedQuad>, Dressed> weakListMap() {
            return new MapMaker().weakKeys().makeMap();
        }
    }

    /** 世界に置かれた覆われたブロック 1 状態ぶん。 */
    private static final class CoveredBlock extends BakedModelWrapper<BakedModel> {

        private final BlockState vanilla;
        private final boolean mask;
        /** 元の模型が同じ並びを返し続けるか(出来上がりを覚えてよいか。{@link DressedLists#stableLists})。 */
        private final boolean stable;

        CoveredBlock(BakedModel base, BlockState vanilla) {
            super(base);
            this.vanilla = vanilla;
            this.mask = !OvergrownLayerField.isPlainCube(vanilla);
            this.stable = DressedLists.stableLists(base);
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            return getQuads(state, side, rand, ModelData.EMPTY, null);
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                        ModelData extraData, @Nullable RenderType renderType) {
            if (renderType != null && !RENDER_TYPES.contains(renderType)) {
                return List.of();
            }
            // renderType に null を渡すと、元の模型の全部の層の面が 1 回で返る(乱数を 1 回しか進めない)。
            return dress(originalModel.getQuads(vanilla, side, rand, ModelData.EMPTY, null), mask, stable);
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
            return RENDER_TYPES;
        }

        @Override
        public TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType renderType) {
            return originalModel.useAmbientOcclusion(vanilla, ModelData.EMPTY, renderType);
        }
    }

    /** 持ち物(手・インベントリ・額縁)。平らな絵のドアや鉄格子は絵の形にだけ苔が乗る。 */
    private static final class CoveredItem extends BakedModelWrapper<BakedModel> {

        private static final List<RenderType> ITEM_RENDER_TYPES = List.of(Sheets.cutoutBlockSheet());

        private final boolean mask;
        /** 元の模型が同じ並びを返し続けるか(出来上がりを覚えてよいか。{@link DressedLists#stableLists})。 */
        private final boolean stable;

        CoveredItem(BakedModel base, boolean mask) {
            super(base);
            this.mask = mask;
            this.stable = DressedLists.stableLists(base);
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            return dress(originalModel.getQuads(state, side, rand), mask, stable);
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                        ModelData extraData, @Nullable RenderType renderType) {
            return dress(originalModel.getQuads(state, side, rand, extraData, null), mask, stable);
        }

        @Override
        public List<RenderType> getRenderTypes(ItemStack itemStack, boolean fabulous) {
            // 苔の画素は切り抜きなので、元が不透明でも切り抜きの層で描く(不透明の層だと透明な画素が黒く出る)。
            return ITEM_RENDER_TYPES;
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack itemStack, boolean fabulous) {
            return List.of(this);
        }

        @Override
        public BakedModel applyTransform(ItemDisplayContext context, PoseStack poseStack, boolean applyLeftHandTransform) {
            originalModel.applyTransform(context, poseStack, applyLeftHandTransform);
            return this;
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }
    }
}
