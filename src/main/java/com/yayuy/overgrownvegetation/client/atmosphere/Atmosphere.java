package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * <b>過繁茂の密林の雰囲気の束ね役</b>(2026-10-30 作り直しの第 1 段)。演出のクラスはどれも自分ではイベントを受けず、ここが決まった順で呼ぶ
 * (同じ段階の受け手どうしの順は決まらないので。影 → 木漏れ日 → 光の筋 → 光る胞子 の順に重ねる必要がある)。
 *
 * <ul>
 *     <li>毎 tick: 林冠の日向の地図 → 光の筋 → 木漏れ日 → 地面の影 → 光る胞子の風の履歴。</li>
 *     <li>AFTER_SKY(地形の前): 見る向き({@link SunLight})→ 地形のシェーダーへの受け渡し({@link CanopySunMap})。</li>
 *     <li>AFTER_PARTICLES: 地面の影(シェーダーが使えないとき)→ 木漏れ日 → 光の筋 → 光る胞子。加算の光は「最高」では主画面へ({@link AddLight})。</li>
 * </ul>
 * 描くかは {@link #enabled}(クライアント設定・空のある次元・シェーダーパック使用中は設定次第)。出し入れの強さは各演出が
 * {@link ForestPresence} の度合いで決める。演出 1 つが例外を出したら、このセッションではその演出だけ止める。
 * 第 2 段(風・葉の揺れ・落ち葉)・第 3 段(霧)・第 4 段(空)もここに 1 行ずつ足す。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class Atmosphere {

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、5 秒ごとに状態をログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    private static ClientLevel level;
    private static long ticks;
    private static final Set<String> BROKEN = new HashSet<>();

    /** ワールドに入ってからの時刻(tick + 部分 tick)。風・明滅の位相に使う。 */
    public static double time(float partialTick) {
        return ticks + partialTick;
    }

    /** 雰囲気の演出を描くか。 */
    public static boolean enabled(ClientLevel level) {
        return level != null && VisualQuality.atmosphere() && level.dimensionType().hasSkyLight()
                && (!ShaderPacks.active() || ShaderPacks.ownEffects());
    }

    private static void clearAll() {
        WindGate.clear();
        FallingLeaves.clear();
        AmbientSpores.clear();
        com.yayuy.overgrownvegetation.client.atmosphere.sound.ForestWindSound.stop();
        com.yayuy.overgrownvegetation.client.atmosphere.sound.ForestWaterSound.stop();
        com.yayuy.overgrownvegetation.client.atmosphere.sound.ForestInsectSound.stop();
        CanopySunMap.clear();
        LightShafts.clear();
        CanopyDapple.clear();
        CanopyShade.clear();
        MistFog.clear();
        GiantSilhouettes.clear();
        RainDrops.clear();
        DuskMotes.clear();
        SteamWisps.clear();
        WaterSigns.clear();
        GnatSwarms.clear();
        GlowAlgae.clear();
        WaterCaustics.clear();
        UnderwaterShafts.clear();
        HollowDwellers.clear();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel now = mc.level;
        LocalPlayer player = mc.player;
        if (now != level) {
            level = now;
            ticks = 0;
            clearAll();
            WindHistory.reset();
            GustField.INSTANCE.reset();
            GustField.INSTANCE.setParams(com.yayuy.overgrownvegetation.data.DesignData.atmosphere().wind());
            Wind.use(GustField.INSTANCE);
            AirHaze.clear();
        }
        if (now == null || player == null || mc.isPaused()) {
            return;
        }
        if (!enabled(now)) {
            clearAll();
            return;
        }
        ticks++;
        guard("wind", () -> {
            GustField.INSTANCE.setPrevailing(PrevailingWindClient.angle(now), PrevailingWindClient.strength(now));
            GustField.INSTANCE.tick(time(0.0F), player.getX(), player.getZ());
        });
        guard("gate", () -> WindGate.tick(player.getX(), player.getZ(), time(0.0F)));
        guard("leaves", () -> FallingLeaves.tick(mc, now, player));
        guard("sound", () -> com.yayuy.overgrownvegetation.client.atmosphere.sound.ForestWindSound.tick(mc, now, player, time(0.0F)));
        guard("water", () -> com.yayuy.overgrownvegetation.client.atmosphere.sound.ForestWaterSound.tick(mc, now, player));
        guard("insects", () -> com.yayuy.overgrownvegetation.client.atmosphere.sound.ForestInsectSound.tick(mc, now, player));
        guard("haze", () -> AirHaze.tick(now));
        guard("mist", () -> MistFog.tick(now, mc.gameRenderer.getMainCamera().getPosition(), ticks));
        guard("sunmap", () -> CanopySunMap.tick(now, ticks));
        guard("shafts", () -> LightShafts.tick(now, player));
        guard("dapple", () -> CanopyDapple.tick(now, player));
        guard("shade", () -> CanopyShade.tick(now, player));
        guard("spores", () -> AmbientSpores.tick(now, player));
        guard("motes", () -> ShaftMotes.tick(mc.gameRenderer.getMainCamera().getPosition()));
        guard("silhouette", () -> GiantSilhouettes.tick(now, player));
        guard("drops", () -> RainDrops.tick(now, player));
        guard("dusk", () -> DuskMotes.tick(now, player));
        guard("steam", () -> SteamWisps.tick(now, player));
        guard("watersigns", () -> WaterSigns.tick(now, player));
        guard("gnats", () -> GnatSwarms.tick(now, player));
        guard("algae", () -> GlowAlgae.tick(now, player));
        guard("caustics", () -> WaterCaustics.tick(now, ticks));
        guard("uwshafts", () -> UnderwaterShafts.tick(now));
        if (DEV_LOG && ticks % 100 == 0) {
            OvergrownVegetation.LOGGER.info("Atmosphere {}", MistFog.describe());
            OvergrownVegetation.LOGGER.info("Atmosphere 小物 {} 滴 {}", DuskMotes.describe(player.getX(), player.getZ()), RainDrops.count());
            OvergrownVegetation.LOGGER.info("Atmosphere 見え方 {} / {} / {} / {}", SteamWisps.describe(), WaterSigns.describe(), GnatSwarms.describe(), GlowAlgae.describe());
            OvergrownVegetation.LOGGER.info("Atmosphere 水中の光 {} / {}", WaterCaustics.describe(), UnderwaterShafts.describe());
            BlockPos feet = player.blockPosition();
            OvergrownVegetation.LOGGER.info("Atmosphere 森 {} 地下 {} 持ち上げ {} 霞み {} 足元の光 空 {} ブロック {} 筋 {} 木漏れ日 {} 影の地図 {}",
                    String.format("%.2f", ForestPresence.forestFactor()), String.format("%.2f", ForestPresence.underground()),
                    String.format("%.2f", CanopyLift.skyLift()), String.format("%.2f", AirHaze.haze()) + " 胞子 " + Math.round(AirHaze.sporePercent()) + "%", now.getBrightness(LightLayer.SKY, feet), now.getBrightness(LightLayer.BLOCK, feet),
                    LightShafts.count(), CanopyDapple.count(), CanopySunMap.active() + " " + GustField.INSTANCE.describe(player.getX(), player.getZ()));
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lvl = mc.level;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            boolean on = lvl != null && lvl == level && enabled(lvl);
            SunLight.update(event);
            guard("sunmap", () -> CanopySunMap.upload(event, on));
            guard("sway", () -> LeafSway.upload(event, on));
            guard("tones", () -> LeafTones.upload(on));
            guard("wet", () -> WetSheen.upload(on));
            guard("march", () -> MistMarch.prepare(on && event.getCamera().getFluidInCamera() == FogType.NONE));
            guard("mist", () -> MistFog.upload(event, on));
            guard("caustics", () -> WaterCaustics.upload(event, on));
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            // 地形を描いた後の霧の値で、バニラの霧が効いているかを見る(Distant Horizons は霧を切ると値を巨大にする、{@link MistView#vanillaTerrainFog})。
            MistView.noteTerrainFog();
            // 霧の向こうの巨大樹のシルエット({@link GiantSilhouettes})。奥行きを書くので霧の細部(AFTER_WEATHER)より前。
            if (lvl != null && lvl == level && enabled(lvl) && !hidden(mc, event.getCamera())) {
                guard("silhouette", () -> GiantSilhouettes.render(event));
            }
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            // 夜の光る藻({@link GlowAlgae})。水の半透明より先に描き、水面越しに透かす。潜っている間も描く(10-31 夜: 水の中で消えていた)。
            if (lvl != null && lvl == level && enabled(lvl) && !blinded(mc, event.getCamera())) {
                guard("algae", () -> {
                    LifeSprites.begin(event, true);
                    try {
                        GlowAlgae.render(lvl, event.getPartialTick().getGameTimeDeltaPartialTick(false), event.getCamera().getLeftVector(),
                                event.getCamera().getUpVector());
                    } finally {
                        LifeSprites.end();
                    }
                });
            }
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            // 霧の細部(品質 HIGH、{@link MistMarch})。水の中・盲目の間は描かない。
            Camera cam = event.getCamera();
            if (lvl != null && lvl == level && enabled(lvl) && cam.getFluidInCamera() == FogType.NONE && !(mc.player != null
                    && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS)))) {
                guard("march", () -> MistMarch.render(event));
            }
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || lvl == null || lvl != level || !enabled(lvl)) {
            return;
        }
        Camera camera = event.getCamera();
        // 潜っている間は水中の光の筋({@link UnderwaterShafts})だけ。
        if (camera.getFluidInCamera() == FogType.WATER) {
            if (!blinded(mc, camera)) {
                guard("uwshafts", () -> UnderwaterShafts.render(event));
            }
            return;
        }
        if (camera.getFluidInCamera() != FogType.NONE || mc.player != null
                && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        if (!CanopySunMap.active()) {
            guard("shade", () -> CanopyShade.render(event));
        }
        guard("spores", () -> AmbientSpores.render(event));
        guard("life", () -> renderLife(event));
        if (HollowDwellers.hasEyes()) {
            // 木の洞の目(43)。足し算で光らせる。
            guard("hollow", () -> {
                LifeSprites.begin(event, true);
                try {
                    HollowDwellers.renderGlow(event.getCamera().getLeftVector(), event.getCamera().getUpVector());
                } finally {
                    LifeSprites.end();
                }
            });
        }
        boolean main = AddLight.drawToMainTarget();
        try {
            guard("dapple", () -> CanopyDapple.render(event));
            guard("shafts", () -> LightShafts.render(event));
            guard("motes", () -> ShaftMotes.render(event));
            guard("dusk", () -> DuskMotes.render(event));
        } finally {
            AddLight.restoreTarget(main);
        }
    }

    /** 見え方の段(10-31)の小さな形: 湯気・水面の輪と泡・羽虫({@link LifeSprites} の 1 回の描画)。幹を伝う水は 2026-11-01 に parked へ退避(U12)。 */
    private static void renderLife(RenderLevelStageEvent event) {
        ClientLevel lvl = Minecraft.getInstance().level;
        if (lvl == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        org.joml.Vector3f left = event.getCamera().getLeftVector();
        org.joml.Vector3f up = event.getCamera().getUpVector();
        LifeSprites.begin(event);
        try {
            SteamWisps.render(lvl, partialTick, left, up);
            WaterSigns.render(lvl, partialTick, left, up);
            GnatSwarms.render(partialTick, left, up);
            HollowDwellers.render(left, up); // 木の洞から飛び出す虫(43)
        } finally {
            LifeSprites.end();
        }
    }

    /** 霧の色(朝霧の中は霧の遠い色へ、{@link MistView})。 */
    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        ClientLevel lvl = Minecraft.getInstance().level;
        if (lvl != null && lvl == level && enabled(lvl)) {
            guard("mistview", () -> MistView.computeFogColor(event));
        }
    }

    /** 霧の距離(朝霧の中は 64 ブロック、{@link MistView})。 */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        ClientLevel lvl = Minecraft.getInstance().level;
        if (lvl != null && lvl == level && enabled(lvl)) {
            guard("mistview", () -> MistView.renderFog(event));
        }
    }

    /** 水の中・溶岩の中・盲目・暗闇の間は描かない。 */
    /** 水の中では描く物(光る藻・水中の光の筋)の止め: 溶岩の中・粉雪の中・盲目・暗闇。 */
    private static boolean blinded(Minecraft mc, Camera cam) {
        FogType fluid = cam.getFluidInCamera();
        return fluid == FogType.LAVA || fluid == FogType.POWDER_SNOW || mc.player != null
                && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS));
    }

    private static boolean hidden(Minecraft mc, Camera cam) {
        return cam.getFluidInCamera() != FogType.NONE || mc.player != null
                && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS));
    }

    /** 演出 {@code name} を 1 つ動かす。例外が出たらこのセッションではその演出を止める。 */
    private static void guard(String name, Runnable run) {
        if (BROKEN.contains(name)) {
            return;
        }
        try {
            run.run();
        } catch (RuntimeException e) {
            BROKEN.add(name);
            OvergrownVegetation.LOGGER.error("雰囲気の演出 {} で例外が出たので、このセッションでは止める", name, e);
        }
    }

    private Atmosphere() {
    }
}
