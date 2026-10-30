package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.defense.SunlitLanterns;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * おとりの灯籠の中身(2026-10-16、㉘)。燃料(石炭・木炭)がある間だけ灯り、周りの G1 と小さな敵を灯籠のそばへ呼び寄せる。
 * 仮決め: 石炭 1 個で 2 分・8 個まで / 半径 {@link #RADIUS} / {@link #LURE_INTERVAL} tick ごとに、狙う相手のいない Mob の行き先を灯籠にする。
 * 効く相手は陽溜まりのランタンと同じ({@link SunlitLanterns#affects})。罠(逆茂木・地雷)の前に置く使い方。
 */
public class LureLanternBlockEntity extends BlockEntity {

    public static final int FUEL_PER_COAL = 2400;
    public static final int MAX_COAL = 8;
    public static final int MAX_FUEL = FUEL_PER_COAL * MAX_COAL;
    public static final double RADIUS = 16.0D;
    public static final int LURE_INTERVAL = 40;
    private static final int STEP = 20;

    private int fuel;

    public LureLanternBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LURE_LANTERN.get(), pos, state);
    }

    public int fuel() {
        return fuel;
    }

    /** 石炭を {@code coal} 個足す(入った数を返す)。 */
    public int addCoal(int coal) {
        int room = (MAX_FUEL - fuel) / FUEL_PER_COAL;
        int n = Math.min(room, coal);
        if (n > 0) {
            fuel += n * FUEL_PER_COAL;
            updateLit(getBlockState());
            setChanged();
        }
        return n;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, LureLanternBlockEntity be) {
        if (be.fuel <= 0) {
            return;
        }
        long t = level.getGameTime() + pos.asLong();
        if (Math.floorMod(t, STEP) == 0) {
            be.fuel = Math.max(0, be.fuel - STEP);
            if (be.fuel == 0) {
                be.updateLit(state);
            }
            be.setChanged();
        }
        if (Math.floorMod(t, LURE_INTERVAL) == 0) {
            be.lure(level, pos);
        }
    }

    /** 周りの効く相手を呼び寄せる。呼んだ数を返す。 */
    public int lure(Level level, BlockPos pos) {
        int n = 0;
        for (PathfinderMob mob : level.getEntitiesOfClass(PathfinderMob.class, new AABB(pos).inflate(RADIUS),
                m -> m.isAlive() && m.getTarget() == null && SunlitLanterns.affects(m))) {
            if (mob.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) > 4.0D) {
                mob.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 1.0D);
                n++;
            }
        }
        return n;
    }

    private void updateLit(BlockState state) {
        if (level == null || !state.hasProperty(LureLanternBlock.LIT)) {
            return;
        }
        boolean lit = fuel > 0;
        if (state.getValue(LureLanternBlock.LIT) != lit) {
            level.setBlock(worldPosition, state.setValue(LureLanternBlock.LIT, lit), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Fuel", fuel);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        fuel = Mth.clamp(tag.getInt("Fuel"), 0, MAX_FUEL);
    }
}
