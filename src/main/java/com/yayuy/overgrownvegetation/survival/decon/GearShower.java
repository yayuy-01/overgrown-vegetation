package com.yayuy.overgrownvegetation.survival.decon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * 洗い場の上に立った人の装備を洗う(2026-11-14 サバイバルの背骨 段 3b)。玄関の床に置いた洗い場を通ると、着ている物と手の物の汚れが
 * 1 tick ごとに {@code dirt.showerPerTick} ずつ落ちる【仮】(3 段の汚れが約 2 秒で落ちる)。洗っている間だけ FE を使う。
 */
public final class GearShower {

    private GearShower() {
    }

    /** 上に立っている人の装備を少し洗う。洗ったら true(FE を使う)。 */
    static boolean washStanding(ServerLevel level, BlockPos pos, WasherBlockEntity be) {
        int amount = GearDirt.spec().showerPerTick();
        if (amount <= 0) return false;
        AABB top = new AABB(pos.getX(), pos.getY() + 1.0D, pos.getZ(), pos.getX() + 1.0D, pos.getY() + 1.6D, pos.getZ() + 1.0D);
        boolean washed = false;
        for (Player p : level.getEntitiesOfClass(Player.class, top, Player::isAlive)) {
            if (p.isSpectator()) continue;
            washed |= washPlayer(p, amount);
        }
        if (washed && level.getGameTime() % 4 == 0) {
            level.sendParticles(ParticleTypes.SPLASH, pos.getX() + 0.5D, pos.getY() + 1.3D, pos.getZ() + 0.5D, 10, 0.25D, 0.4D, 0.25D, 0.0D);
        }
        return washed;
    }

    /** 1 人の着ている物と手の物の汚れを {@code amount} ずつ落とす。落とした物があれば true(GameTest からも)。 */
    public static boolean washPlayer(Player p, int amount) {
        boolean any = false;
        for (EquipmentSlot slot : GearDirt.SLOTS) {
            ItemStack st = p.getItemBySlot(slot);
            if (GearDirt.dirt(st) > 0) {
                GearDirt.wash(st, amount);
                any = true;
            }
        }
        return any;
    }
}
