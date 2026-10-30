package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.effect.FernRustle;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * シダの胞子(2026-10-16 ユーザー指定「シダの茂みを歩いて抜けると胞子がばら撒かれる」「足跡を辿る」)。
 *
 * <p>シダの茂み({@link com.yayuy.overgrownvegetation.ModTags#SPORE_PUFF_PLANTS})の中を動くもの(プレイヤー・Mob)の足元から、
 * 光る胞子がふわっと舞い上がる({@link #PUFF_TICKS})。そのうち何粒かは梢の高さに漂ったまま {@link #TRAIL_TICKS} かけて薄れていくので、
 * <b>Mob が通った所にうっすら光る筋が残る</b>(足跡をたどれる)。しゃがんだプレイヤーは吹かない(忍び足。走ると Mob に気付かれやすいのは
 * サーバーの {@link FernRustle})。
 *
 * <p>粒は 1 つずつ動きを覚えず、印(場所・生まれた時刻・乱数の種)から毎フレーム計算して描く(パーティクルを使わない —— 数が多いと
 * 重いのでユーザーが気にしていた)。印は {@link #MAX_MARKS} 個まで。クライアントが追っていない遠くの Mob は跡を残さない。
 * 数値は全部 Claude の仮決め。
 *
 * <p>2026-10-23 ユーザー指定: 吹くのは<b>プレイヤーが巻きシダ(overgrown_curled_fern)に触れたときだけ</b>(Mob では吹かない・ほかの下草でも吹かない)。
 * 色は萌芽体の噴き出し({@link SporeJetParticle})・細かい胞子と同じ緑に揃えた(前は淡い青緑の光)。上の Mob の足跡の話はそれより前のもの。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class FernSporePuffs {

    /** 印の数の上限・舞い上がる時間・跡が残る時間(tick)。 */
    static final int MAX_MARKS = 600;
    static final int PUFF_TICKS = 40;
    static final int TRAIL_TICKS = 600;
    /** 1 つの印の粒の数と、そのうち跡として残る数。 */
    static final int MOTES = 6;
    static final int TRAIL_MOTES = 3;
    /** 吹く間隔(tick): 歩き・走り。 */
    static final int WALK_EVERY = 5;
    static final int SPRINT_EVERY = 3;
    /** 見る範囲(ブロック)。 */
    static final double RANGE = 40.0;
    /** 色(萌芽体の噴き出し {@link SporeJetParticle}・細かい胞子と同じ緑。2026-10-23 ユーザー指定で青緑から揃えた)。 */
    static final float R = 0.34F;
    static final float G = 0.72F;
    static final float B = 0.27F;

    private record Mark(double x, double y, double z, long born, long seed, float strength) {
    }

    private static final ArrayDeque<Mark> MARKS = new ArrayDeque<>();
    private static final Int2LongOpenHashMap NEXT = new Int2LongOpenHashMap();
    private static final Random RANDOM = new Random();
    private static ClientLevel markLevel;
    private static long ticks;
    private static boolean broken;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != markLevel) {
            MARKS.clear();
            NEXT.clear();
            markLevel = level;
        }
        if (level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        ticks++;
        while (!MARKS.isEmpty() && ticks - MARKS.peekFirst().born() > TRAIL_TICKS) {
            MARKS.removeFirst();
        }
        if (NEXT.size() > 4096) {
            NEXT.clear();
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        // プレイヤーだけ(2026-10-23 ユーザー指定。Mob では吹かない)。
        for (Player living : level.players()) {
            Player e = living;
            if (e.isSpectator() || !e.isAlive()) {
                continue;
            }
            double mx = e.getX() - e.xo;
            double mz = e.getZ() - e.zo;
            double speed2 = mx * mx + mz * mz;
            // 毎秒 0.8 ブロックより遅い動き(立ち止まり・押されただけ)では吹かない。
            if (speed2 < 0.0016 || e.distanceToSqr(eye) > RANGE * RANGE) {
                continue;
            }
            if (living.isCrouching()) {
                continue;
            }
            if (!inCurledFern(level, living)) {
                continue;
            }
            int id = e.getId();
            if (ticks < NEXT.getOrDefault(id, 0L)) {
                continue;
            }
            boolean fast = living.isSprinting() || speed2 > 0.04;
            NEXT.put(id, ticks + (fast ? SPRINT_EVERY : WALK_EVERY));
            float strength = fast ? 1.0F : 0.75F;
            strength *= Mth.clamp(e.getBbWidth() / 0.6F, 0.8F, 1.6F);
            MARKS.addLast(new Mark(e.getX() + (RANDOM.nextDouble() - 0.5) * 0.4, e.getY() + 0.15,
                    e.getZ() + (RANDOM.nextDouble() - 0.5) * 0.4, ticks, RANDOM.nextLong(), Math.min(1.4F, strength)));
            if (MARKS.size() > MAX_MARKS) {
                MARKS.removeFirst();
            }
        }
    }

    /** 足元か、その 1 つ上(背の高いプレイヤーの膝)が巻きシダか。 */
    private static boolean inCurledFern(ClientLevel level, Player player) {
        BlockPos feet = player.blockPosition();
        return level.getBlockState(feet).is(ModBlocks.OVERGROWN_CURLED_FERN.get())
                || level.getBlockState(feet.above()).is(ModBlocks.OVERGROWN_CURLED_FERN.get());
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken || MARKS.isEmpty()) {
            return;
        }
        boolean main = CanopyLight.drawToMainTarget();  // ファビュラスでは加算の光を粒の層でなく主画面へ(CanopyLight#drawToMainTarget)
        try {
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("シダの胞子の描画で例外が出たので、このセッションでは描かない", e);
        } finally {
            CanopyLight.restoreTarget(main);
        }
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        Vec3 eye = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double now = ticks + partialTick;
        double windX = CanopyWind.dirX();
        double windZ = CanopyWind.dirZ();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        MultiBufferSource.BufferSource buffers = CanopyLight.immediateBuffers();  // Iris のまとめ描きを避ける(CanopyLight#immediateBuffers、2026-10-22)
        VertexConsumer c = buffers.getBuffer(CanopyLight.renderType());
        Random random = new Random();
        for (Mark m : MARKS) {
            double hx = m.x() - eye.x;
            double hz = m.z() - eye.z;
            double dist2 = hx * hx + hz * hz;
            if (dist2 > RANGE * RANGE) {
                continue;
            }
            float fade = (float) Math.min(1.0, (RANGE - Math.sqrt(dist2)) / 8.0);
            double age = now - m.born();
            random.setSeed(m.seed());
            // 風下へゆっくり流れる(30 秒で 1.5 ブロックほど)。
            double drift = age / 20.0 * 0.05;
            for (int i = 0; i < MOTES; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double radius = 0.25 + random.nextDouble() * 0.45;
                double rise = 0.5 + random.nextDouble() * 0.9;
                float size = 0.04F + random.nextFloat() * 0.045F;
                double phase = random.nextDouble() * Math.PI * 2.0;
                // 舞い上がり: 速く出て、だんだん止まる。明るく出て薄れる。
                double t = Math.min(1.0, age / PUFF_TICKS);
                double ease = 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t);
                float burst = age < PUFF_TICKS ? (float) Math.pow(1.0 - t, 1.5) * 0.9F : 0.0F;
                // 跡: 何粒かは梢の高さに漂い、ゆっくり揺れながら薄れる。
                float trail = 0.0F;
                if (i < TRAIL_MOTES && age > PUFF_TICKS * 0.5) {
                    double into = Mth.clamp((age - PUFF_TICKS * 0.5) / (PUFF_TICKS * 0.5), 0.0, 1.0);
                    double left01 = Mth.clamp(1.0 - (age - PUFF_TICKS) / (double) (TRAIL_TICKS - PUFF_TICKS), 0.0, 1.0);
                    trail = (float) (0.35 * into * Math.pow(left01, 1.2));
                }
                float alpha = Math.max(burst, trail) * m.strength() * fade;
                if (alpha <= 0.01F) {
                    continue;
                }
                double settle = age < PUFF_TICKS ? ease : 1.0 - 0.3 * Math.min(1.0, (age - PUFF_TICKS) / 200.0);
                double bob = 0.08 * Math.sin(age * 0.05 + phase);
                double x = hx + Math.cos(angle) * radius * (0.3 + 0.7 * ease) + windX * drift;
                double y = m.y() - eye.y + rise * settle + (age > PUFF_TICKS ? bob : 0.0);
                double z = hz + Math.sin(angle) * radius * (0.3 + 0.7 * ease) + windZ * drift;
                CanopyLight.mote(c, (float) x, (float) y, (float) z, size, left, up, Math.min(1.0F, alpha), R, G, B);
            }
        }
        buffers.endBatch(CanopyLight.renderType());
    }

    private FernSporePuffs() {
    }
}
