package com.yayuy.overgrownvegetation.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 汚染死体ブロック(仮実装。専用モデル完成後に差し替え予定)。
 *
 * <p>「胞子感染」({@link com.yayuy.overgrownvegetation.effect.SporeInfectionMobEffect})で力尽きた
 * Mob が死んだ場所に設置される。blight_fern と同じく {@link com.yayuy.overgrownvegetation.corruption.CorruptionSpreader}
 * を使ってこの場所を起点に(ただし小さめの半径で)侵食を広げる。実処理は
 * {@link CorruptedCorpseBlockEntity} を参照。
 *
 * <p>この基底クラスは {@code getRenderShape} が {@code MODEL}(通常のブロックモデル描画)。
 * 牛(Blockbench製の Java モデル)、ゾンビ・クモ(仮テクスチャの立方体)がこれを使う。
 * 羊・豚・クリーパーは GeckoLib 立体モデルで描くサブクラス {@link GeoCorruptedCorpseBlock}
 * ({@code getRenderShape} = {@code INVISIBLE})。{@link BaseEntityBlock} を継承しているのは、
 * 侵食を進める {@link CorruptedCorpseBlockEntity} の tick 処理を持たせるため
 * (加えて Geo 版は同 BlockEntity を {@code GeoBlockEntity} として描画に使う)。
 */
public class CorruptedCorpseBlock extends BaseEntityBlock {


    public CorruptedCorpseBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CorruptedCorpseBlockEntity(pos, state);
    }


    /**
     * 死体ブロックを壊すと、起き上がった後の G1 を倒したときと同じドロップを出す(11-08 ユーザー決定。壊した死体は起き上がらない)。
     * G1 のルート表({@code entities/parasitized_<種>})を、その場に作った G1(世界には足さない)とプレイヤーで引く。
     * スケルトンの死体が持っていた弓は、G1 を倒したときと同じ確率(バニラの 8.5%)で落とす。創造モードでは落とさない。
     */
    @Override
    public void playerDestroy(Level level, net.minecraft.world.entity.player.Player player, BlockPos pos, BlockState state,
                              @Nullable BlockEntity blockEntity, net.minecraft.world.item.ItemStack tool) {
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
        if (!(level instanceof net.minecraft.server.level.ServerLevel server) || player.isCreative()) {
            return;
        }
        com.yayuy.overgrownvegetation.corpse.G1Species species = com.yayuy.overgrownvegetation.corpse.G1Species.ofCorpse(state.getBlock());
        if (species == null) {
            return;
        }
        net.minecraft.world.entity.Mob g1 = species.entityType().create(server);
        if (g1 == null) {
            return;
        }
        g1.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        net.minecraft.world.level.storage.loot.LootTable table = server.getServer().getLootData().getLootTable(g1.getLootTable());
        net.minecraft.world.level.storage.loot.LootParams params = new net.minecraft.world.level.storage.loot.LootParams.Builder(server)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY, g1)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, net.minecraft.world.phys.Vec3.atCenterOf(pos))
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.DAMAGE_SOURCE, server.damageSources().playerAttack(player))
                .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.KILLER_ENTITY, player)
                .withOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.DIRECT_KILLER_ENTITY, player)
                .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.LAST_DAMAGE_PLAYER, player)
                .withLuck(player.getLuck())
                .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.ENTITY);
        table.getRandomItems(params, stack -> popResource(level, pos, stack));
        if (blockEntity instanceof CorruptedCorpseBlockEntity be && !be.heldItem().isEmpty() && server.random.nextFloat() < 0.085F) {
            popResource(level, pos, be.heldItem().copy());
        }
        g1.discard();
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return createTickerHelper(type, com.yayuy.overgrownvegetation.registry.ModG1.CORRUPTED_CORPSE.get(), CorruptedCorpseBlockEntity::serverTick);
    }
}
