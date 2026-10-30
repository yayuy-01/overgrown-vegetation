package com.yayuy.overgrownvegetation.defense;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 鳴子の紐と板の<b>揺れ</b>(クライアントだけ。2026-10-15 ユーザー指定「当たった所が実際に触れたように揺れる」)。
 *
 * <ul>
 *     <li>紐: {@link ClapperRope#SEGMENTS} + 1 個の点の鎖(両端は柱に固定)。各点は前の位置との差を速さとして持つ(Verlet)。
 *     重さの代わりに「張った時の形(垂れの曲線)へ戻るばね」で垂れを保ち、隣の点との距離が張った時より伸びないようにする。</li>
 *     <li>触れた物: 当たり判定の箱の中に入った点を、その物が動いた向きへ押し、箱の外へ追い出す。</li>
 *     <li>板: 吊った点からぶら下がる振り子(紐に沿った向き・紐と直角の向きの 2 つの角度)。吊った点が動くと揺さぶられ、
 *     物が直接ぶつかると弾かれる。隣の板と打ち合うとカラッと鳴る(手元だけの音)。</li>
 *     <li>つながった先の紐が鳴った合図({@link #shake})では、紐全体を揺すり、板を振る。</li>
 *     <li>止まっている間は計算しない(眠る)。</li>
 * </ul>
 * 揺れは見た目だけで、紐は物を押し返さない。
 */
public final class ClapperRopePhysics {

    private static final int N = ClapperRope.SEGMENTS + 1;
    /** 張った形へ戻るばねの強さ(1 tick あたりの割合)。 */
    private static final double SHAPE_SPRING = 0.06D;
    /** 速さの減り方(1 tick あたり)。 */
    private static final double DAMPING = 0.9D;
    /** 板の振れの減り方。 */
    private static final double BOARD_DAMPING = 0.93D;
    /** 板の振り子の長さ(吊った点 → 板の重さの中心)。 */
    private static final double BOARD_LENGTH = ClapperRope.BOARD_DROP + ClapperRope.BOARD_HEIGHT * 0.5D;

    final double[] px = new double[N], py = new double[N], pz = new double[N];
    final double[] ox = new double[N], oy = new double[N], oz = new double[N];
    private final double[] rx = new double[N], ry = new double[N], rz = new double[N];
    private final double[] segLen = new double[N];

    /** 板ごとの角度(紐と直角の向きへの振れ・紐に沿った向きへの振れ)と、前の tick の角度。 */
    float[] swingA = new float[0], swingB = new float[0], prevA = new float[0], prevB = new float[0];
    private float[] velA = new float[0], velB = new float[0];
    private int[] clackCooldown = new int[0];

    private int sleepTicks;
    private boolean ready;

    /** 張った時の形(と板の数)から作り直す。 */
    void reset(Vec3 a, Vec3 b, float sag, int boards) {
        for (int i = 0; i < N; i++) {
            Vec3 p = ClapperRope.curvePoint(a, b, sag, (double) i / ClapperRope.SEGMENTS);
            rx[i] = px[i] = ox[i] = p.x;
            ry[i] = py[i] = oy[i] = p.y;
            rz[i] = pz[i] = oz[i] = p.z;
        }
        for (int i = 1; i < N; i++) {
            segLen[i] = Math.sqrt(sq(rx[i] - rx[i - 1]) + sq(ry[i] - ry[i - 1]) + sq(rz[i] - rz[i - 1]));
        }
        resizeBoards(boards);
        ready = true;
        sleepTicks = 0;
    }

    void resizeBoards(int boards) {
        if (swingA.length == boards) {
            return;
        }
        swingA = new float[boards];
        swingB = new float[boards];
        prevA = new float[boards];
        prevB = new float[boards];
        velA = new float[boards];
        velB = new float[boards];
        clackCooldown = new int[boards];
    }

    boolean isReady() {
        return ready;
    }

    /** 紐全体を揺すり、板を振る(つながった先の紐が鳴った合図)。 */
    void shake(RandomSource random) {
        if (!ready) {
            return;
        }
        for (int i = 1; i < N - 1; i++) {
            double w = Math.sin(Math.PI * i / (N - 1));
            ox[i] = px[i] - (random.nextDouble() - 0.5D) * 0.25D * w;
            oy[i] = py[i] - (random.nextDouble() - 0.3D) * 0.15D * w;
            oz[i] = pz[i] - (random.nextDouble() - 0.5D) * 0.25D * w;
        }
        for (int k = 0; k < velA.length; k++) {
            velA[k] += (float) ((random.nextDouble() - 0.5D) * 0.6D);
            velB[k] += (float) ((random.nextDouble() - 0.5D) * 0.4D);
        }
        sleepTicks = 0;
    }

    /**
     * 1 tick 進める。{@code box} は紐と板が占める範囲(触れた物を探す)。
     *
     * @param boardTs 板を吊った位置(紐の t)
     */
    void tick(Level level, AABB box, float[] boardTs, Vec3 ropeCenter) {
        if (!ready) {
            return;
        }
        List<Entity> touching = level.getEntities((Entity) null, box, ClapperRopePhysics::canPush);
        if (touching.isEmpty() && sleepTicks > 60) {
            return;
        }
        boolean moving = false;

        // 1. 点を動かす(速さ + 形へ戻るばね)
        for (int i = 1; i < N - 1; i++) {
            double vx = (px[i] - ox[i]) * DAMPING;
            double vy = (py[i] - oy[i]) * DAMPING;
            double vz = (pz[i] - oz[i]) * DAMPING;
            ox[i] = px[i];
            oy[i] = py[i];
            oz[i] = pz[i];
            px[i] += vx + (rx[i] - px[i]) * SHAPE_SPRING;
            py[i] += vy + (ry[i] - py[i]) * SHAPE_SPRING;
            pz[i] += vz + (rz[i] - pz[i]) * SHAPE_SPRING;
            if (vx * vx + vy * vy + vz * vz > 1.0E-6D) {
                moving = true;
            }
        }
        ox[0] = px[0];
        oy[0] = py[0];
        oz[0] = pz[0];
        ox[N - 1] = px[N - 1];
        oy[N - 1] = py[N - 1];
        oz[N - 1] = pz[N - 1];

        // 2. 触れた物が押す
        for (Entity e : touching) {
            Vec3 move = e.position().subtract(e.xo, e.yo, e.zo);
            AABB eb = e.getBoundingBox().inflate(0.08D);
            for (int i = 1; i < N - 1; i++) {
                if (!eb.contains(px[i], py[i], pz[i])) {
                    continue;
                }
                // 動いた向きへ押し、さらに箱の中心から外向きへ少し追い出す。
                double cx = (eb.minX + eb.maxX) * 0.5D;
                double cz = (eb.minZ + eb.maxZ) * 0.5D;
                double ax = px[i] - cx;
                double az = pz[i] - cz;
                double len = Math.sqrt(ax * ax + az * az);
                if (len > 1.0E-4D) {
                    ax /= len;
                    az /= len;
                }
                px[i] += move.x * 0.9D + ax * 0.04D;
                pz[i] += move.z * 0.9D + az * 0.04D;
                py[i] += Math.max(-0.05D, Math.min(0.05D, move.y * 0.5D));
                moving = true;
            }
        }

        // 3. 隣の点との距離を、張った時より伸ばさない(両端は動かさない)
        for (int iter = 0; iter < 4; iter++) {
            for (int i = 1; i < N; i++) {
                double dx = px[i] - px[i - 1];
                double dy = py[i] - py[i - 1];
                double dz = pz[i] - pz[i - 1];
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double max = segLen[i] * 1.03D;
                if (d <= max || d < 1.0E-6D) {
                    continue;
                }
                double excess = (d - max) / d;
                boolean fixedA = i - 1 == 0;
                boolean fixedB = i == N - 1;
                double wa = fixedA ? 0.0D : fixedB ? 1.0D : 0.5D;
                double wb = fixedB ? 0.0D : fixedA ? 1.0D : 0.5D;
                px[i - 1] += dx * excess * wa;
                py[i - 1] += dy * excess * wa;
                pz[i - 1] += dz * excess * wa;
                px[i] -= dx * excess * wb;
                py[i] -= dy * excess * wb;
                pz[i] -= dz * excess * wb;
            }
        }

        // 4. 板の振り子
        int boards = Math.min(boardTs.length, swingA.length);
        for (int k = 0; k < boards; k++) {
            prevA[k] = swingA[k];
            prevB[k] = swingB[k];
            double fi = boardTs[k] * ClapperRope.SEGMENTS;
            int i0 = Math.max(0, Math.min(N - 2, (int) Math.floor(fi)));
            double f = fi - i0;
            double vx = lerp(px[i0] - ox[i0], px[i0 + 1] - ox[i0 + 1], f);
            double vz = lerp(pz[i0] - oz[i0], pz[i0 + 1] - oz[i0 + 1], f);
            // 紐に沿った向き(tx, tz)と直角の向き(nx, nz)
            double tx = rx[i0 + 1] - rx[i0];
            double tz = rz[i0 + 1] - rz[i0];
            double tl = Math.sqrt(tx * tx + tz * tz);
            if (tl < 1.0E-6D) {
                tx = 1.0D;
                tz = 0.0D;
                tl = 1.0D;
            }
            tx /= tl;
            tz /= tl;
            double nx = -tz;
            double nz = tx;
            // 吊った点が動くと、振り子は逆へ遅れて振れる。
            velA[k] -= (float) ((vx * nx + vz * nz) * 1.6D);
            velB[k] -= (float) ((vx * tx + vz * tz) * 1.2D);
            // 物が直接ぶつかったら弾かれる。
            double bx = lerp(px[i0], px[i0 + 1], f);
            double by = lerp(py[i0], py[i0 + 1], f) - BOARD_LENGTH;
            double bz = lerp(pz[i0], pz[i0 + 1], f);
            for (Entity e : touching) {
                if (e.getBoundingBox().inflate(0.15D).contains(bx, by, bz)) {
                    Vec3 move = e.position().subtract(e.xo, e.yo, e.zo);
                    velA[k] += (float) ((move.x * nx + move.z * nz) * 2.5D);
                    velB[k] += (float) ((move.x * tx + move.z * tz) * 2.0D);
                }
            }
            // 振り子の戻り(重さ)と減り
            velA[k] = (float) ((velA[k] - Math.sin(swingA[k]) * 0.18D) * BOARD_DAMPING);
            velB[k] = (float) ((velB[k] - Math.sin(swingB[k]) * 0.18D) * BOARD_DAMPING);
            swingA[k] = clampAngle(swingA[k] + velA[k]);
            swingB[k] = clampAngle(swingB[k] + velB[k]);
            if (Math.abs(velA[k]) > 0.002F || Math.abs(velB[k]) > 0.002F) {
                moving = true;
            }
            if (clackCooldown[k] > 0) {
                clackCooldown[k]--;
            }
        }
        // 隣の板と打ち合う(紐に沿った向きの振れが向き合ってすれ違ったら)
        for (int k = 1; k < boards; k++) {
            float gap = (boardTs[k] - boardTs[k - 1]) * (float) totalLength();
            if (gap > 0.6F) {
                continue;
            }
            boolean crossed = (prevB[k - 1] - prevB[k]) * (swingB[k - 1] - swingB[k]) < 0.0F
                    || Math.abs(swingB[k - 1] - swingB[k]) > 0.5F;
            float speed = Math.abs(velB[k - 1] - velB[k]) + Math.abs(velA[k - 1] - velA[k]) * 0.5F;
            if (crossed && speed > 0.08F && clackCooldown[k] == 0) {
                clackCooldown[k] = 4;
                double fi = boardTs[k] * ClapperRope.SEGMENTS;
                int i0 = Math.max(0, Math.min(N - 2, (int) Math.floor(fi)));
                level.playLocalSound(px[i0], py[i0] - BOARD_LENGTH, pz[i0], SoundEvents.BAMBOO_WOOD_BUTTON_CLICK_ON,
                        SoundSource.BLOCKS, Math.min(0.8F, speed * 2.0F), 0.9F + level.random.nextFloat() * 0.4F, false);
                float swap = velB[k - 1];
                velB[k - 1] = velB[k] * 0.7F;
                velB[k] = swap * 0.7F;
            }
        }
        sleepTicks = moving || !touching.isEmpty() ? 0 : sleepTicks + 1;
    }

    private double totalLength() {
        double sum = 0.0D;
        for (int i = 1; i < N; i++) {
            sum += segLen[i];
        }
        return sum;
    }

    private static boolean canPush(Entity e) {
        return e.isAlive() && !e.isSpectator() && !(e instanceof Player p && p.isShiftKeyDown()) && !(e instanceof net.minecraft.world.entity.item.ItemEntity);
    }

    private static float clampAngle(float a) {
        return Math.max(-1.3F, Math.min(1.3F, a));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double sq(double v) {
        return v * v;
    }
}
