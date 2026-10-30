package com.yayuy.overgrownvegetation.client.skyboss;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.SiegeBossRenderer;
import com.yayuy.overgrownvegetation.skyboss.SkyBossBody;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFlight;
import com.yayuy.overgrownvegetation.skyboss.SkyBossPose;
import com.yayuy.overgrownvegetation.skyboss.SkyBossTuning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 空の巨大生物を描く(2026-10-18)。エンティティの描画とは別に、{@link RenderLevelStageEvent.Stage#AFTER_ENTITIES} で 1 体だけ描く
 * (攻城戦のボスの {@link SiegeBossRenderer} と同じく GeckoLib のインポスタ)。
 *
 * <h2>頭の道をなぞる</h2>
 * 頭の先から道に沿って、首の輪・b01〜b18 の後ろの輪・尾の輪の点を取り、節ごとの向き(前の輪 − 後ろの輪)を出す。モデルは首の輪(b01 の前、
 * z = {@link SkyBossBody#Z0})をその点へ置いて b01 の向きで回し、ほかの節は「親の節に対する曲がり」を {@link SkyBossModel} がアニメに足す。
 * どの節も道に沿わせる(10-18 の直し。前は腕の付いた所を 1 本の弦にしていて、曲がりが 2 か所の関節に溜まって折れて見えた)。
 * 全体は腹が上(体の軸のまわりに 180°)+ 曲がるときの傾き(最大 70°)。点と曲がりの計算は当たり判定と同じ {@link SkyBossPose}(10-18 夕方にここから移した)。
 *
 * <h2>遠く</h2>
 * 全長 約 2100 ブロックで奥のクリップ面(描画距離 × 64)より長いので、自前のシェーダー({@link SkyBossShaders})が<b>遠い所の頂点だけ</b>
 * 見える向きのままカメラへ寄せる(絵は変わらず奥行きだけ縮む)。近い所は本当の位置・奥行きなので、近くの雲・地形と正しく前後する。
 * シェーダーが無いときだけ、前と同じく体ごと寄せて縮める。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SkyBossRenderer {

    /**
     * ここ(奥のクリップ面に対する割合)より遠い頂点を寄せ始め、いちばん遠い所を {@link #SQUEEZE_LIMIT} へ。
     * 雲を引く演出({@code client/CloudWake}、雰囲気づくりのセッション)がこの 3 つの値と寄せ方の式を写しているので、変えるときは向こうにも知らせる。
     */
    private static final double SQUEEZE_START = 0.6;
    private static final double SQUEEZE_LIMIT = 0.95;
    /** 扇の広がりの分の余裕(ブロック。腕と刃の長さ 約 180 単位)。 */
    private static final double FAN_MARGIN = 190.0 * SkyBossTuning.BLOCKS_PER_UNIT;
    /** 霞がいちばん濃くなる距離(ブロック)と濃さ(霧の色を混ぜる割合)。仮。 */
    private static final float HAZE_FULL = 6000.0F;
    private static final float HAZE_MAX = 0.6F;

    private static final SkyBossImpostor IMPOSTOR = new SkyBossImpostor();
    private static SkyBossImpostorRenderer renderer;
    /** 描画の姿勢(毎フレーム、道と曲がりだけ。確かめ用の線を描くときは形も)。 */
    private static SkyBossPose pose;

    /**
     * 描く段階。ふだんはエンティティの後。Distant Horizons が入っているときは tripwire の層の後(2026-10-20)。
     * DH は tripwire の層を描き始める所で「ふつうの描画を DH の遠景へ溶かし込む」(vanillaFadeMode = DOUBLE_PASS、既定)をして、
     * 描画距離より遠い深さの所を DH の遠景(無ければ空)で塗り直す。この体の遠い所はカメラへ寄せても奥のクリップ面の 6〜95% の深さにあるので、
     * エンティティの後に描くと塗り直されて消えていた(DH の雲や遠景がある所だけ残る。攻城戦のボスが DH で消えたのも同じ)。その後に描けば消されない。
     * 半透明の地形(水)より後になるので、水越しに見ると水の向こうで隠れる(DH のときだけ)。
     */
    private static final RenderLevelStageEvent.Stage STAGE = net.neoforged.fml.ModList.get().isLoaded("distanthorizons")
            ? RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS : RenderLevelStageEvent.Stage.AFTER_ENTITIES;

    /**
     * 今のフレームに描く段階。Iris のシェーダーパック使用中に自前のシェーダーで描くとき(10-23)は、DH があってもエンティティの後
     * (パックの遅延処理 = 半透明の地形の直前より前)に描く。tripwire の後だと遅延処理の後に奥行きを書くことになり、パックが体を半透明の面のように扱って、
     * 近いほど灰色の霞に沈んだ。
     */
    private static RenderLevelStageEvent.Stage stage() {
        return com.yayuy.overgrownvegetation.client.ShaderPacks.ownEffects() && !SkyBossShaders.vanillaFallback()
                ? RenderLevelStageEvent.Stage.AFTER_ENTITIES : STAGE;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != stage() || !ClientSkyBoss.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        SkyBossFlight f = ClientSkyBoss.flight();
        double t = f.tick - 1 + pt;
        double hx = Mth.lerp(pt, f.px, f.x);
        double hy = Mth.lerp(pt, f.py, f.y);
        double hz = Mth.lerp(pt, f.pz, f.z);
        if (pose == null) {
            pose = new SkyBossPose();
        }
        SkyBossPose ps = pose;
        ps.parts = ClientSkyBoss.parts();
        ps.compute(f, hx, hy, hz, ClientSkyBoss.bank(pt), t, SkyBossDebugDraw.ENABLED);
        double[][] points = ps.points;
        double far = 0.0;
        for (double[] p : points) {
            far = Math.max(far, Math.sqrt(sq(p[0] - cam.x) + sq(p[1] - cam.y) + sq(p[2] - cam.z)));
        }
        far += FAN_MARGIN;
        double depthFar = mc.gameRenderer.getDepthFar();
        // Iris のシェーダーパック使用中に自前のシェーダーで描けないとき(10-23)も、前の作り(バニラの描き方)へ落とす
        boolean own = SkyBossShaders.shader != null && !SkyBossShaders.vanillaFallback();
        // シェーダーが無いときだけ体ごと寄せて縮める(前の作り)
        double shrink = own ? 1.0 : Math.min(1.0, depthFar * 0.9 / Math.max(1.0, far));
        for (int i = 0; i < SkyBossPose.BEND_COUNT; i++) {
            System.arraycopy(ps.bends[i], 0, SkyBossModel.BENDS[i], 0, 3);
        }
        // 避雷針が壊れた後の体の反動のうち、曲がりに入らない分(b01 の回転と体の根の位置)
        SkyBossModel.recoilB01 = ps.recoilB01;
        SkyBossModel.recoilBodyY = ps.recoilBodyY;
        SkyBossModel.oralSwing = SkyBossModel.oralSwing(f, t);
        // 背の垂れ草を垂らす向き: 体の骨の行列(うねり・震え・反動込み)。確かめ用の線を描くときは compute が出し済み
        if (!SkyBossDebugDraw.ENABLED) {
            ps.bodyBones(t);
        }
        SkyBossModel.hangPose = ps;
        SkyBossModel.hangTime = t;
        // 電紋・避雷針・扇の先の光(10-19。放電の溜め・予告・放電中。光る層のシェーダーがあるときだけ)
        SkyBossGlowLayer.on = SkyBossShaders.glowType() != null && SkyBossGlow.compute(f, t);

        if (renderer == null) {
            renderer = new SkyBossImpostorRenderer();
        }
        double[] r0 = points[1];
        double[] mid = points[(2 + 12) / 2 + 1];
        double distance = Math.sqrt(sq(mid[0] - cam.x) + sq(mid[1] - cam.y) + sq(mid[2] - cam.z));
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate((r0[0] - cam.x) * shrink, (r0[1] - cam.y) * shrink, (r0[2] - cam.z) * shrink);
        pose.mulPose(ps.b01);
        float s = (float) (SkyBossTuning.MODEL_SCALE * shrink);
        pose.scale(s, s, s);
        // 首の輪(モデルの (0, Y0, Z0)、1/16 ブロック単位)を原点へ
        pose.translate(0.0, -SkyBossBody.Y0 / 16.0, -SkyBossBody.Z0 / 16.0);

        float[] fog = RenderSystem.getShaderFogColor();
        float sqStart = (float) (depthFar * SQUEEZE_START);
        float sqLimit = (float) (depthFar * SQUEEZE_LIMIT);
        if (own) {
            renderer.setColor(0xFFFFFFFF);
            SkyBossShaders.setUniforms(sqStart, sqLimit, (float) far, HAZE_FULL, HAZE_MAX, fog);
        } else {
            renderer.setColor(SiegeBossRenderer.hazeColor(distance));
        }
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        // 空の明かり 15・ブロックの明かり 0(昼夜で暗くなる。光る所は次の段で)
        renderer.render(pose, IMPOSTOR, buffers, null, null, LightTexture.pack(0, 15), pt);
        // 体を先に描き終えてから光る層(同じ深さで重ねるので、体の奥行きが先に要る)
        net.minecraft.client.renderer.RenderType mainType = SkyBossShaders.mainType();
        if (mainType != null) {
            buffers.endBatch(mainType);
        }
        if (SkyBossGlowLayer.on) {
            SkyBossShaders.setGlowUniforms(sqStart, sqLimit, (float) far, HAZE_FULL, HAZE_MAX, (float) (t / 20.0));
            buffers.endBatch(SkyBossShaders.glowType());
        }
        buffers.endBatch();
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        pose.popPose();
        // 放電の雷(10-19)。まわりの雷は決まった式、当たった雷はサーバーから
        SkyBossArcs.draw(pose, buffers, f, ClientSkyBoss.pose(), ClientSkyBoss.seed(), t, cam, own ? sqStart : Float.MAX_VALUE, sqLimit, (float) far);
        // 技の弾(10-19)
        SkyBossShotsView.draw(pose, buffers, cam, pt);
        // 転がりのビーム(10-19。蕾の位置・向きは描画の姿勢の骨から)
        SkyBossBeams.draw(pose, buffers, f, ps, t, cam, own ? sqStart : Float.MAX_VALUE, sqLimit, (float) far);
        if (SkyBossDebugDraw.ENABLED && SkyBossDebugDraw.show) {
            SkyBossDebugDraw.draw(f, hx, hy, hz, t, cam, shrink, ps, own ? sqStart : Float.MAX_VALUE, sqLimit, (float) far);
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private SkyBossRenderer() {
    }
}
