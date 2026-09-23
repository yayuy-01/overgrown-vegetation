package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * {@code overgrown} バイオーム({@link ModBiomes#OVERGROWN})の中にいる間の霧の演出。
 *
 * <ul>
 *     <li><b>濃さ・可視範囲</b>: 霧の終端距離を最大 {@link #MAX_FOG_DISTANCE} ブロック(= 6 チャンク)に
 *     制限し、始端はその {@link #FOG_NEAR_FRACTION} 倍にする。プレイヤーの描画距離がそれより小さい場合は
 *     そちらに従う。これにより過繁茂バイオームでは「6 チャンク先はもう完全に霧」という見え方になる。</li>
 *     <li><b>色</b>: バニラは夜になると霧色を明るさで大きく暗くするため、暗い基準色の過繁茂バイオームでは
 *     霧が真っ黒になってしまう。{@link ViewportEvent.ComputeFogColor} で各成分に下限
 *     ({@link #FLOOR_R}/{@link #FLOOR_G}/{@link #FLOOR_B})を設けて、夜でも「暗いが色のある靄」に留める。</li>
 * </ul>
 *
 * <p>過繁茂バイオームとそれ以外で差が大きく境界を跨いだ瞬間に切り替わると不自然なので、実際の適用量は
 * 目標へ 設定 {@code fogFadeSeconds}(既定 10 秒)かけて実時間ベースで補間する(入ると徐々に、出ると徐々に戻る)。
 * バニラのバイオーム効果には「霧の濃さ」を変える項目が無いため、ここで上乗せする必要がある。
 *
 * <p><b>霧の奥のチャンク描画スキップ</b>: 霧を濃くしても、その奥にあるチャンクのジオメトリ(草・葉など)は
 * バニラの描画パイプラインでは普通に頂点処理・ドローコールされ続ける(霧は最終段でピクセルを潰すだけ)。
 * そこで {@link com.yayuy.overgrownvegetation.mixin.client.LevelRendererFogCullMixin} が
 * {@link #terrainFogCullFarPlane()} を参照し、地形霧の終端より奥のチャンクセクションを毎フレームの
 * 描画対象から外す(Sodium 系の "fog occlusion" と同じ考え方)。チャンク自体はアンロードせず、
 * 描画対象から外すだけなので境界往復でのチャンク再構築は起きない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CorruptionAtmosphere {

    /** 霧の終端距離の上限(ブロック)。16 * 6 = 6 チャンク。 */
    private static final float MAX_FOG_DISTANCE = 16.0F * 6.0F;

    /**
     * 極相(engulfed)バイオームでの霧の終端距離(ブロック = 3 チャンク)。過繁茂の半分。
     * 「奥へ入るほど見えなくなる」を色ではなく可視範囲で出すための値(2026-09-22)。
     */
    private static final float CLIMAX_FOG_DISTANCE = 16.0F * 3.0F;
    /** 霧の始端距離を終端距離の何倍にするか(小さいほど手前から霧がかかる)。 */
    private static final float FOG_NEAR_FRACTION = 0.3F;
    /** 目標の状態へ到達するまでにかける時間(ミリ秒)。設定 fogFadeSeconds(既定 10 秒)から毎フレーム読む。 */
    private static float fadeMillis() {
        return (float) (OvergrownVegetationConfig.COMMON.fogFadeSeconds.get() * 1000.0D);
    }

    /** 過繁茂バイオーム内での霧色の下限(夜に真っ黒へ潰れないように)。 */
    private static final float FLOOR_R = 0.09F;
    private static final float FLOOR_G = 0.13F;
    private static final float FLOOR_B = 0.14F;

    /**
     * チャンク描画スキップを有効にする最小の補間係数。<b>「霧を上書きしているか」だけを見る。</b>
     *
     * <h3>0.85 から 0.05 に下げた(2026-09-17、FPS 低下の調査)</h3>
     * <p>元は「境界のフェード中に地形が消えたり湧いたりして見えるのを避けるため」0.85 にしていた。
     * ところが<b>スキップの基準距離は霧が 100% 不透明になる距離そのもの</b>
     * ({@link #appliedTerrainFogFar} = {@link ViewportEvent.RenderFog} に実際に設定した終端距離。
     * バニラの地形霧は始端→終端で線形に濃くなり、終端で完全に霧色へ置き換わる)なので、
     * 補間係数がいくらであってもその奥は見えない —— 0.85 という下限は<b>要らなかった</b>。
     * 係数が小さいときは {@link #onRenderFog} の {@code Mth.lerp} が終端距離をバニラ側
     * (= 描画距離)へ寄せるため、スキップの基準距離も自動的に遠のいて何も切られなくなる。
     *
     * <p>下限が 0.85 だったことで、<b>いちばん重い場面ほどスキップが効いていなかった</b>:
     * <ul>
     *     <li><b>湿地・菌湿地帯</b> —— {@code swampFogFactor}(既定 0.45)が上限なので、
     *     この 2 バイオームでは<b>スキップが一度も働かない</b>。霧が薄くて遠くまで見えるうえ、
     *     地面は過繁茂バイオームと同じ密度の下草(巻きシダは 1 本 86 面)で埋まっている。</li>
     *     <li><b>バイオームの境界を跨いだ直後</b> —— {@code fogFadeSeconds}(既定 10 秒)の間は
     *     係数が 0.85 を下回るので、出入りするたびに 10 秒ぶんスキップが切れる。</li>
     * </ul>
     *
     * <p>0 ではなく 0.05 にしてあるのは、霧をまったく上書きしていない場面で
     * 無駄に判定を回さないための足切りだけ。
     */
    private static final float CULL_MIN_FOG_FACTOR = 0.05F;

    /** 極相バイオームに居る度合い(0〜1)。霧と同じ速さで寄せる。ライトマップの暗さに使う。 */
    private static float climaxFactor;

    /** 現在の霧の終端距離(ブロック)。過繁茂 ↔ 極相の境目で霧が一段飛ばないよう、補間係数と同じ速さで寄せる。 */
    private static float fogDistance = MAX_FOG_DISTANCE;

    /** 現在の補間係数(0 = 通常、1 = 過繁茂バイオームの演出が完全に効いた状態)。 */
    private static float fogFactor;
    /** 直近に補間を進めた実時刻(ミリ秒)。0 は未初期化。 */
    private static long lastMillis;
    /**
     * 直近フレームで {@link #onRenderFog} が地形霧に実際に適用した終端距離(ブロック、カメラからの距離)。
     * {@link com.yayuy.overgrownvegetation.mixin.client.LevelRendererFogCullMixin} が読む。
     * -1 は「今フレームは地形霧を上書きしていない」= スキップしないことを表す。
     */
    private static volatile float appliedTerrainFogFar = -1.0F;

    /** 毎フレーム最初に呼ばれる {@link ViewportEvent.ComputeFogColor} で補間係数を進め、霧色の下限を適用する。 */
    @SubscribeEvent
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }

        var biome = level.getBiome(player.blockPosition());
        float target = 0.0F;
        if (ModBiomes.isOvergrownLike(biome)) {
            // 湿地・菌湿地帯は密林ほど霧を濃くしない(swampFogFactor、1.0 で密林と同じ)。
            target = ModBiomes.isSwampLike(biome)
                    ? OvergrownVegetationConfig.COMMON.swampFogFactor.get().floatValue()
                    : 1.0F;
        }
        boolean climax = ModBiomes.isClimax(biome);
        advanceFactor(target, climax ? CLIMAX_FOG_DISTANCE : MAX_FOG_DISTANCE, climax ? 1.0F : 0.0F);

        if (fogFactor <= 0.001F) {
            return;
        }

        event.setRed(liftToFloor(event.getRed(), FLOOR_R, fogFactor));
        event.setGreen(liftToFloor(event.getGreen(), FLOOR_G, fogFactor));
        event.setBlue(liftToFloor(event.getBlue(), FLOOR_B, fogFactor));
    }

    /** 地形霧の終端・始端距離を 6 チャンク以内へ制限する。 */
    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || fogFactor <= 0.001F) {
            appliedTerrainFogFar = -1.0F;
            return;
        }

        float vanillaNear = event.getNearPlaneDistance();
        float vanillaFar = event.getFarPlaneDistance();

        float cappedFar = Math.min(vanillaFar, fogDistance);
        float cappedNear = Math.min(vanillaNear, cappedFar * FOG_NEAR_FRACTION);

        float appliedFar = Mth.lerp(fogFactor, vanillaFar, cappedFar);
        event.setNearPlaneDistance(Mth.lerp(fogFactor, vanillaNear, cappedNear));
        event.setFarPlaneDistance(appliedFar);
        event.setCanceled(true);

        appliedTerrainFogFar = appliedFar;
    }

    /**
     * 霧の奥のチャンクセクション・エンティティを描画対象から外してよい場合、その基準距離
     * (ブロック、カメラからの距離)を返す。これより奥は地形霧で完全に隠れているため描画しても見えない。
     * スキップすべきでない場合は負の値を返す(設定で無効・今フレームは地形霧を上書きしていない、
     * のいずれか)。
     *
     * <p>読み手は 2 つ: 地形は
     * {@link com.yayuy.overgrownvegetation.mixin.client.LevelRendererFogCullMixin}、
     * エンティティは {@link EntityFogCull}。
     */
    public static float terrainFogCullFarPlane() {
        if (!OvergrownVegetationConfig.COMMON.fogCullEnabled.get()) {
            return -1.0F;
        }
        if (fogFactor < CULL_MIN_FOG_FACTOR) {
            return -1.0F;
        }
        return appliedTerrainFogFar;
    }


    /**
     * 空の光の強さ({@link net.minecraft.world.level.Level#getSkyDarken})を極相バイオームで下げる。
     * {@code LightTextureClimaxMixin} から呼ぶ。
     *
     * <p>{@code climax.json} の {@code biome.daylightBrightness}(既定 0.2 = バニラの夜と同じ)まで
     * 落とすので、<b>真昼でも夜と同じ暗さ</b>になる。松明・グロウベリーなどのブロック光は
     * この値を通らないので、明かりを置けばそこだけ普通に明るい —— 「閉じた林冠の下」の絵になる。
     *
     * <p>すでにそれより暗いとき(本物の夜)は何もしない。{@code Math.min} を挟んでいるのはそのため。
     * 境目で明るさが飛ばないよう、霧と同じ {@link #climaxFactor} で寄せる。
     *
     * <p><b>見た目だけの処理。</b> サーバー側の明るさは変わらないので、湧き判定・作物の成長・
     * 日光による炎上には影響しない(極相には通常 Mob がそもそも湧かないので実害は無い)。
     */
    public static float climaxSkyDarken(float vanilla) {
        if (climaxFactor <= 0.001F) {
            return vanilla;
        }
        float dark = Math.min(vanilla, DesignData.climax().daylightBrightness());
        return Mth.lerp(climaxFactor, vanilla, dark);
    }
    private static void advanceFactor(float target, float targetDistance, float climaxTarget) {
        long now = Util.getMillis();
        float elapsed = lastMillis == 0L ? 0.0F : (now - lastMillis);
        lastMillis = now;

        float fade = fadeMillis();
        float step = fade > 0.0F ? elapsed / fade : 1.0F;
        if (fogFactor < target) {
            fogFactor = Math.min(target, fogFactor + step);
        } else if (fogFactor > target) {
            fogFactor = Math.max(target, fogFactor - step);
        }
        float ease = Math.min(1.0F, step);
        fogDistance = Mth.lerp(ease, fogDistance, targetDistance);
        climaxFactor = Mth.lerp(ease, climaxFactor, climaxTarget);
    }

    /** {@code current} を下限 {@code floor} まで持ち上げた値へ、補間係数 {@code factor} ぶんだけ寄せる。 */
    private static float liftToFloor(float current, float floor, float factor) {
        return Mth.lerp(factor, current, Math.max(current, floor));
    }

    private CorruptionAtmosphere() {
    }
}
