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
        com.yayuy.overgrownvegetation.client.atmosphere.sound.CaveSound.stop();
        com.yayuy.overgrownvegetation.client.atmosphere.sound.UnderwaterSound.stop();
        CanopySunMap.clear();
        LightShafts.clear();
        CaveShafts.clear();
        CaveBreath.clear();
        CanopyDapple.clear();
        CanopyShade.clear();
        MistFog.clear();
        GiantSilhouettes.clear();
        RainDrops.clear();
        DuskMotes.clear();
        SteamWisps.clear();
        TrunkRivulets.clear();
        WaterSigns.clear();
        GnatSwarms.clear();
        GlowAlgae.clear();
        WaterCaustics.clear();
        WaterDepth.clear(); // 浅瀬と深みの色・岸の泡(11-12 水まわり ②)
        UnderwaterCritters.clear(); // 水中のにぎわい(11-13 水まわり ⑥)
        WaterMotes.clear(); // 水中の浮遊物(11-13 水まわり ⑥ の続き)
        FilmWakes.clear(); // 水面の膜をかき分けた筋(11-13)
        UnderwaterShafts.clear();
        HollowDwellers.clear();
        CanopyGnats.clear(); // 巨大樹の生き物と音(11-06 改善案 ⑤)
        TrunkLizards.clear();
        CaveGlowworms.clear(); // 洞窟の小ネタ(11-07)
        CaveCrickets.clear();
        com.yayuy.overgrownvegetation.client.atmosphere.sound.GiantTreeCreakSound.stop();
        CaveLights.clear();
        EyeAdaptation.clear();
        SporePools.clear();
        CloudShadows.clear();
        CrepuscularRays.clear();
        SporeGale.clear(); // G4 胞子の風・G5 光る胞子の夜(11-08)
        GlowNight.clear();
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
        // G4 胞子の風(11-08): どの突風を胞子の風にするか・霧・前触れ。突風の場を進めた後に。
        guard("gale", () -> SporeGale.tick(now, player));
        guard("glownight", () -> GlowNight.tick(now, player)); // G5 光る胞子の夜(11-08)。雲の影の地図より前に
        guard("gate", () -> WindGate.tick(player.getX(), player.getZ(), time(0.0F)));
        guard("leaves", () -> FallingLeaves.tick(mc, now, player));
        guard("sound", () -> com.yayuy.overgrownvegetation.client.atmosphere.sound.ForestWindSound.tick(mc, now, player, time(0.0F)));
        guard("water", () -> com.yayuy.overgrownvegetation.client.atmosphere.sound.ForestWaterSound.tick(mc, now, player));
        guard("insects", () -> com.yayuy.overgrownvegetation.client.atmosphere.sound.ForestInsectSound.tick(mc, now, player));
        guard("cavesound", () -> com.yayuy.overgrownvegetation.client.atmosphere.sound.CaveSound.tick(mc, now, player));
        guard("uwsound", () -> com.yayuy.overgrownvegetation.client.atmosphere.sound.UnderwaterSound.tick(mc, now, player));
        guard("haze", () -> AirHaze.tick(now));
        guard("mist", () -> MistFog.tick(now, mc.gameRenderer.getMainCamera().getPosition(), ticks));
        guard("sunmap", () -> CanopySunMap.tick(now, ticks));
        guard("shafts", () -> LightShafts.tick(now, player));
        guard("caveshafts", () -> CaveShafts.tick(now, player)); // 洞窟の天井の穴の光の筋(11-07)
        guard("cavebreath", () -> CaveBreath.tick(now, player)); // 洞窟の呼吸(11-08 境目の帯 1)
        guard("dapple", () -> CanopyDapple.tick(now, player));
        guard("shade", () -> CanopyShade.tick(now, player));
        guard("spores", () -> AmbientSpores.tick(now, player));
        guard("motes", () -> ShaftMotes.tick(mc.gameRenderer.getMainCamera().getPosition()));
        guard("silhouette", () -> GiantSilhouettes.tick(now, player));
        guard("sporecolumns", () -> SporeColumns.tick(now)); // 巨大樹の胞子の柱の雨上がりの判定(11-06 改善案 ①)
        guard("drops", () -> RainDrops.tick(now, player));
        guard("dusk", () -> DuskMotes.tick(now, player));
        guard("steam", () -> SteamWisps.tick(now, player));
        guard("rivulets", () -> TrunkRivulets.tick(now, player)); // 幹を伝う水(41、11-01 に作り直して戻した)
        guard("canopydrips", () -> CanopyDrips.tick(now, player)); // 巨大樹の傘からの雨だれ(11-06 夜)
        guard("watersigns", () -> WaterSigns.tick(now, player));
        guard("gnats", () -> GnatSwarms.tick(now, player));
        // 巨大樹のまわりの生き物と音(11-06 改善案 ⑤): 傘の羽虫・幹のトカゲ・突風の軋み
        guard("canopygnats", () -> CanopyGnats.tick(now, player));
        guard("lizards", () -> TrunkLizards.tick(now, player));
        // 洞窟の小ネタ(11-07): 天井の土ボタル・洞窟の口のカマドウマ
        guard("glowworms", () -> CaveGlowworms.tick(now, player));
        guard("crickets", () -> CaveCrickets.tick(now, player));
        guard("giantcreak", () -> com.yayuy.overgrownvegetation.client.atmosphere.sound.GiantTreeCreakSound.tick(mc, now, player, time(0.0F)));
        guard("algae", () -> GlowAlgae.tick(now, player));
        guard("caustics", () -> WaterCaustics.tick(now, ticks));
        guard("waterdepth", () -> WaterDepth.tick(now, ticks)); // 浅瀬と深みの色・岸の泡(11-12 水まわり ②)
        guard("uwshafts", () -> UnderwaterShafts.tick(now));
        guard("current", () -> WaterCurrent.tick(now)); // 水の中の共通の流れ(11-13 水まわり ⑥ の直し)
        guard("watermotes", () -> WaterMotes.tick(now)); // 水中の浮遊物(11-13。バニラの水中の粒の置き換え)
        guard("floatleaves", () -> FloatingLeaves.tick(now)); // 水面に浮く落ち葉(11-13 密林の水辺 ②。流木は同日に消した)
        guard("filmwakes", () -> FilmWakes.tick(now)); // 水面の膜をかき分けた筋(11-13)
        guard("uwmotes", () -> UnderwaterMotes.tick(now)); // 水中を漂う粒を足す(11-13 水まわり ⑤)
        guard("uwcritters", () -> UnderwaterCritters.tick(now)); // 沈む葉・水生の虫・泥煙(11-13 水まわり ⑥)
        guard("cave", () -> CaveLights.tick(now, mc.gameRenderer.getMainCamera().getPosition(), ticks));
        guard("eyeadapt", () -> EyeAdaptation.tick(now, mc.gameRenderer.getMainCamera().getPosition()));
        guard("sporepools", () -> SporePools.tick(now, player, ticks)); // 洞窟の胞子の溜まり(地下の植生 2・U6)
        guard("cloudshadow", () -> CloudShadows.tick(now, mc.gameRenderer.getMainCamera().getPosition())); // 雲の影・薄明光線(11-08 雲の段階 1)
        guard("rays", () -> CrepuscularRays.tick(now));
        if (DEV_LOG && ticks % 100 == 0) {
            OvergrownVegetation.LOGGER.info("Atmosphere {}", MistFog.describe());
            OvergrownVegetation.LOGGER.info("Atmosphere 小物 {} 滴 {}", DuskMotes.describe(player.getX(), player.getZ()), RainDrops.count());
            OvergrownVegetation.LOGGER.info("Atmosphere 見え方 {} / {} / {} / {} / {}", SteamWisps.describe(), WaterSigns.describe(), GnatSwarms.describe(), GlowAlgae.describe(), TrunkRivulets.describe());
            OvergrownVegetation.LOGGER.info("Atmosphere 巨大樹の生き物 {} / {}", CanopyGnats.describe(), TrunkLizards.describe());
            OvergrownVegetation.LOGGER.info("Atmosphere 洞窟の小ネタ {} / {}", CaveGlowworms.describe(), CaveCrickets.describe());
            OvergrownVegetation.LOGGER.info("Atmosphere 水中の光 {} / {} / {} / {} / {}", WaterCaustics.describe(), UnderwaterShafts.describe(), UnderwaterCritters.describe(), WaterMotes.describe(), FloatingLeaves.describe() + " " + FilmWakes.describe());
            OvergrownVegetation.LOGGER.info("Atmosphere {}", CaveLights.describe());
            OvergrownVegetation.LOGGER.info("Atmosphere G4・G5 {} / {} / {}", SporeGale.describe(), GlowNight.describe(now), CloudShadows.describe());
            BlockPos feet = player.blockPosition();
            OvergrownVegetation.LOGGER.info("Atmosphere 覆い {} 地下 {} 持ち上げ {} 霞み {} 足元の光 空 {} ブロック {} 筋 {} 木漏れ日 {} 影の地図 {}",
                    String.format("%.2f", ForestPresence.cover()) + " 過繁茂 " + String.format("%.2f", ForestPresence.overgrown()), String.format("%.2f", ForestPresence.underground()),
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
            guard("glint", () -> WaterGlint.upload(on));
            guard("waterdepth", () -> WaterDepth.upload(on));
            guard("filmwakes", () -> FilmWakes.upload(on)); // 水面の膜をかき分けた筋(11-13)
            guard("rainlook", () -> RainLook.upload(on));
            guard("march", () -> MistMarch.prepare(on && event.getCamera().getFluidInCamera() == FogType.NONE));
            guard("mist", () -> MistFog.upload(event, on));
            guard("caustics", () -> WaterCaustics.upload(event, on));
            guard("cave", () -> CaveLights.upload(event, on));
            guard("cloudshadow", () -> CloudShadows.upload(event, on)); // 流れる雲の影(11-08 雲の段階 1)
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
                guard("watermotes", () -> WaterMotes.render(event)); // 水中の浮遊物(11-13)
                // 沈む葉・水生の虫の群れ(11-13 水まわり ⑥)は絵の付いた粒(UnderwaterLifeParticles)なので、ここでは描かない。
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
        guard("rivulets", () -> TrunkRivulets.render(event));
        guard("canopydrips", () -> CanopyDrips.render(event));
        if (CaveGlowworms.any()) {
            // 洞窟の天井の土ボタル(11-07)。足し算・霧で薄めない(地下の黒い霧の中でも沈めない)。
            guard("glowworms", () -> {
                LifeSprites.beginGlowNoFog(event);
                try {
                    net.minecraft.world.phys.Vec3 c = event.getCamera().getPosition();
                    CaveGlowworms.render(event.getPartialTick().getGameTimeDeltaPartialTick(false), c.x, c.y, c.z, event.getCamera().getLeftVector(),
                            event.getCamera().getUpVector());
                } finally {
                    LifeSprites.end();
                }
            });
        }
        if (CanopyGnats.glowing()) {
            // 夜の傘の光る虫(11-06 ⑤ の案。nightGlow が 0 なら出ない)。足し算で光らせる。
            guard("canopyglow", () -> {
                LifeSprites.begin(event, true);
                try {
                    CanopyGnats.renderGlow(event.getPartialTick().getGameTimeDeltaPartialTick(false), event.getCamera().getPosition(),
                            event.getCamera().getLeftVector(), event.getCamera().getUpVector());
                } finally {
                    LifeSprites.end();
                }
            });
        }
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
            guard("rays", () -> CrepuscularRays.render(event)); // 薄明光線(11-08 雲の段階 1)
            // G4 光線の中で光る胞子の波・G5 降る光る胞子(11-08)。薄明光線(月の柱)を描いた後に、その位置で光らせる。
            guard("galeglow", () -> renderGlowEvents(event));
            guard("caveshafts", () -> CaveShafts.render(event));
            guard("strikes", () -> StormStrikes.render(event)); // 積乱雲からの落雷(11-16 雷雨の強化)
            guard("fogsun", () -> DenseFog.renderSun(event)); // 濃霧の中のぼやけた太陽(11-16)
            guard("motes", () -> ShaftMotes.render(event));
            guard("dusk", () -> DuskMotes.render(event));
            guard("sporecolumns", () -> SporeColumns.render(event)); // 巨大樹の胞子の柱(11-06 改善案 ①)
            guard("leypulses", () -> LeyPulses.render(event)); // 夜に幹を昇る地脈の光(11-06 改善案 ③)
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
            CaveBreath.render(lvl, partialTick, left, up, event.getCamera().getPosition().x, event.getCamera().getPosition().y, event.getCamera().getPosition().z); // 洞窟の呼吸(11-08 境目の帯 1)
            WaterSigns.render(lvl, partialTick, left, up);
            GnatSwarms.render(partialTick, left, up);
            CanopyGnats.render(partialTick, event.getCamera().getPosition(), left, up); // 巨大樹の傘の羽虫(11-06 ⑤)
            TrunkLizards.render(lvl, partialTick); // 幹のトカゲ(11-06 ⑤)
            CaveCrickets.render(lvl, partialTick); // 洞窟の口のカマドウマ(11-07)
            HollowDwellers.render(left, up); // 木の洞から飛び出す虫(43)
            net.minecraft.world.phys.Vec3 cam = event.getCamera().getPosition();
            SporePools.render(lvl, partialTick, left, up, cam.x, cam.y, cam.z); // 洞窟の胞子の溜まり(地下の植生 2・U6)
            SporeGale.render(lvl, partialTick, cam, left, up); // G4 胞子の風の黄緑の波(11-08)
        } finally {
            LifeSprites.end();
        }
    }

    /** G4 胞子の風の光る粒・G5 光る胞子の夜の粒(足し算の光。11-08)。 */
    private static void renderGlowEvents(RenderLevelStageEvent event) {
        ClientLevel lvl = Minecraft.getInstance().level;
        if (lvl == null || !SporeGale.active() && GlowNight.amount() <= 0.002F) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        net.minecraft.world.phys.Vec3 cam = event.getCamera().getPosition();
        LifeSprites.begin(event, true);
        try {
            SporeGale.renderGlow(lvl, partialTick, cam, event.getCamera().getLeftVector(), event.getCamera().getUpVector());
            GlowNight.render(lvl, partialTick, cam, event.getCamera().getLeftVector(), event.getCamera().getUpVector());
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
