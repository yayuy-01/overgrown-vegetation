package com.yayuy.overgrownvegetation.g1;

import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec3;

/**
 * 進化体の破裂(歩く胞子嚢・破裂莢。G1 の作り直し 第 1 段): その升の浮遊と降り積もりを増やし、周りに萌芽体(今は id blight_fern)を落とす。
 * 萌芽体を落とすのは /ov corruption false の間は止める(汚染の入口)。
 */
public final class SporeBursts {

    public static void burst(ServerLevel level, Vec3 center, double air, double deposit, int sprouts) {
        BlockPos pos = BlockPos.containing(center);
        SporeFieldServer.emitPoint(level, pos, air);
        SporeFieldServer.depositPoint(level, pos, deposit);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, center.x, center.y + 0.8D, center.z, 160, 2.0D, 1.2D, 2.0D, 0.05D);
        level.sendParticles(ParticleTypes.SNEEZE, center.x, center.y + 0.8D, center.z, 40, 1.5D, 1.0D, 1.5D, 0.05D);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 2.0F, 0.5F);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.MOSS_BREAK, SoundSource.HOSTILE, 1.5F, 0.6F);
        if (CorruptionSwitch.isOff()) {
            return;
        }
        int placed = 0;
        for (int i = 0; i < sprouts * 4 && placed < sprouts; i++) {
            int x = pos.getX() + level.random.nextInt(7) - 3;
            int z = pos.getZ() + level.random.nextInt(7) - 3;
            BlockPos at = MoistDarkPlaces.standAt(level, x, pos.getY(), z);
            if (at != null && BlightFernPlacement.spawn(level, at, MobSpawnType.MOB_SUMMONED) != null) {
                placed++;
            }
        }
    }

    private SporeBursts() {
    }
}
