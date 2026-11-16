package com.yayuy.overgrownvegetation.wall;

import com.yayuy.overgrownvegetation.block.WallRemainsBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModCreepingWall;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * 飲まれたプレイヤー: 持ち物を成れの果てへ移してから即死させる(決定 その 2・7)。
 * <b>殺す前に移す</b>(後からだとバニラのドロップ・keepInventory・ほかの死亡処理とぶつかる)。keepInventory のときも成れの果てへ入れる(ユーザー決定)。
 * 消滅の呪いの付いた物は、バニラの死と同じく消える。
 */
public final class WallRemains {

    private WallRemains() {
    }

    /** 成れの果てを置き、持ち物を移して殺す。置いた場所を返す(置けなかったら null。そのときは持ち物はふつうに落ちる)。 */
    @Nullable
    public static BlockPos consume(ServerLevel level, ServerPlayer player) {
        BlockPos at = findSpot(level, player.blockPosition());
        if (at != null) {
            level.setBlock(at, ModCreepingWall.WALL_REMAINS.get().defaultBlockState(), Block.UPDATE_ALL);
            if (level.getBlockEntity(at) instanceof WallRemainsBlockEntity remains) {
                remains.setOwnerName(player.getGameProfile().getName());
                Inventory inv = player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    inv.setItem(i, ItemStack.EMPTY);
                    if (EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) {
                        continue; // 消滅の呪い
                    }
                    if (!remains.add(stack)) {
                        player.drop(stack, true, false);
                    }
                }
            } else {
                at = null;
            }
        }
        player.hurt(ModCreepingWall.creepingWall(level), Float.MAX_VALUE);
        if (player.isAlive()) {
            player.setHealth(0.0F);
            player.die(ModCreepingWall.creepingWall(level));
        }
        return at;
    }

    /** 置き場所: 足元 → 上下 2 升 → まわり 2 升の空き。無ければ足元を上書き(岩盤・BlockEntity 持ちは除く)。 */
    @Nullable
    static BlockPos findSpot(ServerLevel level, BlockPos feet) {
        if (fits(level, feet)) {
            return feet;
        }
        for (int dy : new int[]{1, -1, 2, -2}) {
            if (fits(level, feet.above(dy))) {
                return feet.above(dy);
            }
        }
        for (BlockPos p : BlockPos.withinManhattan(feet, 2, 2, 2)) {
            if (fits(level, p)) {
                return p.immutable();
            }
        }
        BlockState s = level.getBlockState(feet);
        return s.getDestroySpeed(level, feet) >= 0.0F && !s.hasBlockEntity() ? feet : null;
    }

    private static boolean fits(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return level.isInWorldBounds(p) && (s.isAir() || s.canBeReplaced()) && !s.hasBlockEntity();
    }
}
