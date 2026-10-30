package com.yayuy.overgrownvegetation.base;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.base.build.BaseConstruction;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseGarrison;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.build.BaseUpgrade;
import com.yayuy.overgrownvegetation.base.build.BaseUpkeep;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.yayuy.overgrownvegetation.menu.BaseTerminalMenu;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 管理端末(2026-10-01)。管理棟の作戦室に置かれ、基地のレベルを見て上げる(ユーザー指定「基地レベルを管理棟で上げる」)。
 * 右クリック = 画面({@link com.yayuy.overgrownvegetation.menu.BaseTerminalMenu}。状態・寄付・取引のタブ。順 2 の着手順 5、2026-10-07)、
 * 素手でしゃがんで右クリック = 持ち物から払って拡張の工事を始める(画面の「拡張する」ボタンと同じ {@link #upgrade})。
 * 護衛・援軍の指揮・共同研究・箱庭式の予定地の地図もこの画面に載せる予定(どれも 1.0.0 の後。護衛・援軍は 10-07 に回した)。
 */
public class BaseTerminalBlock extends BaseEntityBlock {

    public static final MapCodec<BaseTerminalBlock> CODEC = simpleCodec(BaseTerminalBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public BaseTerminalBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BaseTerminalBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        ServerLevel server = (ServerLevel) level;
        BaseSites data = BaseSites.get(server.getServer());
        BaseSite site = null;
        if (level.getBlockEntity(pos) instanceof BaseTerminalBlockEntity be) {
            site = be.siteId() > 0 ? data.byId(be.siteId()) : null;
            if (site == null) {
                site = data.nearest(level.dimension(), pos, 64);
                if (site != null) {
                    be.setSiteId(site.id);
                }
            }
        }
        if (site == null) {
            player.sendSystemMessage(Component.translatable("message.overgrown_vegetation.base.no_site"));
            return InteractionResult.CONSUME;
        }
        if (site.fallen()) { // 仮の陥落(順 2 の着手順 1)。順 5 の奪還まで何もできない(画面も開かない)
            player.sendSystemMessage(Component.translatable("message.overgrown_vegetation.base.is_fallen", site.id));
            return InteractionResult.CONSUME;
        }
        if (!player.isShiftKeyDown()) {
            // 画面(状態・寄付・取引。順 2 の着手順 5)。工事中・前の版でも寄付と取引はできる(拡張だけ止まる)
            if (player instanceof ServerPlayer sp) {
                BaseTerminalMenu.open(sp, server, pos, site);
            }
            return InteractionResult.CONSUME;
        }
        upgrade(server, pos, player, data, site, true);
        return InteractionResult.CONSUME;
    }

    /** 拡張の結果({@link #upgrade})。 */
    public enum UpgradeResult {
        STARTED, OUTDATED, BUILDING, MAX, CANNOT_PAY
    }

    /**
     * 持ち物から払って次のレベルの工事を始める(素手のしゃがみ右クリックと、画面の「拡張する」ボタン)。{@code chat} ならできなかった理由もチャットに出す
     * (始めたときは どちらでもチャットに出す)。
     */
    public static UpgradeResult upgrade(ServerLevel level, BlockPos pos, Player player, BaseSites data, BaseSite site, boolean chat) {
        int lv = site.level();
        if (site.outdated()) {
            if (chat) {
                player.sendSystemMessage(Component.translatable("message.overgrown_vegetation.base.old_version", site.id));
            }
            return UpgradeResult.OUTDATED;
        }
        if (site.building()) {
            if (chat) {
                player.sendSystemMessage(Component.translatable("message.overgrown_vegetation.base.building", site.id, lv, site.buildTarget()));
            }
            return UpgradeResult.BUILDING;
        }
        if (BaseUpgrade.isMax(lv)) {
            if (chat) {
                player.sendSystemMessage(Component.translatable("message.overgrown_vegetation.base.max", site.id, lv));
                player.sendSystemMessage(BaseEconomy.describe(level.getServer(), site));
                player.sendSystemMessage(BaseGarrison.describe(site));
                player.sendSystemMessage(BaseUpkeep.describe(site));
            }
            return UpgradeResult.MAX;
        }
        List<ItemStack> cost = BaseUpgrade.cost(lv + 1);
        if (!BaseUpgrade.canPay(player, cost)) {
            if (chat) {
                player.sendSystemMessage(Component.translatable("message.overgrown_vegetation.base.cannot_pay", BaseUpgrade.describe(cost)));
            }
            return UpgradeResult.CANNOT_PAY;
        }
        BaseUpgrade.pay(player, cost);
        BaseConstruction.start(data, site, lv, lv + 1);
        level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0F, 1.2F);
        player.sendSystemMessage(Component.translatable("message.overgrown_vegetation.base.upgrade_started", site.id, lv + 1));
        if (!site.grows(lv, lv + 1)) {
            player.sendSystemMessage(Component.translatable("message.overgrown_vegetation.base.no_room", site.id, lv + 1));
        }
        return UpgradeResult.STARTED;
    }
}
