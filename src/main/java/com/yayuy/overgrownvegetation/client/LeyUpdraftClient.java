package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.block.LeyUpdraftBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 上昇気流機のクライアント側(2026-10-22)。動いている間だけ毎 tick 呼ばれる。
 * <ul>
 *     <li>手元のプレイヤーが柱の中にいれば持ち上げる(プレイヤーの動きはクライアントが持つので、サーバーで速さを変えても効かない)。</li>
 *     <li>柱に沿って昇る白い筋と灰を出す(気流が見えるように。量は仮決め)。</li>
 * </ul>
 */
public final class LeyUpdraftClient {

    private LeyUpdraftClient() {
    }

    public static void tick(Level level, BlockPos pos, BlockState state, LeyUpdraftBlockEntity be) {
        int height = LeyUpdraftBlockEntity.height(level, pos);
        if (height <= 0) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.level() == level && LeyUpdraftBlockEntity.canLift(player, pos)
                && LeyUpdraftBlockEntity.column(pos, height).intersects(player.getBoundingBox())) {
            LeyUpdraftBlockEntity.lift(player);
        }
        RandomSource r = level.random;
        double x = pos.getX() + 0.5D, z = pos.getZ() + 0.5D;
        // 口から吹き上がる筋
        if (r.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.CLOUD, x + (r.nextDouble() - 0.5D) * 0.5D, pos.getY() + 1.05D,
                    z + (r.nextDouble() - 0.5D) * 0.5D, 0.0D, 0.25D + r.nextDouble() * 0.1D, 0.0D);
        }
        // 柱の途中を昇る灰
        for (int i = 0; i < 2; i++) {
            double y = pos.getY() + 1.0D + r.nextDouble() * height;
            level.addParticle(ParticleTypes.WHITE_ASH, x + (r.nextDouble() - 0.5D) * 0.8D, y,
                    z + (r.nextDouble() - 0.5D) * 0.8D, 0.0D, 0.3D, 0.0D);
        }
    }
}
