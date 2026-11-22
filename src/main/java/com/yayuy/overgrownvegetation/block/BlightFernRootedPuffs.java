package com.yayuy.overgrownvegetation.block;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 萌芽体の胞子嚢がしぼむ瞬間の噴き出し(2026-10-23 萌芽体のモデル)。アニメの timeline の {@code puff:<番号>} で呼ばれる
 * (根付いた姿 = rooted_idle、歩く姿 = 胞子嚢の脈 sacs。クライアントだけ。GeckoLib はアニメを描画のときに進めるので、見えている株だけが噴く)。
 *
 * <p>胞子嚢の先の 1 点から、小さな粒が上向きの細い円錐へ勢いよく噴き出す({@code client/SporeJets}。ユーザー指定 10-23:
 * 「大きな胞子 1 粒がポッと出る」のをやめ、地割れの裂け目から吹き上がるように)。数・時間は仮決め。
 */
public final class BlightFernRootedPuffs {

    /** 根付いた姿の噴き出し: 粒の数と噴く長さ(tick。0.4 秒)。 */
    private static final int ROOTED_COUNT = 32;
    private static final int ROOTED_TICKS = 8;
    /** 歩く姿の脈の噴き出し(少なめ)。 */
    private static final int WALKING_COUNT = 12;
    private static final int WALKING_TICKS = 6;

    /**
     * 胞子嚢の先の位置と向き {x, y, z, dx, dy, dz}(位置はブロック、模型の原点 = 足元の真ん中から。ファイルの空間で、x はゲームでは反転)。
     * 生成器 tools/g5-models/mobs/Hougatai の report の値。模型を作り直したら写し直す。
     */
    private static final double[][] ROOTED_TIPS = {
            {0.000, 0.395, -0.383, 0.000, 0.976, -0.216},
            {-0.391, 0.281, -0.385, -0.345, 0.774, -0.531},
            {0.213, 0.471, -0.368, 0.108, 0.976, -0.187},
            {0.236, 0.299, 0.475, 0.169, 0.850, 0.498},
            {0.514, 0.296, -0.180, 0.438, 0.893, -0.100},
            {-0.149, 0.269, 0.349, -0.287, 0.893, 0.346},
            {-0.488, 0.288, 0.025, -0.677, 0.733, 0.059},
            {0.338, 0.244, 0.209, 0.172, 0.976, 0.132},
            {0.231, 0.317, 0.019, 0.216, 0.976, -0.019},
    };
    private static final double[][] WALKING_TIPS = {
            {0.000, 0.641, -0.317, 0.000, 0.976, -0.216},
            {-0.388, 0.568, -0.380, -0.345, 0.774, -0.531},
            {0.200, 0.742, -0.346, 0.108, 0.976, -0.187},
            {0.239, 0.588, 0.480, 0.169, 0.850, 0.498},
            {0.462, 0.564, -0.157, 0.438, 0.893, -0.100},
            {-0.159, 0.564, 0.367, -0.287, 0.893, 0.346},
            {-0.476, 0.575, 0.024, -0.677, 0.733, 0.059},
            {0.316, 0.514, 0.192, 0.172, 0.976, 0.132},
            {0.230, 0.604, 0.019, 0.216, 0.976, -0.019},
    };

    private BlightFernRootedPuffs() {
    }

    static void puff(BlightFernRootedBlockEntity be, String instructions) {
        Level level = be.getLevel();
        if (level == null) {
            return;
        }
        int k = sac(instructions);
        if (k > 0) {
            emit(level, be.getBlockPos().getX() + 0.5D, be.getBlockPos().getY(), be.getBlockPos().getZ() + 0.5D,
                    be.yaw(), ROOTED_TIPS[k - 1], ROOTED_COUNT, ROOTED_TICKS, true);
        }
    }

    /** 歩く萌芽体の胞子嚢の脈(sacs の timeline)から。 */
    public static void puffWalking(Entity fern, float bodyYaw, String instructions) {
        int k = sac(instructions);
        if (k > 0) {
            emit(fern.level(), fern.getX(), fern.getY(), fern.getZ(), bodyYaw, WALKING_TIPS[k - 1], WALKING_COUNT, WALKING_TICKS, false);
        }
    }

    private static int sac(String instructions) {
        for (String part : instructions.split(";")) {
            String s = part.trim();
            if (s.startsWith("puff:")) {
                try {
                    int k = Integer.parseInt(s.substring(5).trim());
                    return k >= 1 && k <= ROOTED_TIPS.length ? k : 0;
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static void emit(Level level, double ox, double oy, double oz, float yaw, double[] tip, int count, int ticks, boolean sound) {
        // 描画と同じ回し方: ファイルの x を反転 → Y 軸まわりに (180 − 向き) 度
        double a = Math.toRadians(180.0D - yaw);
        double cos = Math.cos(a), sin = Math.sin(a);
        double fx = -tip[0], fz = tip[2];
        double dx = -tip[3], dz = tip[5];
        double x = ox + fx * cos + fz * sin;
        double z = oz - fx * sin + fz * cos;
        double vx = dx * cos + dz * sin;
        double vz = -dx * sin + dz * cos;
        if (level.getFluidState(net.minecraft.core.BlockPos.containing(x, oy + tip[1], z)).is(net.minecraft.tags.FluidTags.WATER)) {
            // 水の中の株(2026-10-24 追記 18): 胞子の噴き出しの代わりに泡が上がる
            for (int i = 0; i < count / 3; i++) {
                level.addParticle(net.minecraft.core.particles.ParticleTypes.BUBBLE, x, oy + tip[1], z,
                        vx * 0.15D + (level.getRandom().nextDouble() - 0.5D) * 0.05D, 0.08D + level.getRandom().nextDouble() * 0.1D,
                        vz * 0.15D + (level.getRandom().nextDouble() - 0.5D) * 0.05D);
            }
            return;
        }
        com.yayuy.overgrownvegetation.client.SporeJets.start(x, oy + tip[1], z, vx, tip[4], vz, count, ticks);
        if (sound) {
            level.playLocalSound(x, oy + tip[1], z, net.minecraft.sounds.SoundEvents.MOSS_CARPET_STEP, net.minecraft.sounds.SoundSource.BLOCKS,
                    0.2F, Mth.nextFloat(level.getRandom(), 1.4F, 1.7F), false);
        }
    }
}
