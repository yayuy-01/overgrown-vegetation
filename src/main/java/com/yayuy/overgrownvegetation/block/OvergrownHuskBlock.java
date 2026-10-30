package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CorruptionRegistry;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerField;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>覆われて動かなくなったブロック</b>(2026-09-22、要望)。
 *
 * <h2>何のためにあるのか</h2>
 * <p>汚染の対応表に無いブロック —— つまり<b>他 MOD のブロック</b>と、バニラでも対応表に載せていないもの
 * —— が植物に覆われたとき、<b>このブロックへ差し替える</b>。
 * 見た目は元のブロックそのまま、しかし<b>何の機能も持たない</b>ただのブロックになる。
 *
 * <h2>差し替えるのは「自分で動くもの」だけ</h2>
 * <p>一度は<b>覆ったものを全部これにした</b>ことがある —— 金床・砥石・作業台・ドア・ボタンは
 * {@link BlockEntity} を持たないのに開くし鳴るので、列挙で見分けると穴が残ると考えたため。
 * だが代償が重すぎた:<b>覆ったマスの数だけ {@link BlockEntity} が増える</b>(拠点 1 つで数千個)。
 *
 * <p>そこで<b>{@link BlockEntity} を持つものだけ</b>に戻した。
 * 他 MOD の機械やかまどは<b>誰も触らなくても自分で動く</b>ので、ブロックごと差し替えないと止まらない。
 * 金床や砥石は自分では動かないので、<b>座標を控えて右クリックを止める</b>だけで足りる
 * ({@link com.yayuy.overgrownvegetation.corruption.OvergrownLayerData} —— 1 マス 8 バイト)。
 * 残る穴はレッドストーンで動く分(覆われたドアがレバーで開く)。承知の上。
 *
 * <p>中身を持っていた機械(かまどなど)は<b>中のアイテムを引き継ぐ</b>。
 * 汚染を解こうとこのブロックを壊せば、<b>元のブロックと中身がまとめて返ってくる</b>(要望)。
 * つまり覆われたぶんは<b>取り返せる</b> —— ただし向きや状態はアイテムに戻った時点で失われる。
 *
 * <h2>触らないもの</h2>
 * <ul>
 *     <li><b>この MOD 自身のブロック</b>({@code overgrown_vegetation:*}) ——
 *     研究台・除染機・砲台など、動いてもらわないと困るものがここに入っている。
 *     タグに入れ忘れても事故らないよう、名前空間で弾く。</li>
 *     <li>{@link ModTags#HUSK_IMMUNE} のブロック —— データパックで足せる逃げ道。
 *     既定は 2 種類。スポナー・試練のスポナー・宝物庫(構造物の仕掛けを黙って壊さないため)と、
 *     看板・旗・頭・飾り壺(<b>模型が空で、見えているものを描画器が丸ごと描いている</b>ので、
 *     借りても何も出てこない。書いた文字まで消える)。</li>
 *     <li>{@link ModTags#CORRUPTION_IMMUNE} / 既に汚染済み / 破壊不能ブロック ——
 *     いずれも {@link CorruptionRegistry#canCorrupt} が先に弾く。</li>
 * </ul>
 *
 * <h2>{@code corruptBlockEntities} とは別の設定</h2>
 * <p>「BlockEntity を汚染するか」({@code corruptBlockEntities}、既定 false)は
 * <b>チェストなどが中身ごと消えるのを防ぐための設定</b>。こちらは<b>消さない</b> ——
 * 見た目も中身も残して機能だけ止めるので、別の設定
 * ({@code OvergrownLayerField#ENCASE}、既定 true。もとは climax.json)で切り替える。
 *
 * <h2>形</h2>
 * <p>当たり判定と遮蔽は<b>元のブロックの形をそのまま借りる</b>。ただし元のブロックのコードに
 * <b>この世界のこの座標</b>を渡すと、自分の {@link BlockEntity} を期待している MOD が落ちうるので、
 * {@link EmptyBlockGetter} と原点を渡して形だけを取り出し、結果をキャッシュする
 * ({@link #SHAPE_CACHE})。例外が飛んだら立方体に落とす。
 */
public class OvergrownHuskBlock extends Block implements EntityBlock {

    /** 元のブロック状態 → 借りた形。毎フレーム他 MOD のコードを呼ばないためのキャッシュ。 */
    private static final Map<BlockState, VoxelShape> SHAPE_CACHE = new ConcurrentHashMap<>();

    /**
     * 覆う前の明るさの 4 割(2026-10-05、覆われた光源は −60%、{@code covered/CoveredLight})。火の付いたかまど 13 → 5、
     * 焚き火 15 → 6。中身ごと止めるので、覆ったときの明るさのまま変わらない。古いセーブの覆われたブロックは 0(今までどおり暗い)。
     */
    public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 15);

    public OvergrownHuskBlock(Properties properties) {
        super(properties.lightLevel(state -> state.getValue(LIGHT)));
        registerDefaultState(stateDefinition.any().setValue(LIGHT, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIGHT);
    }

    // --- 覆う側(サーバー) ---

    /**
     * そのブロックを「覆われて動かなくなったブロック」へ差し替えてよいか。
     *
     * <p>{@link CorruptionRegistry#canCorrupt} と {@link CorruptionRegistry#corrupt} の両方から呼ぶので、
     * <b>両者で答えが食い違わないこと</b>が大事 —— 食い違うと「汚染してよいのに変換先が無い」
     * ブロックが毎回抽選を無駄打ちする。
     */
    public static boolean canEncase(BlockState state) {
        if (!com.yayuy.overgrownvegetation.corruption.OvergrownLayerField.ENCASE) {
            return false;
        }
        if (!state.hasBlockEntity()) {
            // 中身を持たないブロック(金床・砥石・コンクリートなど)は差し替えない ——
            // 自分で動かないので、記録 + 右クリック抑止で足りる
            // ({@link com.yayuy.overgrownvegetation.corruption.OvergrownLayerData})。
            // ここを外すと拠点 1 つで BlockEntity が数千個に膨れる。
            return false;
        }
        if (state.getRenderShape() != RenderShape.MODEL) {
            // 模型を持たない描画(チェストのような BlockEntity 描画)は見た目を借りられない。
            return false;
        }
        if (state.is(ModTags.CORRUPTED) || state.is(ModTags.HUSK_IMMUNE)) {
            return false;
        }
        if (OvergrownContainers.isConvertibleContainer(state)) {
            // チェスト・樽は専用の「過繫茂したチェスト/樽」がある(中身を持ったまま、開けもする)。
            // ここで覆ってしまうと、開けられないただの塊になって専用ブロックが死ぬ。
            return false;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null || OvergrownVegetation.MODID.equals(id.getNamespace())) {
            // この MOD の設備(研究台・除染機・砲台など)は動いてもらわないと困る。
            // 未登録のブロック(あり得ないはずだが)も触らない。
            return false;
        }
        return CorruptionRegistry.hasNoCorruptedForm(state);
    }

    /**
     * 実際に差し替える。{@link CorruptionRegistry#placeCorrupted} から呼ばれる。
     *
     * <p><b>先に中身を取り出して空にしてから</b>置き換えること —— バニラの入れ物は
     * {@code onRemove} で中身を地面へ撒くので、空にしないと汚染がアイテムをばら撒いてしまう
     * (汚染はドロップを出さない、が全体の決まり)。
     */
    public static void encase(LevelAccessor level, BlockPos pos, BlockState previous, int flags) {
        List<ItemStack> items = takeContents(level, pos);
        level.setBlock(pos, ModBlocks.OVERGROWN_HUSK.get().defaultBlockState()
                .setValue(LIGHT, com.yayuy.overgrownvegetation.covered.CoveredLight.dim(previous.getLightEmission())), flags);
        if (level.getBlockEntity(pos) instanceof OvergrownHuskBlockEntity husk) {
            husk.capture(previous, items);
        }
    }

    /**
     * そこにある入れ物から中身を<b>取り出して空にする</b>。
     *
     * <p>{@link Container}(バニラのかまど・チェストや、それを実装した MOD)を先に見る ——
     * 確実に空にできるので、置き換えでアイテムが撒かれない。次に NeoForge の
     * アイテムハンドラ能力を見る(ほとんどの MOD の機械はこちらで取れる)。
     * 取り出しを拒む枠(出力専用など)は、中身を<b>写して</b>覚える —— どのみち
     * この BlockEntity は消えるので、写しておくほうが失うより良い。
     */
    private static List<ItemStack> takeContents(LevelAccessor level, BlockPos pos) {
        List<ItemStack> out = new ArrayList<>();
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return out;
        }
        if (be instanceof Container container) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty()) {
                    out.add(stack.copy());
                }
            }
            container.clearContent();
            return out;
        }
        if (level instanceof Level realLevel) {
            IItemHandler handler = realLevel.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    ItemStack taken = handler.extractItem(i, Integer.MAX_VALUE, false);
                    if (!taken.isEmpty()) {
                        out.add(taken);
                        continue;
                    }
                    ItemStack shown = handler.getStackInSlot(i);
                    if (!shown.isEmpty()) {
                        out.add(shown.copy());
                    }
                }
            }
        }
        return out;
    }

    // --- ブロックとしてのふるまい ---

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OvergrownHuskBlockEntity(pos, state);
    }

    // ticker は<b>絶対に付けない</b>。付けた瞬間「覆われているのに動くブロック」に逆戻りする。

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof OvergrownHuskBlockEntity husk) {
                husk.dropSalvage(level, pos);
            }
            super.onRemove(state, level, pos, newState, movedByPiston);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return borrowedShape(level, pos);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return borrowedShape(level, pos);
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return borrowedShape(level, pos);
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return borrowedShape(level, pos);
    }

    /** 元のブロックの形。取れなければ立方体。 */
    private static VoxelShape borrowedShape(BlockGetter level, BlockPos pos) {
        BlockState mimic = mimicAt(level, pos);
        if (mimic == null || mimic.isAir()) {
            return Shapes.block();
        }
        return SHAPE_CACHE.computeIfAbsent(mimic, OvergrownHuskBlock::computeShape);
    }

    private static VoxelShape computeShape(BlockState mimic) {
        try {
            // 世界を見せない。他 MOD の getShape が「自分の BlockEntity」を掴もうとしても null になり、
            // この座標にある<b>こちらの</b>中身を取り違えて落ちることがない。
            VoxelShape shape = mimic.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return shape.isEmpty() ? Shapes.block() : shape;
        } catch (Throwable ignored) {
            return Shapes.block();
        }
    }

    /** そこにある「覆われたブロック」が化けている元のブロック状態。 */
    @Nullable
    public static BlockState mimicAt(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof OvergrownHuskBlockEntity husk ? husk.mimic() : null;
    }

    @Override
    protected boolean isPathfindable(BlockState state, net.minecraft.world.level.pathfinder.PathComputationType type) {
        return false;
    }

    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacent, Direction direction) {
        return false;
    }
}
