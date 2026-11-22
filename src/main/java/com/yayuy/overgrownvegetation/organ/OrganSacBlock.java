package com.yayuy.overgrownvegetation.organ;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 器官の嚢のかたまり(2026-11-17)。生きている嚢は、上に乗る・叩く・矢が当たると破裂する(ユーザー決定「胞子嚢が触れると破裂する」。
 * 0.2.0 に入れる)。破裂すると枯れの状態(しぼんだ膜)になり、まわりに胞子の煙。近くの生き物は胞子を浴びる(息で浴びる = 防護マスクで防げる)。
 * スニークで静かに乗れば破れない。植物側の Mob は破らない。生え直さない【仮】。量は【仮】。
 */
public class OrganSacBlock extends OrganTissueBlock {

    public static final MapCodec<OrganSacBlock> CODEC = simpleCodec(OrganSacBlock::new);
    /** 破裂で浴びる胞子の量【仮】と届く距離。 */
    public static final double BURST_DOSE = 6.0D, BURST_RADIUS = 3.0D;

    public OrganSacBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        if (entity instanceof LivingEntity le && !le.isSteppingCarefully() && !PlantSideRules.isPlantSide(le)) {
            burst(level, pos, state);
        }
        super.stepOn(level, pos, state, entity);
    }

    @Override
    protected void attack(BlockState state, Level level, BlockPos pos, Player player) {
        burst(level, pos, state);
        super.attack(state, level, pos, player);
    }

    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        burst(level, hit.getBlockPos(), state);
    }

    /** 破裂させる(生きている嚢だけ)。破れたら true。 */
    public static boolean burst(Level level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel server) || state.getValue(WITHERED)) return false;
        server.setBlock(pos, state.setValue(WITHERED, true), Block.UPDATE_ALL);
        server.playSound(null, pos, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.BLOCKS, 1.0F, 0.6F);
        server.sendParticles(ModParticles.SPORE_BURST_CLOUD.get(), pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 18,
                0.8D, 0.6D, 0.8D, 0.02D);
        for (LivingEntity le : server.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(BURST_RADIUS))) {
            if (PlantSideRules.isPlantSide(le)) continue;
            SporeExposureTracker.addExposure(le, BURST_DOSE, true);
        }
        com.yayuy.overgrownvegetation.spore.field.SporeFieldServer.depositPoint(server, pos, 0.05D);
        return true;
    }
}
