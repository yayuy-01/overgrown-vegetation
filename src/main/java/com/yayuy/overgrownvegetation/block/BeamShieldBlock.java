package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.siege.BeamShieldGeometry;
import com.yayuy.overgrownvegetation.siege.SiegeStructureShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * 「遮蔽盾」— 攻城戦ボスが頭の花から撃つ巨大ビームを<b>部分的に</b>防ぐ、3×5 ブロックの展開式の盾。
 *
 * <h2>使い方</h2>
 * <ul>
 *   <li><b>下半分を右クリック = ハンドル</b> — 方位を {@code shieldHandleStep}(既定 15 度)ずつ回す。
 *       スニークしながらだと逆回り。<b>たたんでいるときだけ</b>回せる。</li>
 *   <li><b>上半分を右クリック = ボタン</b> — 展開 / 収納。展開には足元が固い地面であることが要る。</li>
 *   <li><b>レッドストーン</b> — 信号が入っているあいだ展開(自動化用)。</li>
 * </ul>
 *
 * <p>展開しきっているあいだ、盾の裏に居る生物は花のビームのダメージを受けない
 * ({@link BeamShieldBlockEntity#shielded})。遮るのは幅 3・高さ 5 ブロックぶんだけなので、
 * ビーム全体(1000 ブロック先では直径 130 ブロック)から見れば文字どおり「部分的」な守り。
 *
 * <p>見た目は丸ごと {@code BeamShieldRenderer}(GeckoLib)が描くので、ブロックモデルは描画しない。
 * 当たり判定は {@link SiegeStructurePartBlock}（見えない部品ブロック）をモデルの占める範囲へ敷き詰めて作る。
 */
public class BeamShieldBlock extends BaseEntityBlock {

    public static final MapCodec<BeamShieldBlock> CODEC = simpleCodec(BeamShieldBlock::new);

    public BeamShieldBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    /** 形は本体が「いまの向きと展開の進み」から作る（部品ブロックも同じものを使う）。 */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return level.getBlockEntity(pos) instanceof BeamShieldBlockEntity shield
                ? shield.structureShapes(BlockPos.ZERO)
                : Shapes.block();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    /** 展開して地面に固定する物なので、ピストンでは動かない。 */
    @Override
    public PushReaction getPistonPushReaction(BlockState state) {
        return PushReaction.BLOCK;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BeamShieldBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // 展開のせり上がりはクライアントでも同じ式で進めるので、両側で tick する。
        return createTickerHelper(type, ModBlockEntities.BEAM_SHIELD.get(), BeamShieldBlockEntity::tick);
    }

    /** 置いた向き（プレイヤーが向いている方）を最初の方位にする。 */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        SiegeStructurePartBlock.place(level, pos, BeamShieldGeometry.template(),
                (SiegeStructurePartBlock) ModBlocks.SIEGE_STRUCTURE_PART.get());
        if (level.getBlockEntity(pos) instanceof BeamShieldBlockEntity shield && placer != null) {
            shield.setAzimuth(placer.getYRot() + 180.0D);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            SiegeStructurePartBlock.clear(level, pos, BeamShieldGeometry.template());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * 右クリック。<b>触った場所がハンドルかボタンか</b>で分かれる（部品ブロック越しでも当たった座標は
     * そのまま来るので、モデルのどこを触ったかがそのまま分かる）。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof BeamShieldBlockEntity shield)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        Vec3 hit = hitResult.getLocation();
        double[] local = SiegeStructureShape.toLocal(pos, shield.azimuth(), BeamShieldGeometry.scale(),
                hit.x, hit.y, hit.z);
        if (BeamShieldGeometry.BUTTON_ZONE.contains(local[0], local[1], local[2])) {
            return pressButton(level, pos, player, shield);
        }
        if (BeamShieldGeometry.HANDLE_ZONE.contains(local[0], local[1], local[2])) {
            return turnHandle(level, pos, player, shield);
        }
        player.displayClientMessage(Component.translatable("gui.overgrown_vegetation.shield.where"), true);
        return InteractionResult.CONSUME;
    }

    /** ボタン: 展開 / 収納。 */
    private InteractionResult pressButton(Level level, BlockPos pos, Player player, BeamShieldBlockEntity shield) {
        boolean deploy = !shield.isDeploying();
        if (deploy && !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) {
            player.displayClientMessage(Component.translatable("gui.overgrown_vegetation.shield.needs_ground"), true);
            return InteractionResult.CONSUME;
        }
        if (deploy && !com.yayuy.overgrownvegetation.defense.MachineBattery.hasEnergy(shield)) {
            // 電池が無いと動かない(2026-10-16、㉔)
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.battery.needed"), true);
            return InteractionResult.CONSUME;
        }
        shield.setDeploying(deploy);
        level.playSound(null, pos, deploy ? SoundEvents.PISTON_EXTEND : SoundEvents.PISTON_CONTRACT,
                SoundSource.BLOCKS, 0.9F, 0.7F);
        player.displayClientMessage(Component.translatable(deploy
                ? "gui.overgrown_vegetation.shield.deploying"
                : "gui.overgrown_vegetation.shield.folding"), true);
        return InteractionResult.CONSUME;
    }

    /**
     * ハンドル: 方位を {@code shieldHandleStep}（既定 1 度）ずつ回す。スニークしながらだと逆回り。
     * 右クリックを押しっぱなしにすると 4 tick ごとに繰り返されるので、そのままぐるぐる回せる。
     * たたんでいるときだけ動かせる。
     */
    private InteractionResult turnHandle(Level level, BlockPos pos, Player player, BeamShieldBlockEntity shield) {
        if (!shield.turnHandle(player.isSecondaryUseActive() ? -1 : 1)) {
            player.displayClientMessage(Component.translatable("gui.overgrown_vegetation.shield.locked"), true);
            return InteractionResult.CONSUME;
        }
        level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.5F, 0.6F);
        player.displayClientMessage(Component.translatable("gui.overgrown_vegetation.shield.azimuth",
                String.format(java.util.Locale.ROOT, "%.0f", shield.azimuth())), true);
        return InteractionResult.CONSUME;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof BeamShieldBlockEntity shield) {
            shield.setPowered(level.hasNeighborSignal(pos));
        }
    }
}
