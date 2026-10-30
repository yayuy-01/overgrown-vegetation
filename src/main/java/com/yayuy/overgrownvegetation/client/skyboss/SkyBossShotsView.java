package com.yayuy.overgrownvegetation.client.skyboss;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.SkyBossShotPayload;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.skyboss.SkyBossShots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 空の巨大生物の技の弾を描く(クライアント、2026-10-19)。サーバーが撃った弾({@link SkyBossShotPayload} の SPAWN)を、同じ式
 * ({@link SkyBossShots#move})で飛ばし、終わった(END)ら消す。
 * <ul>
 *   <li>対空砲火の弾(10-19 夜ユーザー「曳光弾のように微光する硬い殻に覆われた塊」「曳光色の尾(D)を引いて飛ぶ A」): 鱗の殻の絵
 *       ({@code sky_boss_shell.png}。道具の SkyGlaucusShell が書く)を、飛ぶ向きに沿わせてカメラへ向けた板に描き、鱗のすき間と後ろの口だけ自分で光らせ
 *       ({@code sky_boss_shell_glow.png}、バニラの目の光の型)、後ろへ曳光色の尾(加算の光の帯)を引く。色はボスの絵の樹皮と芯の光({@link SkyBossGlow#GLOW}・{@link SkyBossGlow#SOFT})。</li>
 *   <li>樹液 = 樹液の結晶ブロックの絵の琥珀色(#FA8400・#D86000)、種の爆弾 = 腹の袋の暗い緑(過繁茂の胞子の花の絵 #578900・#222C12)の光る塊。</li>
 * </ul>
 * 大きさ・尾の長さは仮。
 */
final class SkyBossShotsView {

    private static final SkyBossShots SHOTS = new SkyBossShots();
    private static final ResourceLocation SHELL = OvergrownVegetation.id("textures/entity/sky_boss_shell.png");
    private static final ResourceLocation SHELL_GLOW = OvergrownVegetation.id("textures/entity/sky_boss_shell_glow.png");
    /** 殻の長さの半分・高さの半分(ブロック。絵の 40×22 の比)と、尾の長さ・太さ(ブロック)。 */
    private static final double SHELL_HALF_LEN = 1.7;
    private static final double SHELL_HALF_H = SHELL_HALF_LEN * 22.0 / 40.0;
    private static final double TAIL_LEN = 26.0;
    private static final double TAIL_CORE = 0.8;
    private static final double TAIL_HALO = 2.6;
    private static final int TAIL_STEPS = 6;
    /** 尾を真正面(真後ろ)から見たときの明るさの割合(横から見て 1)。 */
    private static final double TAIL_END_ON = 0.15;
    /** 樹液・種の爆弾の光る塊の大きさ(半径、ブロック)。 */
    private static final double SIZE_SAP = 1.8;
    private static final double SIZE_SEED = 3.2;

    static void onPayload(SkyBossShotPayload p, ClientLevel level) {
        if (p.op() == SkyBossShotPayload.SPAWN) {
            SkyBossShots.Shot s = SHOTS.add(p.id(), p.kind(), p.x(), p.y(), p.z(), p.vx(), p.vy(), p.vz(), p.born(), p.life());
            SkyBossShots.advanceTo(s, level.getGameTime());
        } else {
            SkyBossShots.Shot s = SHOTS.find(p.id());
            if (s != null) {
                s.dead = true;
            }
        }
        if (SHOTS.list.size() > 512) {
            SHOTS.list.remove(0);
        }
    }

    static void clear() {
        SHOTS.list.clear();
    }

    /** tick ごと: 弾を進め、跡の粒を出す(対空砲火の弾は胞子のもやを 2 tick に 1 つ)。 */
    static void tick(ClientLevel level) {
        long now = level.getGameTime();
        SHOTS.list.removeIf(s -> s.dead);
        for (SkyBossShots.Shot s : SHOTS.list) {
            SkyBossShots.advanceTo(s, now);
            if (s.dead) {
                continue;
            }
            switch (s.kind) {
                case SkyBossShots.SPORE -> {
                    if (((now + s.id) & 1) == 0) {
                        level.addAlwaysVisibleParticle(ModParticles.OVERGROWN_SPORE_SHORT.get(), true, s.x, s.y, s.z, 0.0, 0.0, 0.0);
                    }
                }
                case SkyBossShots.SAP -> level.addAlwaysVisibleParticle(ParticleTypes.FALLING_HONEY, true, s.x, s.y, s.z, 0.0, 0.0, 0.0);
                default -> level.addAlwaysVisibleParticle(ModParticles.FINE_SPORE.get(), true, s.x, s.y, s.z, 0.0, 0.0, 0.0);
            }
        }
    }

    /** 描く({@link SkyBossRenderer} が体の後に)。殻(切り抜き)→ 殻の光(目の光の型)→ 尾とほかの弾(加算)の順に、型ごとに描き終える。 */
    static void draw(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 cam, float partialTick) {
        if (SHOTS.list.isEmpty()) {
            return;
        }
        PoseStack.Pose last = pose.last();
        Matrix4f m = last.pose();
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        org.joml.Vector3f left = camera.getLeftVector();
        org.joml.Vector3f up = camera.getUpVector();
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        int light = LightTexture.pack(0, 15);
        // 殻
        RenderType baseType = RenderType.entityCutoutNoCull(SHELL);
        VertexConsumer base = buffers.getBuffer(baseType);
        for (SkyBossShots.Shot s : SHOTS.list) {
            if (!s.dead && s.kind == SkyBossShots.SPORE) {
                shell(base, last, s, cam, partialTick, left, up, light, false);
            }
        }
        buffers.endBatch(baseType);
        // 鱗のすき間と後ろの口の微光
        RenderType glowType = RenderType.eyes(SHELL_GLOW);
        VertexConsumer glow = buffers.getBuffer(glowType);
        for (SkyBossShots.Shot s : SHOTS.list) {
            if (!s.dead && s.kind == SkyBossShots.SPORE) {
                shell(glow, last, s, cam, partialTick, left, up, LightTexture.FULL_BRIGHT, true);
            }
        }
        buffers.endBatch(glowType);
        // 曳光の尾・樹液と種の爆弾の光る塊
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        for (SkyBossShots.Shot s : SHOTS.list) {
            if (s.dead) {
                continue;
            }
            double x = s.px + (s.x - s.px) * partialTick - cam.x;
            double y = s.py + (s.y - s.py) * partialTick - cam.y;
            double z = s.pz + (s.z - s.pz) * partialTick - cam.z;
            switch (s.kind) {
                case SkyBossShots.SPORE -> tail(vc, m, s, x, y, z);
                case SkyBossShots.SAP -> {
                    blob(vc, m, x, y, z, left, up, SIZE_SAP * 2.0, 0xD86000, 0.35F);
                    blob(vc, m, x, y, z, left, up, SIZE_SAP, 0xFA8400, 0.85F);
                }
                default -> {
                    blob(vc, m, x, y, z, left, up, SIZE_SEED * 1.8, 0x222C12, 0.5F);
                    blob(vc, m, x, y, z, left, up, SIZE_SEED, 0x578900, 0.8F);
                }
            }
        }
        buffers.endBatch(RenderType.lightning());
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    /**
     * 殻の板 1 枚: 長さの向き = 飛ぶ向きを画面へ落とした向き(絵の右 = 前)、高さの向き = それとカメラへの向きに直角で、画面の上の側(絵の上 = 明るい鱗)。
     * {@code both} = 裏表とも(目の光の型は裏を描かないことがあるので)。
     */
    private static void shell(VertexConsumer vc, PoseStack.Pose last, SkyBossShots.Shot s, Vec3 cam, float pt, org.joml.Vector3f left,
                              org.joml.Vector3f up, int light, boolean both) {
        double x = s.px + (s.x - s.px) * pt - cam.x;
        double y = s.py + (s.y - s.py) * pt - cam.y;
        double z = s.pz + (s.z - s.pz) * pt - cam.z;
        double vl = Math.sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz);
        double dl = Math.sqrt(x * x + y * y + z * z);
        if (vl < 1.0E-6 || dl < 1.0E-6) {
            return;
        }
        double wx = x / dl;
        double wy = y / dl;
        double wz = z / dl;
        double dx = s.vx / vl;
        double dy = s.vy / vl;
        double dz = s.vz / vl;
        double k = dx * wx + dy * wy + dz * wz;
        double ax = dx - wx * k;
        double ay = dy - wy * k;
        double az = dz - wz * k;
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        if (al < 1.0E-3) {
            // 真っすぐこちらへ(向こうへ)飛ぶ: 画面の横で
            ax = -left.x();
            ay = -left.y();
            az = -left.z();
            al = 1.0;
        }
        ax /= al;
        ay /= al;
        az /= al;
        double bx = wy * az - wz * ay;
        double by = wz * ax - wx * az;
        double bz = wx * ay - wy * ax;
        double bl = Math.max(1.0E-6, Math.sqrt(bx * bx + by * by + bz * bz));
        bx /= bl;
        by /= bl;
        bz /= bl;
        if (bx * up.x() + by * up.y() + bz * up.z() < 0.0) {
            bx = -bx;
            by = -by;
            bz = -bz;
        }
        double L = SHELL_HALF_LEN;
        double H = SHELL_HALF_H;
        float[][] p = {
                {(float) (x - ax * L + bx * H), (float) (y - ay * L + by * H), (float) (z - az * L + bz * H), 0.0F, 0.0F},
                {(float) (x + ax * L + bx * H), (float) (y + ay * L + by * H), (float) (z + az * L + bz * H), 1.0F, 0.0F},
                {(float) (x + ax * L - bx * H), (float) (y + ay * L - by * H), (float) (z + az * L - bz * H), 1.0F, 1.0F},
                {(float) (x - ax * L - bx * H), (float) (y - ay * L - by * H), (float) (z - az * L - bz * H), 0.0F, 1.0F}};
        for (float[] v : p) {
            vertex(vc, last, v, light);
        }
        if (both) {
            for (int i = 3; i >= 0; i--) {
                vertex(vc, last, p[i], light);
            }
        }
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose last, float[] v, int light) {
        vc.addVertex(last, v[0], v[1], v[2]).setColor(255, 255, 255, 255).setUv(v[3], v[4]).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(last, 0.0F, 1.0F, 0.0F);
    }

    /** 曳光の尾: 殻の後ろから、飛んできた向きへ {@link #TAIL_LEN}。芯(曳光色)と、まわりのにじみ。先(殻の側)ほど明るく太い。 */
    private static void tail(VertexConsumer vc, Matrix4f m, SkyBossShots.Shot s, double x, double y, double z) {
        double vl = Math.sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz);
        if (vl < 1.0E-6) {
            return;
        }
        double dx = s.vx / vl;
        double dy = s.vy / vl;
        double dz = s.vz / vl;
        // 撃った所より後ろへは伸ばさない(蕾から出たばかりの弾)
        double flown = (s.tick - s.born) * vl;
        double len = Math.min(TAIL_LEN, Math.max(0.0, flown - SHELL_HALF_LEN));
        if (len <= 0.5) {
            return;
        }
        // 正面(こちらへ向かってくる弾)ほど薄く: 帯が縮んで重なり、加算で黄色い塊になった(10-19 夜のテストクライアント、撃たれる側から)
        double dl = Math.sqrt(x * x + y * y + z * z);
        double side = 1.0;
        if (dl > 1.0E-6) {
            double cx = dy * z - dz * y;
            double cy = dz * x - dx * z;
            double cz = dx * y - dy * x;
            side = Math.sqrt(cx * cx + cy * cy + cz * cz) / dl;
        }
        float fade = (float) (TAIL_END_ON + (1.0 - TAIL_END_ON) * side);
        double sx = x - dx * SHELL_HALF_LEN * 0.8;
        double sy = y - dy * SHELL_HALF_LEN * 0.8;
        double sz = z - dz * SHELL_HALF_LEN * 0.8;
        for (int i = 0; i < TAIL_STEPS; i++) {
            double f0 = i / (double) TAIL_STEPS;
            double f1 = (i + 1) / (double) TAIL_STEPS;
            double x0 = sx - dx * len * f0;
            double y0 = sy - dy * len * f0;
            double z0 = sz - dz * len * f0;
            double x1 = sx - dx * len * f1;
            double y1 = sy - dy * len * f1;
            double z1 = sz - dz * len * f1;
            float a0 = (float) ((1.0 - f0) * (1.0 - f0)) * fade;
            float a1 = (float) ((1.0 - f1) * (1.0 - f1)) * fade;
            streak(vc, m, x0, y0, z0, x1, y1, z1, TAIL_HALO * (1.0 - 0.7 * f0), TAIL_HALO * (1.0 - 0.7 * f1), SkyBossGlow.SOFT, 0.22F * a0, 0.22F * a1);
            streak(vc, m, x0, y0, z0, x1, y1, z1, TAIL_CORE * (1.0 - 0.8 * f0), TAIL_CORE * (1.0 - 0.8 * f1), SkyBossGlow.GLOW, 0.85F * a0, 0.85F * a1);
        }
    }

    /** p0 → p1 のカメラを向いた帯 1 枚(幅 w0 → w1・明るさ a0 → a1)。バニラの雷の型は裏を描かないので、雷・ビームと同じ並び。 */
    private static void streak(VertexConsumer vc, Matrix4f m, double x0, double y0, double z0, double x1, double y1, double z1,
                               double w0, double w1, int rgb, float a0, float a1) {
        double tx = x1 - x0;
        double ty = y1 - y0;
        double tz = z1 - z0;
        double mx = (x0 + x1) * 0.5;
        double my = (y0 + y1) * 0.5;
        double mz = (z0 + z1) * 0.5;
        double cx = ty * mz - tz * my;
        double cy = tz * mx - tx * mz;
        double cz = tx * my - ty * mx;
        double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (cl < 1.0E-9) {
            return;
        }
        cx /= cl;
        cy /= cl;
        cz /= cl;
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int i0 = Math.max(0, Math.min(255, Math.round(a0 * 255.0F)));
        int i1 = Math.max(0, Math.min(255, Math.round(a1 * 255.0F)));
        double h0 = w0 * 0.5;
        double h1 = w1 * 0.5;
        vc.addVertex(m, (float) (x0 + cx * h0), (float) (y0 + cy * h0), (float) (z0 + cz * h0)).setColor(r, g, b, i0);
        vc.addVertex(m, (float) (x0 - cx * h0), (float) (y0 - cy * h0), (float) (z0 - cz * h0)).setColor(r, g, b, i0);
        vc.addVertex(m, (float) (x1 - cx * h1), (float) (y1 - cy * h1), (float) (z1 - cz * h1)).setColor(r, g, b, i1);
        vc.addVertex(m, (float) (x1 + cx * h1), (float) (y1 + cy * h1), (float) (z1 + cz * h1)).setColor(r, g, b, i1);
    }

    /**
     * カメラを向いた丸(八角形を 4 枚の四角で。加算。バニラの雷の型は裏を描かないので、カメラから見て反時計回り)。
     * 四角 1 枚だと板に見えた(10-19 のテストクライアント)。
     */
    private static void blob(VertexConsumer vc, Matrix4f m, double x, double y, double z, org.joml.Vector3f left, org.joml.Vector3f up,
                             double r, int rgb, float alpha) {
        int cr = (rgb >> 16) & 0xFF;
        int cg = (rgb >> 8) & 0xFF;
        int cb = rgb & 0xFF;
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
        float lx = (float) (left.x() * r);
        float ly = (float) (left.y() * r);
        float lz = (float) (left.z() * r);
        float ux = (float) (up.x() * r);
        float uy = (float) (up.y() * r);
        float uz = (float) (up.z() * r);
        float fx = (float) x;
        float fy = (float) y;
        float fz = (float) z;
        // 真ん中と、まわりの 8 点のうち 3 点ずつで四角 4 枚(扇の形)
        float[] px = new float[9];
        float[] py = new float[9];
        float[] pz = new float[9];
        for (int i = 0; i <= 8; i++) {
            double ang = Math.PI * 2.0 * i / 8.0;
            float c = (float) Math.cos(ang);
            float s = (float) Math.sin(ang);
            px[i] = fx + lx * c + ux * s;
            py[i] = fy + ly * c + uy * s;
            pz[i] = fz + lz * c + uz * s;
        }
        for (int q = 0; q < 4; q++) {
            int i = q * 2;
            vc.addVertex(m, fx, fy, fz).setColor(cr, cg, cb, a);
            vc.addVertex(m, px[i + 2], py[i + 2], pz[i + 2]).setColor(cr, cg, cb, a);
            vc.addVertex(m, px[i + 1], py[i + 1], pz[i + 1]).setColor(cr, cg, cb, a);
            vc.addVertex(m, px[i], py[i], pz[i]).setColor(cr, cg, cb, a);
        }
    }

    private SkyBossShotsView() {
    }
}
