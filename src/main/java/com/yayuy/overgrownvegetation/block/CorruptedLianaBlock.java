package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * 木と木の間に張られる1本の連結蔦(リアナ)の「アンカー」。
 *
 * <p>以前は経路上にこのブロックを何個も並べて蔦を表現していたが、格子に沿ったブロックの
 * 向き(X/Y/Z軸)でしか繋げないため、曲線というより階段状にしか見えなかった。
 * このバージョンでは、木1本の成長につき蔦1本ぶんだけこのブロックを(始点側の幹に隣接する
 * 1マスへ)置き、実際の始点・終点・垂れ具合は {@link CorruptedLianaBlockEntity} に保持する。
 * 見た目は {@link CorruptedLianaBlockEntity}(クライアント側は
 * {@code com.yayuy.overgrownvegetation.client.CorruptedLianaRenderer})が、
 * バニラのリード(ひも)と同じ要領で毎フレーム曲線をポリゴンとして直接描画するので、
 * ブロックの格子に縛られない滑らかな垂れ下がりになる({@code getRenderShape} は
 * {@link BaseEntityBlock} の既定どおり {@link RenderShape#INVISIBLE} で、
 * このブロック自体は何も描画しない)。
 *
 * <p>当たり判定はごく小さいマーカー用の箱のみ。支えている面が無くなっても剥がれ落ちない
 * ({@code canSurvive} を上書きしていない=常に存在し続けられる、
 * {@code minecraft:chain} などと同じ考え方)。
 *
 * <p>通常の採掘(左クリック長押し)は硬さ 0.2F(バニラの蔦と同じ)でどの道具でも一瞬で終わるが、
 * それとは別に「切る」動作として即座に反応する2つの入り口を持たせてある。
 * <ul>
 *     <li>{@link #attack}: 剣を持って左クリック(攻撃)した瞬間に即座に切れる。</li>
 *     <li>{@link #useItemOn}: ハサミを持って右クリックした瞬間に即座に切れる。</li>
 * </ul>
 *
 * <p>曲線そのもの(ポリゴン描画)には当たり判定が無いため、この1マスしか置かなければ
 * 「見えている蔦のどこを触っても反応しない」状態になってしまう。それを避けるため、
 * {@link com.yayuy.overgrownvegetation.worldgen.TreeVineConnector} は経路に沿って
 * このブロックの副アンカー({@link CorruptedLianaBlockEntity#getPrimaryPos} が非nullのもの)
 * を複数個追加で置く。副アンカーも同じ {@link #attack}/{@link #useItemOn} で反応し、
 * 主アンカーを経由して蔦を構成する全ブロックをまとめて撤去する
 * ({@link #cut} 参照)。
 *
 * <p>蔦から生えた蔦(重複蔦、終点が既存の蔦になった通常の蔦——いずれも
 * {@link com.yayuy.overgrownvegetation.worldgen.TreeVineConnector} 参照)は依存元の主アンカーを
 * {@link CorruptedLianaBlockEntity#getParentPositions} に持つ。{@link #cut} は依存元の主アンカーを
 * 切ったとき、そこに依存している蔦(たち)を {@link CorruptedLianaBlockEntity#getChildPositions}
 * 経由で再帰的にたどり、まとめて連鎖除去する(2026-08-24追加。破壊演出・ドロップは実際に
 * クリックした1マスからのみ出し、連鎖除去される側は静かに消える——同じ蔦1本ぶんの
 * 主アンカー・副アンカーをまとめて消す既存の挙動と同じ考え方)。
 *
 * <p>発光する垂れ蔦(overgrown_liana_droop_light)ぶんだけ実際に置かれた見えない光源ブロックの
 * 座標は {@link CorruptedLianaBlockEntity#getLightBlockPositions} に記録されている(2026-08-24追加)。
 * 蔦(主アンカー・連鎖除去される依存先のどちらでも)を撤去する際は、この光源ブロックも一緒に
 * 取り除く——放置すると宙に浮いた光源だけが残ってしまうため。
 */
public class CorruptedLianaBlock extends BaseEntityBlock {

    public static final MapCodec<CorruptedLianaBlock> CODEC = simpleCodec(CorruptedLianaBlock::new);

    /** 見えないマーカーなので当たり判定は小さめだが、クリックしやすいよう少し余裕を持たせてある。 */
    private static final VoxelShape SHAPE = Block.box(4.0, 4.0, 4.0, 12.0, 12.0, 12.0);

    public CorruptedLianaBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** 右クリック位置からこの距離(ブロック)以内にある発光中の垂れ蔦だけを収穫対象にする。 */
    private static final double HARVEST_REACH = 3.0;

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CorruptedLianaBlockEntity(pos, state);
    }

    /**
     * 主アンカーだけがサーバーティックで動く({@link CorruptedLianaBlockEntity#serverTick} が
     * 副アンカー・垂れ蔦なしの蔦では即座に何もしない)。「蔦の実」の熟成(発光していない垂れ蔦を
     * 時間経過で発光させる)にだけ使う。
     *
     * <p>ここではブロックの状態しか見られないので副アンカーにもティッカーを返すが、副アンカーは
     * 最初の 1 tick で自分のティッカーを外す({@code CorruptedLianaBlockEntity#detachTicker})。
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.OVERGROWN_LIANA.get(), CorruptedLianaBlockEntity::serverTick);
    }

    /**
     * 素手で蔦(主アンカー・副アンカーどれでも)を右クリックすると、クリック位置に一番近い
     * 発光中(=熟した)の垂れ蔦を1本収穫して「蔦の実」({@link ModItems#VINE_FRUIT})を出し、
     * その垂れ蔦を発光しない状態へ戻す(バニラのグロウベリーと同じ流れ)。発光中の垂れ蔦が
     * 近くに無ければ何もしない({@link InteractionResult#PASS})。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof CorruptedLianaBlockEntity clicked)) {
            return InteractionResult.PASS;
        }
        BlockPos primaryPos = clicked.getPrimaryPos() != null ? clicked.getPrimaryPos() : pos;
        if (!(level.getBlockEntity(primaryPos) instanceof CorruptedLianaBlockEntity primary)) {
            return InteractionResult.PASS;
        }

        int droopIndex = primary.findNearestRipeDroop(hitResult.getLocation(), HARVEST_REACH);
        if (droopIndex < 0) {
            return InteractionResult.PASS;
        }

        if (level instanceof ServerLevel serverLevel) {
            Vec3 dropAt = primary.harvestDroop(serverLevel, droopIndex);
            BlockPos dropPos = BlockPos.containing(dropAt);
            Block.popResource(serverLevel, dropPos, new ItemStack(ModItems.VINE_FRUIT.get(), 1));
            float pitch = Mth.randomBetween(serverLevel.random, 0.8F, 1.2F);
            serverLevel.playSound(null, dropPos, SoundEvents.CAVE_VINES_PICK_BERRIES, SoundSource.BLOCKS, 1.0F, pitch);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** 剣で殴った(左クリックした)瞬間、通常の採掘進捗を待たずに即座に切る。 */
    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        ItemStack heldItem = player.getMainHandItem();
        if (heldItem.getItem() instanceof SwordItem) {
            cut(state, level, pos, player, heldItem, EquipmentSlot.MAINHAND);
        }
    }

    /** ハサミを持って右クリックした瞬間、即座に切る。 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (stack.getItem() instanceof ShearsItem) {
            cut(state, level, pos, player, stack, LivingEntity.getSlotForHand(hand));
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    /**
     * 破壊パーティクル/音を出し、通常の採掘と同じくルートテーブルどおりにドロップさせてから
     * ブロックを取り除き、道具の耐久を1消費する。サーバー側でのみ実行する。
     *
     * <p>{@code pos} が副アンカー(クリック判定用に経路上へ追加で置いたマーカー)の場合は、
     * 主アンカーを経由して蔦を構成する全ブロック(主アンカー+他の全副アンカー)を静かに
     * 撤去する。さらに、その主アンカーに依存している蔦(重複蔦・終点が既存の蔦になった蔦)が
     * あれば {@link #removeDependentVines} で再帰的に連鎖除去する。破壊演出・ドロップは
     * 実際にクリックした {@code pos} からのみ出す(蔦1本につきアイテムが何個もばら撒かれる
     * のを防ぐため、連鎖除去される側も含めて全て静かに消す)。
     */
    private static void cut(BlockState state, Level level, BlockPos pos, Player player, ItemStack tool, EquipmentSlot slot) {
        if (level.isClientSide()) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);

        if (blockEntity instanceof CorruptedLianaBlockEntity liana) {
            BlockPos primaryPos = liana.getPrimaryPos() != null ? liana.getPrimaryPos() : pos;
            if (level.getBlockEntity(primaryPos) instanceof CorruptedLianaBlockEntity primaryLiana) {
                for (BlockPos secondaryPos : primaryLiana.getSecondaryPositions()) {
                    if (!secondaryPos.equals(pos)) {
                        level.removeBlock(secondaryPos, false);
                    }
                }
                for (BlockPos lightPos : primaryLiana.getLightBlockPositions()) {
                    level.removeBlock(lightPos, false);
                }
                removeDependentVines(level, primaryLiana, pos, primaryPos);
            }
            if (!primaryPos.equals(pos)) {
                level.removeBlock(primaryPos, false);
            }
        }

        level.levelEvent(player, 2001, pos, Block.getId(state));
        Block.dropResources(state, level, pos, blockEntity, player, tool);
        level.removeBlock(pos, false);
        tool.hurtAndBreak(1, player, slot);
    }

    /**
     * 蔦 1 本(主アンカー {@code primaryPos})を、副アンカー・光源ブロック・依存している蔦ごと<b>静かに</b>消す
     * (2026-10-16、ユーザー報告「木を消す処理の後に垂れ蔦が空中に浮いてしまう」)。{@link #cut} から
     * 破壊演出・ドロップ・道具の消耗を除いたもの。近隣更新・形状更新も出さない(周りの植物を巻き込んで壊さない)。
     * 端の木が消えたときに {@code corruption.SwampTreeFall} が呼ぶ。
     */
    public static void removeQuietly(Level level, BlockPos primaryPos) {
        if (!(level.getBlockEntity(primaryPos) instanceof CorruptedLianaBlockEntity primaryLiana)) {
            return;
        }
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(primaryPos);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (!visited.add(pos) || !(level.getBlockEntity(pos) instanceof CorruptedLianaBlockEntity liana)) {
                continue;
            }
            for (BlockPos secondaryPos : liana.getSecondaryPositions()) {
                if (level.getBlockState(secondaryPos).getBlock() instanceof CorruptedLianaBlock) {
                    level.setBlock(secondaryPos, air, flags);
                }
            }
            for (BlockPos lightPos : liana.getLightBlockPositions()) {
                if (level.getBlockState(lightPos).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                    level.setBlock(lightPos, air, flags);
                }
            }
            queue.addAll(liana.getChildPositions());
            level.setBlock(pos, air, flags);
        }
    }

    /**
     * {@code primaryLiana} に依存している蔦(重複蔦・終点が既存の蔦になった通常の蔦)を、
     * さらにその依存先……という具合に再帰的にすべて撤去する({@code getChildPositions} を
     * 幅優先でたどる)。撤去する子1本につき、その子自身の副アンカー・光源ブロック
     * ({@code getLightBlockPositions}、発光する垂れ蔦ぶん)もまとめて取り除く。
     * 破壊演出・ドロップは一切出さない(呼び出し元の {@link #cut} がクリックした1マスからのみ
     * 出す)。{@code visited} は理論上ありえない循環参照からの防御(既に処理済み/これから
     * 処理する主アンカー2つを最初から除外しておく)。
     */
    private static void removeDependentVines(Level level, CorruptedLianaBlockEntity primaryLiana,
            BlockPos clickedPos, BlockPos primaryPos) {
        if (primaryLiana.getChildPositions().isEmpty()) {
            return;
        }
        Set<BlockPos> visited = new HashSet<>();
        visited.add(clickedPos);
        visited.add(primaryPos);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(primaryLiana.getChildPositions());
        while (!queue.isEmpty()) {
            BlockPos childPos = queue.poll();
            if (!visited.add(childPos)) {
                continue;
            }
            if (!(level.getBlockEntity(childPos) instanceof CorruptedLianaBlockEntity childLiana)) {
                // 既に何らかの理由で無くなっている(例えば別の親経由で先に消えた)。
                continue;
            }
            for (BlockPos secondaryPos : childLiana.getSecondaryPositions()) {
                level.removeBlock(secondaryPos, false);
            }
            for (BlockPos lightPos : childLiana.getLightBlockPositions()) {
                level.removeBlock(lightPos, false);
            }
            queue.addAll(childLiana.getChildPositions());
            level.removeBlock(childPos, false);
        }
    }
}
