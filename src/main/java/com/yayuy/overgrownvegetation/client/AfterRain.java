package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * <b>雨上がりの森</b>(2026-10-17。10-16 にユーザーが選んだ「密林をさらに美しく見せる案」の (4)「雨上がり: 葉先から滴が落ち、水たまり・葉がきらめき、
 * 地面から湯気」。ユーザー「まだ作っていない案の次を進めて」)。
 *
 * <p>過繁茂の森で雨が降ると「濡れ」が雨の強さまで上がり、止んでから {@value #DRY_TICKS} tick(2 分)かけて乾く。濡れている間:
 * <ul>
 *     <li><b>葉先の滴</b>: 頭の上の葉の下面に水玉がぶら下がって膨らみ、落ちて地面ではねる。雨の最中も
 *     (過繁茂の葉はバニラの葉ではないので、雨の日の葉の滴が出なかった)。最初はバニラの滴の粒(青い四角)だったが、
 *     ユーザー「青い滴も半透明でしっかり水のように見えるように」(10-17)で、自前の半透明の水玉(縁が濃く真ん中が透けるしずく形・
 *     その場所の明かりで明るさが変わる・日の当たる所で小さな照り)にした。</li>
 *     <li><b>きらめき</b>(昼・雨が止んだ後): 日が当たる地面(太陽へたどって葉を 2 枚以下しか通らない所)で、小さな光が一瞬またたく。</li>
 *     <li><b>湯気</b>(昼・雨が止んだ後): じかに日が当たる地面から、うっすら白い湯気がゆっくり立ちのぼって消える。</li>
 * </ul>
 * 葉そのものは光らせない(2026-10-16 ユーザー「葉を光らせるのはやめましょう」)。強さ = 設定 {@code afterRain} × 過繁茂の森にいる度合い ×
 * (1 − 地下の度合い)。数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class AfterRain {

    /** 雨が止んでから乾ききるまで(tick)。 */
    static final int DRY_TICKS = 2400;
    /** 滴を探す範囲(水平、ブロック)と、濡れ 1 のときの 1 tick の試し数。 */
    static final double RANGE = 14.0;
    static final float DRIPS_PER_TICK = 5.0F;
    /** 湯気・きらめきの数の上限と、1 tick に出す確率(濡れ・昼 1 のとき)。 */
    static final int MAX_STEAM = 36;
    static final int MAX_GLINTS = 48;
    static final float STEAM_CHANCE = 0.7F;
    static final float GLINT_CHANCE = 1.5F;

    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Wisp> STEAM = new ArrayList<>();
    private static final List<Glint> GLINTS = new ArrayList<>();
    private static ClientLevel trackedLevel;
    /** 濡れ(0〜1)。 */
    private static float wet;
    /** 今の強さ(描くときに掛ける。設定を切ると消える)。 */
    private static float strength;
    private static long ticks;
    private static boolean broken;

    /** 立ちのぼる湯気 1 つ。 */
    private static final class Wisp {
        final double x;
        final double y;
        final double z;
        final int life;
        final float rise;
        final float size;
        final float driftX;
        final float driftZ;
        int age;

        Wisp(double x, double y, double z, int life, float rise, float size, float driftX, float driftZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.life = life;
            this.rise = rise;
            this.size = size;
            this.driftX = driftX;
            this.driftZ = driftZ;
        }
    }

    /** 一瞬またたく水のきらめき 1 つ。 */
    private static final class Glint {
        final double x;
        final double y;
        final double z;
        final int life;
        final float size;
        final float bright;
        int age;

        Glint(double x, double y, double z, int life, float size, float bright) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.life = life;
            this.size = size;
            this.bright = bright;
        }
    }

    /** 葉の下面からぶら下がって膨らみ、落ちる水の滴 1 つ。 */
    private static final class Drop {
        final double x;
        final double z;
        final double hangY;
        final int hang;
        final float size;
        double y;
        double prevY;
        float vy;
        int age;

        Drop(double x, double y, double z, int hang, float size) {
            this.x = x;
            this.y = y;
            this.prevY = y;
            this.z = z;
            this.hangY = y;
            this.hang = hang;
            this.size = size;
        }
    }

    /** 滴が地面に当たってはねた小さなしぶき 1 つ。 */
    private static final class Bit {
        double x;
        double y;
        double z;
        double prevX;
        double prevY;
        double prevZ;
        float vx;
        float vy;
        float vz;
        final int life;
        final float size;
        int age;

        Bit(double x, double y, double z, float vx, float vy, float vz, int life, float size) {
            this.x = this.prevX = x;
            this.y = this.prevY = y;
            this.z = this.prevZ = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.life = life;
            this.size = size;
        }
    }

    private static final List<Drop> DROPS = new ArrayList<>();
    private static final List<Bit> BITS = new ArrayList<>();
    /** 滴の数の上限。 */
    static final int MAX_DROPS = 200;

    /** 濡れ(0〜1。ほかの演出が読むため)。 */
    public static float wetness() {
        return wet;
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != trackedLevel) {
            STEAM.clear();
            GLINTS.clear();
            DROPS.clear();
            BITS.clear();
            wet = 0.0F;
            trackedLevel = level;
        }
        if (level == null || player == null || mc.isPaused() || broken) {
            return;
        }
        try {
            tick(level, player);
        } catch (RuntimeException e) {
            broken = true;
            STEAM.clear();
            GLINTS.clear();
            DROPS.clear();
            BITS.clear();
            OvergrownVegetation.LOGGER.error("雨上がりの演出で例外が出たので、このセッションでは使わない", e);
        }
    }

    private static void tick(ClientLevel level, LocalPlayer player) {
        ticks++;
        float rain = level.getRainLevel(1.0F);
        if (rain > 0.2F) {
            wet = Math.max(wet, rain);
        } else {
            wet = Math.max(0.0F, wet - 1.0F / DRY_TICKS);
        }
        strength = OvergrownVegetationConfig.COMMON.afterRain.get().floatValue()
                * CorruptionAtmosphere.forestFactor() * (1.0F - CaveLight.underground());

        double range2 = (RANGE + 10.0) * (RANGE + 10.0);
        for (Iterator<Wisp> it = STEAM.iterator(); it.hasNext(); ) {
            Wisp w = it.next();
            if (++w.age >= w.life || player.distanceToSqr(w.x, w.y, w.z) > range2) {
                it.remove();
            }
        }
        for (Iterator<Glint> it = GLINTS.iterator(); it.hasNext(); ) {
            Glint g = it.next();
            if (++g.age >= g.life || player.distanceToSqr(g.x, g.y, g.z) > range2) {
                it.remove();
            }
        }
        for (Iterator<Drop> it = DROPS.iterator(); it.hasNext(); ) {
            Drop d = it.next();
            d.age++;
            d.prevY = d.y;
            if (d.age < d.hang) {
                continue;
            }
            d.vy = Math.min(d.vy + 0.045F, 1.2F);
            d.y -= d.vy;
            double hit = landing(level, d.x, d.prevY, d.y, d.z);
            if (!Double.isNaN(hit)) {
                splash(d.x, hit, d.z, d.size);
                it.remove();
            } else if (d.hangY - d.y > 48.0 || player.distanceToSqr(d.x, d.y, d.z) > range2) {
                it.remove();
            }
        }
        for (Iterator<Bit> it = BITS.iterator(); it.hasNext(); ) {
            Bit b = it.next();
            b.prevX = b.x;
            b.prevY = b.y;
            b.prevZ = b.z;
            b.x += b.vx;
            b.y += b.vy;
            b.z += b.vz;
            b.vy -= 0.04F;
            if (++b.age >= b.life) {
                it.remove();
            }
        }
        if (DEV_LOG && ticks % 100 == 0 && wet > 0.0F) {
            OvergrownVegetation.LOGGER.info("AfterRain 濡れ {} 雨 {} 強さ {} 滴 {} 湯気 {} きらめき {}", wet, rain, strength, DROPS.size(),
                    STEAM.size(), GLINTS.size());
        }
        float s = strength * wet;
        if (s <= 0.01F) {
            return;
        }
        int eyeY = Mth.floor(player.getEyeY());

        // 葉先の滴(雨の最中も)。
        int drips = (int) (DRIPS_PER_TICK * s) + (RANDOM.nextFloat() < (DRIPS_PER_TICK * s) % 1.0F ? 1 : 0);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < drips; i++) {
            int bx = Mth.floor(player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * RANGE);
            int bz = Mth.floor(player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * RANGE);
            for (int y = eyeY + 1; y <= eyeY + 24; y++) {
                pos.set(bx, y, bz);
                if (!CanopyLight.isLeafLike(level, pos)) {
                    continue;
                }
                if (DROPS.size() < VisualQuality.particleCap(MAX_DROPS) && level.getBlockState(pos.setY(y - 1)).isAir()) {
                    DROPS.add(new Drop(bx + 0.15 + RANDOM.nextDouble() * 0.7, y, bz + 0.15 + RANDOM.nextDouble() * 0.7,
                            25 + RANDOM.nextInt(35), 0.03F + RANDOM.nextFloat() * 0.018F));
                }
                break;
            }
        }

        // きらめき・湯気: 昼、雨が止んだ後だけ。日の当たる地面に。
        if (rain > 0.1F) {
            return;
        }
        Vec3 sun = CanopyLight.sunDirection(level, 1.0F);
        float day = CanopyLight.daylight(level, sun, 1.0F) * CloudShadows.sunVisibility();  // 雲が太陽を隠している間は弱める(CloudShadows、2026-10-17)
        float after = s * day;
        if (after <= 0.01F) {
            return;
        }
        if (STEAM.size() < VisualQuality.particleCap(MAX_STEAM) && RANDOM.nextFloat() < STEAM_CHANCE * after) {
            double[] spot = sunnyGround(level, player, eyeY, sun, 0);
            if (spot != null) {
                STEAM.add(new Wisp(spot[0], spot[1] + 0.1, spot[2], 90 + RANDOM.nextInt(70), 1.5F + RANDOM.nextFloat() * 1.5F,
                        0.5F + RANDOM.nextFloat() * 0.5F, (RANDOM.nextFloat() - 0.5F) * 0.6F, (RANDOM.nextFloat() - 0.5F) * 0.6F));
            }
        }
        float glints = GLINT_CHANCE * after;
        for (int i = 0; i < 3 && GLINTS.size() < VisualQuality.particleCap(MAX_GLINTS) && RANDOM.nextFloat() < glints; i++, glints -= 1.0F) {
            double[] spot = sunnyGround(level, player, eyeY, sun, 2);
            if (spot != null) {
                GLINTS.add(new Glint(spot[0], spot[1] + 0.06, spot[2], 6 + RANDOM.nextInt(8), 0.035F + RANDOM.nextFloat() * 0.03F,
                        spot[3] == 0.0 ? 1.0F : 0.6F));
            }
        }
    }

    /**
     * まわりの地面から、太陽へたどって葉を {@code maxLeaves} 枚以下しか通らない所を 1 つ選ぶ(x, 地面の高さ, z, 葉の枚数)。見つからなければ null。
     */
    private static double[] sunnyGround(ClientLevel level, LocalPlayer player, int eyeY, Vec3 sun, int maxLeaves) {
        for (int tries = 0; tries < 3; tries++) {
            double x = player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * (RANGE - 2.0);
            double z = player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * (RANGE - 2.0);
            float ground = CanopyLight.groundAt(level, Mth.floor(x), Mth.floor(z), eyeY);
            if (Float.isNaN(ground) || Math.abs(ground - eyeY) > 12.0F) {
                continue;
            }
            int leaves = CanopyLight.traceSun(level, x, ground + 0.1, z, sun);
            if (leaves >= 0 && leaves <= maxLeaves) {
                return new double[] {x, ground, z, leaves};
            }
        }
        return null;
    }

    /** 高さ fromY から toY へ落ちる間に当たる地面・水面の高さ(無ければ NaN)。当たり判定の無い草・シダは抜ける。 */
    private static double landing(ClientLevel level, double x, double fromY, double toY, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        for (int by = Mth.floor(fromY); by >= Mth.floor(toY); by--) {
            pos.set(bx, by, bz);
            BlockState state = level.getBlockState(pos);
            double top = Double.NaN;
            if (!state.getFluidState().isEmpty()) {
                top = by + state.getFluidState().getHeight(level, pos);
            } else {
                VoxelShape shape = state.getCollisionShape(level, pos);
                if (!shape.isEmpty()) {
                    top = by + shape.max(Direction.Axis.Y);
                }
            }
            if (!Double.isNaN(top) && top <= fromY + 1.0E-3 && top >= toY) {
                return top;
            }
        }
        return Double.NaN;
    }

    /** 滴が当たった所に小さなしぶきを 3〜5 粒はねさせる。 */
    private static void splash(double x, double y, double z, float size) {
        int n = 3 + RANDOM.nextInt(3);
        for (int i = 0; i < n && BITS.size() < VisualQuality.particleCap(MAX_DROPS); i++) {
            float a = RANDOM.nextFloat() * Mth.TWO_PI;
            float sp = 0.03F + RANDOM.nextFloat() * 0.04F;
            BITS.add(new Bit(x, y + 0.02, z, Mth.cos(a) * sp, 0.12F + RANDOM.nextFloat() * 0.1F, Mth.sin(a) * sp,
                    6 + RANDOM.nextInt(5), size * (0.35F + RANDOM.nextFloat() * 0.2F)));
        }
    }

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、5 秒ごとにログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    // ------------------------------------------------------------------ 描く

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken || (STEAM.isEmpty() && GLINTS.isEmpty() && DROPS.isEmpty() && BITS.isEmpty())) {
            return;
        }
        boolean main = CanopyLight.drawToMainTarget();  // ファビュラスでは加算の光を粒の層でなく主画面へ(CanopyLight#drawToMainTarget)
        try {
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("雨上がりの演出の描画で例外が出たので、このセッションでは描かない", e);
        } finally {
            CanopyLight.restoreTarget(main);
        }
    }

    private static void render(RenderLevelStageEvent event) {
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        float fade = Math.min(1.0F, strength * 1.5F);
        if (fade <= 0.01F) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        MultiBufferSource.BufferSource buffers = CanopyLight.immediateBuffers();  // Iris のまとめ描きを避ける(CanopyLight#immediateBuffers、2026-10-22)
        // 湯気は白くかすむ半透明(加算の光にすると明るい地面の上で見えなかった)。きらめきは加算の光。
        VertexConsumer steam = buffers.getBuffer(steamType());
        for (Wisp w : STEAM) {
            float f = (w.age + partialTick) / w.life;
            float alpha = STEAM_ALPHA * Mth.sin(f * Mth.PI) * fade;
            float size = w.size * (0.6F + 1.2F * f);
            double y = w.y + w.rise * (1.0 - (1.0 - f) * (1.0 - f));
            CanopyLight.mote(steam, (float) (w.x + w.driftX * f - eye.x), (float) (y - eye.y), (float) (w.z + w.driftZ * f - eye.z),
                    size, left, up, alpha, 0.93F, 0.94F, 0.9F);
        }
        buffers.endBatch(steamType());
        // 滴: 半透明の水玉(縁が濃く真ん中が透ける・上がとがったしずく形)。明るさはその場所の明かり(ライトマップ)。
        ClientLevel level = mc.level;
        float daySky = level == null ? 0.0F : Mth.clamp((level.getSkyDarken(partialTick) - 0.2F) / 0.8F, 0.0F, 1.0F);
        int n = DROPS.size() + BITS.size();
        float[] hx = new float[n];
        float[] hy = new float[n];
        float[] hz = new float[n];
        float[] hs = new float[n];
        float[] ha = new float[n];
        int h = 0;
        if (level != null && n > 0) {
            VertexConsumer body = buffers.getBuffer(dropType());
            for (Drop d : DROPS) {
                boolean hanging = d.age < d.hang;
                float grow = hanging ? 0.45F + 0.55F * CanopyLight.smooth(Math.min(1.0F, (d.age + partialTick) / d.hang)) : 1.0F;
                float w = d.size * grow;
                float hh = hanging ? w * 1.3F : w * Math.min(3.2F, 1.3F + d.vy * 2.2F);
                double y = Mth.lerp(partialTick, d.prevY, d.y) - hh;
                h = bead(body, level, eye, d.x, y, d.z, w, hh, fade, left, hx, hy, hz, hs, ha, h, daySky);
            }
            for (Bit b : BITS) {
                double x = Mth.lerp(partialTick, b.prevX, b.x);
                double y = Mth.lerp(partialTick, b.prevY, b.y);
                double z = Mth.lerp(partialTick, b.prevZ, b.z);
                float a = fade * (1.0F - (b.age + partialTick) / b.life);
                h = bead(body, level, eye, x, y, z, b.size, b.size * 1.2F, a, left, hx, hy, hz, hs, ha, h, daySky);
            }
            buffers.endBatch(dropType());
        }
        VertexConsumer c = buffers.getBuffer(CanopyLight.renderType());
        for (int i = 0; i < h; i++) {
            // 滴のハイライト(光の当たった水玉の小さな照り)。
            CanopyLight.mote(c, hx[i], hy[i], hz[i], hs[i], left, up, ha[i], 1.0F, 1.0F, 1.0F);
        }
        for (Glint g : GLINTS) {
            float f = (g.age + partialTick) / g.life;
            float flash = Mth.sin(f * Mth.PI);
            flash *= flash;
            float x = (float) (g.x - eye.x);
            float y = (float) (g.y - eye.y);
            float z = (float) (g.z - eye.z);
            CanopyLight.mote(c, x, y, z, g.size * 2.2F, left, up, 0.35F * flash * g.bright * fade, 1.0F, 0.97F, 0.85F);
            CanopyLight.mote(c, x, y, z, g.size, left, up, 0.95F * flash * g.bright * fade, 1.0F, 1.0F, 1.0F);
        }
        buffers.endBatch(CanopyLight.renderType());
    }

    /** 滴の色(水の淡い青白)と、縁・真ん中の濃さ。仮決め。 */
    static final float DROP_R = 0.74F;
    static final float DROP_G = 0.86F;
    static final float DROP_B = 1.0F;
    static final float DROP_RIM = 0.62F;
    static final float DROP_CORE = 0.12F;
    private static final float[] BEAD_COS = new float[10];
    private static final float[] BEAD_SIN = new float[10];

    static {
        for (int i = 0; i < 10; i++) {
            float a = Mth.HALF_PI + i * Mth.TWO_PI / 10.0F;
            BEAD_COS[i] = Mth.cos(a);
            BEAD_SIN[i] = Mth.sin(a);
        }
    }

    /**
     * 縦に立つ水玉を 1 つ描く(中心 (x, y, z)、半分の幅 w・半分の高さ hh。上半分は細くしてしずく形)。縦の軸のまわりでカメラの方を向く。
     * ハイライト(左上の小さな照り)の位置・大きさ・濃さを配列の i 番目に入れ、次の番号を返す。
     */
    private static int bead(VertexConsumer c, ClientLevel level, Vec3 eye, double x, double y, double z, float w, float hh, float alpha,
                            Vector3f left, float[] hx, float[] hy, float[] hz, float[] hs, float[] ha, int i, float daySky) {
        float rx = (float) (x - eye.x);
        float ry = (float) (y - eye.y);
        float rz = (float) (z - eye.z);
        // 横の向き = 縦の軸とカメラへの向きの外積(真上・真下から見るときはカメラの左)。
        float sx = -rz;
        float sz = rx;
        float len = Mth.sqrt(sx * sx + sz * sz);
        if (len < 1.0E-3F) {
            sx = left.x();
            sz = left.z();
            len = Math.max(1.0E-3F, Mth.sqrt(sx * sx + sz * sz));
        }
        sx /= len;
        sz /= len;
        int light = LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));
        for (int k = 0; k < 10; k++) {
            int j = (k + 1) % 10;
            float tk = BEAD_SIN[k] > 0.0F ? 1.0F - 0.6F * BEAD_SIN[k] : 1.0F;
            float tj = BEAD_SIN[j] > 0.0F ? 1.0F - 0.6F * BEAD_SIN[j] : 1.0F;
            float ax = rx + sx * BEAD_COS[k] * w * tk;
            float ay = ry + BEAD_SIN[k] * hh;
            float az = rz + sz * BEAD_COS[k] * w * tk;
            float bx = rx + sx * BEAD_COS[j] * w * tj;
            float by = ry + BEAD_SIN[j] * hh;
            float bz = rz + sz * BEAD_COS[j] * w * tj;
            c.addVertex(rx, ry, rz).setColor(DROP_R, DROP_G, DROP_B, DROP_CORE * alpha).setLight(light);
            c.addVertex(ax, ay, az).setColor(DROP_R, DROP_G, DROP_B, DROP_RIM * alpha).setLight(light);
            c.addVertex(bx, by, bz).setColor(DROP_R, DROP_G, DROP_B, DROP_RIM * alpha).setLight(light);
            c.addVertex(rx, ry, rz).setColor(DROP_R, DROP_G, DROP_B, DROP_CORE * alpha).setLight(light);
        }
        if (i < hx.length) {
            float sky = LightTexture.sky(light) / 15.0F * daySky;
            float block = LightTexture.block(light) / 15.0F;
            float shine = Math.max(sky, block * 0.7F);
            hx[i] = rx - sx * w * 0.3F;
            hy[i] = ry - hh * 0.15F;
            hz[i] = rz - sz * w * 0.3F;
            hs[i] = w * 0.45F;
            ha[i] = 0.85F * shine * shine * alpha;
            return i + 1;
        }
        return i;
    }

    private static RenderType dropType;

    /** 滴の型: 色とライトマップの頂点(明るさがその場所の明かりに合う)・半透明・両面・深さは読むが書かない。 */
    private static RenderType dropType() {
        if (dropType == null) {
            dropType = RenderType.create(OvergrownVegetation.MODID + "_after_rain_drop", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
                    VertexFormat.Mode.QUADS, 8192, false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.POSITION_COLOR_LIGHTMAP_SHADER)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setLightmapState(RenderStateShard.LIGHTMAP)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return dropType;
    }

    /** 湯気の濃さ(一番濃いとき)。仮決め。 */
    static final float STEAM_ALPHA = 0.16F;
    private static RenderType steamType;

    /** 湯気の型: 色だけの頂点・半透明・両面・深さは読むが書かない。 */
    private static RenderType steamType() {
        if (steamType == null) {
            steamType = RenderType.create(OvergrownVegetation.MODID + "_after_rain_steam", DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS, 4096, false, true,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return steamType;
    }

    private AfterRain() {
    }
}
