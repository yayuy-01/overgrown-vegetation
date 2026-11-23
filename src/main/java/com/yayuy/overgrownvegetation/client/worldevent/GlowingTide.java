package com.yayuy.overgrownvegetation.client.worldevent;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.SunLight;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.WorldEventLooks;
import com.yayuy.overgrownvegetation.worldevent.WorldEventKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ClientTickEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>光る潮</b>(2026-11-16。ユーザー決定: 夜の海・川・湖が青緑に光り、波打ち際と泳いだ跡に光が走る。F3 から。効き目なし)。
 * 理由(設定): 胞子を食べた微生物が、水が動くと光る —— 汚染が水へ入った印。
 *
 * <p>水面のシェーダー({@code shaders/include/ov_water.glsl} の {@code ovWaterTide}。バニラの半透明と Sodium の地形の両方)へ値を流すだけ:
 * 静かな水面の淡い光(ゆっくり流れるむら)・1 画素ずつ灯って消える火花・岸へ寄せてくる光の波(岸までの距離は水深の地図 {@code WaterDepth} のもの)・
 * 何かが水を動かした所の光(自分と乗り物の通った跡 4 か所 + 近くで泳いでいる生き物 2 体)。水のほかの絵と同じく 1/16 ブロックの画素ごとに平らな段。
 * 夜の度合いを掛ける(夕方から少しずつ光り出す)。地下の水には出さない。値は {@code worldEvents.tide}(仮決め)。
 * 確かめは {@code /ov event start glowing_tide <進み>}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class GlowingTide {

    private GlowingTide() {
    }

    /** 通った跡の 1 点(世界の x, z と、置いた時刻)。 */
    private record Stamp(double x, double z, long tick) {
    }

    private static final int TRAIL = 4;
    private static final List<Stamp> STAMPS = new ArrayList<>();
    private static final double[][] OTHERS = new double[2][3];
    private static long ticks;
    private static float lastShown;

    /** 光る潮の出ている度合い(0〜1。夜の度合い込み)。 */
    public static float amount(float partialTick) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !WorldEventClient.is(WorldEventKind.GLOWING_TIDE)) {
            return 0.0F;
        }
        return WorldEventSky.envelope(WorldEventClient.elapsedTicks(partialTick), WorldEventClient.lengthTicks(), 600.0F, 600.0F)
                * SunLight.night(level, partialTick);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            STAMPS.clear();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        ticks++;
        WorldEventLooks.Tide p = DesignData.sky().events.tide;
        long life = Math.max(20L, (long) (p.wakeSeconds * 20.0F));
        STAMPS.removeIf(s -> ticks - s.tick > life);
        OTHERS[0][2] = 0.0;
        OTHERS[1][2] = 0.0;
        if (amount(0.0F) <= 0.0F) {
            return;
        }
        // 自分(乗り物に乗っていれば乗り物): 水の中を動いたら、1.2 ブロックごとに跡を置く。
        Entity self = mc.player.getVehicle() != null ? mc.player.getVehicle() : mc.player;
        if (wet(level, self)) {
            Stamp last = STAMPS.isEmpty() ? null : STAMPS.get(STAMPS.size() - 1);
            if (last == null || sq(last.x - self.getX(), last.z - self.getZ()) > 1.44) {
                STAMPS.add(new Stamp(self.getX(), self.getZ(), ticks));
                if (STAMPS.size() > TRAIL) {
                    STAMPS.remove(0);
                }
            }
        }
        // 近くで泳いでいる生き物: 近い順に 2 体(跡は残さず、今いる所だけ光る)。
        if (ticks % 2 == 0) {
            Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
            double best0 = 40.0 * 40.0, best1 = 40.0 * 40.0;
            for (Entity e : level.entitiesForRendering()) {
                if (e == self || e == mc.player || !wet(level, e) || !moved(e)) {
                    continue;
                }
                double d = sq(e.getX() - cam.x, e.getZ() - cam.z);
                if (d < best0) {
                    best1 = best0;
                    OTHERS[1][0] = OTHERS[0][0];
                    OTHERS[1][1] = OTHERS[0][1];
                    OTHERS[1][2] = OTHERS[0][2];
                    best0 = d;
                    OTHERS[0][0] = e.getX();
                    OTHERS[0][1] = e.getZ();
                    OTHERS[0][2] = 1.0;
                } else if (d < best1) {
                    best1 = d;
                    OTHERS[1][0] = e.getX();
                    OTHERS[1][1] = e.getZ();
                    OTHERS[1][2] = 1.0;
                }
            }
        }
    }

    /** 水に浸かっているか(観戦モードは isInWater が立たないので、足元の水でも見る)。 */
    private static boolean wet(ClientLevel level, Entity e) {
        return e.isInWater() || level.getFluidState(net.minecraft.core.BlockPos.containing(e.getX(), e.getY() + 0.2, e.getZ())).is(net.minecraft.tags.FluidTags.WATER);
    }

    private static boolean moved(Entity e) {
        return sq(e.getX() - e.xOld, e.getZ() - e.zOld) > 0.0004 || Math.abs(e.getY() - e.yOld) > 0.02;
    }

    private static double sq(double x, double z) {
        return x * x + z * z;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        STAMPS.clear();
    }

    /** 毎フレーム(空を描いた後 = 地形の前): 水面のシェーダーへ値を入れる(Sodium はバニラのシェーダーから写す)。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        ShaderInstance shader = GameRenderer.getRendertypeTranslucentShader();
        if (shader == null) {
            return;
        }
        float partial = event.getPartialTick();
        WorldEventLooks.Tide p = DesignData.sky().events.tide;
        float amount = ShaderPacks.active() || !com.yayuy.overgrownvegetation.client.VisualQuality.effects() ? 0.0F : amount(partial) * p.strength;
        lastShown = amount;
        if (amount <= 0.001F) {
            shader.safeGetUniform("OvTide").set(0.0F, 0.0F, 0.0F, 0.0F);
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        Vec3 cam = event.getCamera().getPosition();
        float time = level == null ? 0.0F : (float) ((level.getGameTime() % 12000L + partial) / 20.0);
        shader.safeGetUniform("OvTide").set(WorldEventSky.rgb(p.color, 0), WorldEventSky.rgb(p.color, 1), WorldEventSky.rgb(p.color, 2), amount);
        shader.safeGetUniform("OvTideB").set(p.shore, p.sparks, time, p.wake);
        float life = Math.max(20.0F, p.wakeSeconds * 20.0F);
        for (int i = 0; i < TRAIL; i++) {
            if (i < STAMPS.size()) {
                Stamp s = STAMPS.get(i);
                float age = (ticks - s.tick + partial) / life;
                // 跡は少しずつ広がって消える。
                shader.safeGetUniform("OvTideP" + i).set((float) (s.x - cam.x), (float) (s.z - cam.z), p.wakeRadius * (1.0F + 0.8F * age),
                        Math.max(0.0F, 1.0F - age));
            } else {
                shader.safeGetUniform("OvTideP" + i).set(0.0F, 0.0F, 1.0F, 0.0F);
            }
        }
        for (int i = 0; i < 2; i++) {
            shader.safeGetUniform("OvTideP" + (TRAIL + i)).set((float) (OTHERS[i][0] - cam.x), (float) (OTHERS[i][1] - cam.z), p.wakeRadius * 0.9F,
                    (float) OTHERS[i][2] * 0.8F);
        }
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        return String.format("光る潮: %.2f・跡 %d", lastShown, STAMPS.size());
    }
}
