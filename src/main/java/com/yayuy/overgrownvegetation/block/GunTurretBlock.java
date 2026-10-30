package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.defense.GunTurrets;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 「機関砲台」(ユーザー要望 2026-09-21)—— エンティティではなく<b>自分で動くブロック</b>の対空砲。
 *
 * <h2>操作</h2>
 * <ul>
 *   <li><b>機関砲弾を持って右クリック</b> — そのまま弾倉へ押し込む。</li>
 *   <li><b>右クリック</b> — 弾倉(3×3 = 9 スタック)を開く。覆われているときは、まず胞子を拭う。</li>
 *   <li><b>スニーク + 右クリック</b> — 射撃モードを切り替える(両用 → 防空 → 対地)。</li>
 *   <li><b>レッドストーン信号</b> — 来ているあいだは撃たない(安全装置)。</li>
 * </ul>
 * 撃つ中身は {@link GunTurretBlockEntity}、目標選びは
 * {@link com.yayuy.overgrownvegetation.defense.GunTurretTargeting}、寸法は
 * {@link com.yayuy.overgrownvegetation.defense.GunTurretGeometry}。
 *
 * <h2>汚染されると止まる(2026-09-21)</h2>
 * 過繫茂に飲まれるとテクスチャが差し替わり、<b>砲台は動かなくなる</b>(ユーザー指定)。
 * 弾倉の中身も向きも残っていて、<b>素手で右クリックして胞子を拭えば</b>また動き出す(忌避灯と同じ手当て)。
 * その切り替えが {@link #OVERGROWN} で、汚染の対応表({@code corruption/CorruptionRegistry})から
 * このブロックの {@code OVERGROWN=true} が返る(普通のブロックと同じ経路で汚染される)。
 *
 * <p>それとは別に、<b>過繫茂したスカトゥリウムは迫撃砲で砲台を潰しに来る</b>
 * ({@code entity/ShielderTurretMortarGoal})。動いている砲台の台帳が {@link GunTurrets} で、
 * 胞子爆弾が {@code gunTurret.corruptRadius} 以内で炸裂すると覆われる。
 *
 * <p>見た目は丸ごと {@code client/GunTurretRenderer}(GeckoLib)が描くので、ブロックモデル自体は
 * 描画しない({@link RenderShape#INVISIBLE})。置いた向きが最初の方位になる。
 */
public class GunTurretBlock extends BaseEntityBlock {

    public static final MapCodec<GunTurretBlock> CODEC = simpleCodec(GunTurretBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** 過繫茂に覆われているか。見た目(テクスチャ)だけが変わる。 */
    public static final BooleanProperty OVERGROWN = BooleanProperty.create("overgrown");

    /**
     * 当たり判定は 1 マスまるごと。モデルは {@code gunTurret.modelScale}(既定 1.5)で 1 マスから
     * はみ出すが、当たり判定を旋回に合わせて動かすほどのものではないので、置いた 1 マスで代表する
     * (銃身が壁にめり込んで見えることはある)。
     */
    private static final VoxelShape SHAPE = Shapes.block();

    /**
     * 射撃モード(ユーザー要望 2026-09-26)。<b>スニーク + 右クリック</b>で切り替える。
     * 何を狙うかだけが変わる({@link GunTurretMode}、{@code defense/GunTurretTargeting#wants})。
     */
    public static final EnumProperty<GunTurretMode> MODE = EnumProperty.create("mode", GunTurretMode.class);

    public GunTurretBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(OVERGROWN, false)
                .setValue(MODE, GunTurretMode.DUAL));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OVERGROWN, MODE);
    }

    /** 銃口はプレイヤーの向いている方(= プレイヤーから離れる向き)。 */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    /** 方角 → 方位(度、0 = 北)。{@link Direction#toYRot()} は南 = 0 なので 180 ずらす。 */
    public static double azimuthOf(Direction facing) {
        return (facing.toYRot() + 180.0D) % 360.0D;
    }

    /** 動いている機関砲台か(過繫茂に覆われていれば止まっている)。 */
    public static boolean isWorking(BlockState state) {
        return state.getBlock() instanceof GunTurretBlock && !state.getValue(OVERGROWN);
    }

    /**
     * 覆う/拭う。台帳({@link GunTurrets})も同時に更新する。
     *
     * <p>覆われた砲台は<b>止まる</b>(ユーザー指定 2026-09-21)—— 弾は残ったままで、
     * 拭えばそのまま動き出す。
     */
    public void setOvergrown(Level level, BlockPos pos, BlockState state, boolean overgrown) {
        if (state.getValue(OVERGROWN) == overgrown) {
            return;
        }
        level.setBlock(pos, state.setValue(OVERGROWN, overgrown), Block.UPDATE_ALL);
        if (overgrown) {
            level.playSound(null, pos, SoundEvents.SPORE_BLOSSOM_PLACE, SoundSource.BLOCKS, 1.0F, 0.6F);
        } else {
            level.playSound(null, pos, SoundEvents.VINE_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    /**
     * 置かれた・状態が変わった。台帳を実際の状態に合わせる ——
     * <b>通常の汚染で覆われたときもここを通る</b>({@code corruption/CorruptionRegistry} は
     * {@link #setOvergrown} ではなく直接ブロックを置き換えるため)。
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide) {
            GunTurrets.sync(level, pos, state);
        }
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.getBlockEntity(pos) instanceof GunTurretBlockEntity turret) {
            turret.setAzimuth(azimuthOf(state.getValue(FACING)));
            turret.setPowered(level.hasNeighborSignal(pos));
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    /** 見た目は丸ごと GeckoLib が描く。 */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GunTurretBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // クライアントでも tick する: 旋回・俯仰・発砲の瞬間をサーバーとまったく同じ式で進めるため
        // (そのおかげで毎 tick の同期パケットが要らない)。
        return createTickerHelper(type, ModBlockEntities.GUN_TURRET.get(),
                level.isClientSide ? GunTurretBlockEntity::clientTick : GunTurretBlockEntity::serverTick);
    }

    /** 機関砲弾を持って右クリック: そのまま弾倉へ押し込む。 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!stack.is(ModItems.AUTOCANNON_ROUND.get())
                || !(level.getBlockEntity(pos) instanceof GunTurretBlockEntity turret)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (turret.loadFromHand(stack) <= 0) {
            return ItemInteractionResult.CONSUME;
        }
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.8F, 1.4F);
        return ItemInteractionResult.SUCCESS;
    }

    /**
     * 素手で右クリック: 弾倉を開く。<b>覆われているときは、まず胞子と蔦を拭う</b>
     * (忌避灯と同じ手当て)。拭えばまた動き出す。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof GunTurretBlockEntity turret)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // スニーク + 右クリックは射撃モードの切り替え(覆われていても先に受ける —— 拭う前に決めておける)。
        if (player.isSecondaryUseActive()) {
            GunTurretMode next = state.getValue(MODE).next();
            level.setBlock(pos, state.setValue(MODE, next), Block.UPDATE_ALL);
            player.displayClientMessage(
                    Component.translatable("gui.overgrown_vegetation.gun_turret.mode_set", next.label()), true);
            level.playSound(null, pos, SoundEvents.DISPENSER_FAIL, SoundSource.BLOCKS, 0.8F, 1.6F);
            return InteractionResult.CONSUME;
        }
        if (state.getValue(OVERGROWN)) {
            setOvergrown(level, pos, state, false);
            return InteractionResult.CONSUME;
        }
        player.openMenu(turret);
        return InteractionResult.CONSUME;
    }

    /** レッドストーン信号が来ているあいだは撃たない(安全装置)。 */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof GunTurretBlockEntity turret) {
            turret.setPowered(level.hasNeighborSignal(pos));
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            GunTurrets.unregister(level, pos);
            if (level.getBlockEntity(pos) instanceof GunTurretBlockEntity turret) {
                Containers.dropContents(level, pos, turret);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
