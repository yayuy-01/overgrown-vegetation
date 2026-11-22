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
 * <p>通常の採掘(左クリック長押し)は 2026-11-08 から素手で約 1.2 秒・ハサミで約 0.15 秒({@link #destroyProgress}、config の
 * {@code vineHandBreakSeconds}・{@code vineShearsBreakSeconds})。壊れると蔦 1 本ぶんをまとめて消す({@link #playerWillDestroy})。
 * 登る蔓は経路の全部のマスに狙い用の {@link LianaSegmentBlock} を置く。
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
        // 登る蔓の主アンカーは、そのマスの中を通る蔓の所だけ(2026-11-08。前の 8/16 の箱は幹から 4/16 離れていて、幹沿いの蔓に当たらなかった)
        if (level.getBlockEntity(pos) instanceof CorruptedLianaBlockEntity liana && liana.hasClimb()) {
            VoxelShape s = liana.getShapeCache();
            if (s == null) {
                s = LianaSegmentBlock.markerShape(liana.getClimbPath(), CorruptedLianaBlockEntity.CLIMB_STRIDE,
                        liana.getClimbScale() * com.yayuy.overgrownvegetation.worldgen.ClimbingVine.RADIUS_ROOT + 0.04D);
                if (s == null) {
                    s = SHAPE;
                }
                liana.setShapeCache(s);
            }
            return s;
        }
        return SHAPE;
    }

    /** 光を遮らない(経路から求めた箱がマス全体になっても、見えない印が林床を暗くしないように。2026-11-08)。 */
    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
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
        return harvest(level, pos, hitResult);
    }

    /** 実を摘む(主アンカー・副アンカー・{@link LianaSegmentBlock} 共通)。 */
    static InteractionResult harvest(Level level, BlockPos pos, BlockHitResult hitResult) {
        BlockPos primaryPos = level.isClientSide() ? clientPrimaryOf(level, pos) : primaryOf(level, pos);
        if (primaryPos == null || !(level.getBlockEntity(primaryPos) instanceof CorruptedLianaBlockEntity primary)) {
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
        attackCut(state, level, pos, player);
    }

    static void attackCut(BlockState state, Level level, BlockPos pos, Player player) {
        ItemStack heldItem = player.getMainHandItem();
        if (heldItem.getItem() instanceof SwordItem) {
            cut(state, level, pos, player, heldItem, EquipmentSlot.MAINHAND);
        }
    }

    /** ハサミを持って右クリックした瞬間、即座に切る。 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        ItemInteractionResult r = shearCut(stack, state, level, pos, player, hand);
        return r != null ? r : super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    /** ハサミなら切って結果を返す。ハサミでなければ null。 */
    @Nullable
    static ItemInteractionResult shearCut(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand) {
        if (isShears(stack)) {
            cut(state, level, pos, player, stack, LivingEntity.getSlotForHand(hand));
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        return null;
    }

    static boolean isShears(ItemStack stack) {
        return stack.getItem() instanceof ShearsItem
                || stack.canPerformAction(net.minecraftforge.common.ToolActions.SHEARS_DIG);
    }

    // ---- ふつうに壊す(左クリック長押し。2026-11-08)----

    /**
     * 壊す速さ(2026-11-08、ユーザー依頼「ハサミで速く・素手は少し時間がかかる」)。素手は {@code vineHandBreakSeconds}(仮決め 1.2 秒)、
     * ハサミは {@code vineShearsBreakSeconds}(仮決め 0.15 秒 = バニラの蔦と同じ)。{@link Player#getDestroySpeed} を掛けるので、
     * 剣(sword_efficient のタグで 1.5 倍)・急ぎ・採掘速度低下・水中・空中の倍率はバニラどおり効く。
     * ハサミのツールルールは {@code Blocks.VINE} を名指しで、タグで足せない(ModBlocks の蔦の注を参照)ので、ここで秒数から決める。
     */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return destroyProgress(state, player);
    }

    static float destroyProgress(BlockState state, Player player) {
        double seconds = isShears(player.getMainHandItem())
                ? com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.vineShearsBreakSeconds.get()
                : com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig.COMMON.vineHandBreakSeconds.get();
        if (seconds <= 0.0D) {
            return 1.0F;
        }
        return (float) (player.getDestroySpeed(state) / (seconds * 20.0D));
    }

    /**
     * ふつうに壊した(長押しで壊れた)ときも、蔦 1 本ぶんをまとめて消す(前は叩いた 1 マスだけが消え、主アンカーなら副アンカーが、
     * 副アンカーなら曲線が残っていた)。叩いた 1 マスはバニラが壊してルート(ハサミ・シルクタッチなら蔦)を落とす。
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        removeRestOfVine(level, pos);
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** {@code pos}(主アンカー・副アンカー・{@link LianaSegmentBlock})を含む蔦 1 本の、{@code pos} 以外の全部を静かに消す。サーバー側だけ。 */
    static void removeRestOfVine(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        BlockPos primaryPos = primaryOf(level, pos);
        if (primaryPos == null) {
            return;
        }
        // 登る蔓の束(本体 + 添う細い蔓 + 渡る区間・U から垂れる綱)は見た目が 1 本なので、どこを切っても束ごと消す(2026-11-08。
        // 添う細い蔓のマスを叩くと細い蔓だけが消えて本体が残っていた)。前の掛け渡しの蔦(登る蔓でない)は前どおり、切った蔦とその子だけ
        for (int guard = 0; guard < 8 && level.getBlockEntity(primaryPos) instanceof CorruptedLianaBlockEntity l
                && l.hasClimb() && !l.getParentPositions().isEmpty(); guard++) {
            BlockPos parent = l.getParentPositions().get(0);
            if (!(level.getBlockEntity(parent) instanceof CorruptedLianaBlockEntity)) {
                break;
            }
            primaryPos = parent;
        }
        if (level.getBlockEntity(primaryPos) instanceof CorruptedLianaBlockEntity primaryLiana) {
            for (BlockPos secondaryPos : primaryLiana.getSecondaryPositions()) {
                if (!secondaryPos.equals(pos) && isLianaPart(level.getBlockState(secondaryPos))) {
                    level.removeBlock(secondaryPos, false);
                }
            }
            for (BlockPos lightPos : primaryLiana.getLightBlockPositions()) {
                if (level.getBlockState(lightPos).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                    level.removeBlock(lightPos, false);
                }
            }
            removeDependentVines(level, primaryLiana, pos, primaryPos);
        }
        if (!primaryPos.equals(pos)) {
            level.removeBlock(primaryPos, false);
        }
    }

    /** 蔦の一部(主アンカー・前の副アンカー・{@link LianaSegmentBlock})か。 */
    public static boolean isLianaPart(BlockState state) {
        return state.getBlock() instanceof CorruptedLianaBlock || state.getBlock() instanceof LianaSegmentBlock;
    }

    /**
     * {@code pos} を含む蔦の主アンカー(サーバー側)。主アンカーならそのまま、前の副アンカーは持っている {@code Primary}、
     * {@link LianaSegmentBlock}(BlockEntity が無い)は周りのチャンクの主アンカーから、副アンカーの一覧に {@code pos} を持つものを探す。
     * 見つからなければ null。
     */
    @Nullable
    public static BlockPos primaryOf(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof CorruptedLianaBlockEntity liana) {
            return liana.getPrimaryPos() != null ? liana.getPrimaryPos() : pos;
        }
        if (!(level.getBlockState(pos).getBlock() instanceof LianaSegmentBlock)) {
            return null;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        // 蔓の経路は主アンカーから水平に約 16 まで(渡る区間 8 + 林冠の U 10)。近いチャンクから見る
        for (int ring = 0; ring <= SEGMENT_SEARCH_CHUNKS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || !com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.hasChunk(level, cx + dx, cz + dz)) {
                        continue;
                    }
                    for (BlockEntity be : level.getChunk(cx + dx, cz + dz).getBlockEntities().values()) {
                        if (be instanceof CorruptedLianaBlockEntity liana && liana.getPrimaryPos() == null
                                && liana.getSecondaryPositions().contains(pos)) {
                            return be.getBlockPos();
                        }
                    }
                }
            }
        }
        return null;
    }

    /** {@link LianaSegmentBlock} の持ち主を探すチャンクの半径。 */
    private static final int SEGMENT_SEARCH_CHUNKS = 2;

    /**
     * クライアント側の持ち主(右クリックで実を摘むときの手応えだけに使う)。副アンカーの一覧はクライアントに送らないので、
     * {@link LianaSegmentBlock} は叩いた所から {@link #HARVEST_REACH} 以内に熟した垂れ蔦を持つ主アンカーを近くから探す。
     */
    @Nullable
    private static BlockPos clientPrimaryOf(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof CorruptedLianaBlockEntity liana) {
            return liana.getPrimaryPos() != null ? liana.getPrimaryPos() : pos;
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        Vec3 at = Vec3.atCenterOf(pos);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!com.yayuy.overgrownvegetation.perf.ChunkEdgeGuard.hasChunk(level, cx + dx, cz + dz)) {
                    continue;
                }
                for (BlockEntity be : level.getChunk(cx + dx, cz + dz).getBlockEntities().values()) {
                    if (be instanceof CorruptedLianaBlockEntity liana && liana.getPrimaryPos() == null
                            && liana.findNearestRipeDroop(at, HARVEST_REACH) >= 0) {
                        return be.getBlockPos();
                    }
                }
            }
        }
        return null;
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
        removeRestOfVine(level, pos); // 主アンカー・副アンカー・LianaSegmentBlock・光源・依存している蔦(2026-11-08 に playerWillDestroy と共通に)

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
                if (isLianaPart(level.getBlockState(secondaryPos))) {
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
                if (!secondaryPos.equals(clickedPos) && isLianaPart(level.getBlockState(secondaryPos))) {
                    level.removeBlock(secondaryPos, false);
                }
            }
            for (BlockPos lightPos : childLiana.getLightBlockPositions()) {
                if (level.getBlockState(lightPos).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                    level.removeBlock(lightPos, false);
                }
            }
            queue.addAll(childLiana.getChildPositions());
            if (!childPos.equals(clickedPos)) { // 叩いたのが子の印なら、その 1 マスは呼び手(バニラの破壊・cut)が消す
                level.removeBlock(childPos, false);
            }
        }
    }
}
