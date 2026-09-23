package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.siege.TitanicaKinematics;
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
 * 超大型レイドボス(攻城戦)の<b>遠距離用</b>描画。エンティティ描画とは別枠で、ワールド描画の
 * {@link RenderLevelStageEvent.Stage#AFTER_ENTITIES} に自前で 1 体だけ描く。
 *
 * <h2>なぜエンティティとして描けないのか、そしてここで越えている壁</h2>
 * <ol>
 *   <li><b>同期距離</b> — {@code ChunkMap} のトラッキング距離(サーバーの view-distance が上限、
 *       既定 160〜192 ブロック)を超えるとエンティティはクライアントへ送られない。
 *       → 位置ではなく軌道パラメータを距離無視で配り、クライアントが自分で位置を計算する
 *       ({@link ClientSiegeBoss} / {@link com.yayuy.overgrownvegetation.siege.SiegeBossMotion})。</li>
 *   <li><b>距離・フラスタムカリング</b> — {@code Entity#shouldRenderAtSqrDistance} 等。
 *       → 自前描画なので最初から無関係。</li>
 *   <li><b>照明</b> — ボスのいるチャンクはクライアントに存在しないので明るさを引けない。
 *       → {@link LightTexture#FULL_BRIGHT} 固定。</li>
 *   <li><b>far クリップ面</b> — 投影行列の far は {@code 描画距離(チャンク) × 64} ブロックしかない
 *       (描画距離 12 なら 768 ブロック)。1000 ブロック先はそのままでは<b>クリップされて消える</b>。
 *       → 下記「見かけの距離を詰める」で回避。</li>
 *   <li><b>霧</b> — far を越えなくても、霧の終端より奥は霧色に塗り潰されて見えない。
 *       → 描画の瞬間だけシェーダーの霧を無効化し、代わりに距離に応じた霞の色を自前で乗算する。</li>
 * </ol>
 *
 * <h2>見かけの距離を詰める(インポスタ)</h2>
 * <p>ボスを実際の距離 D ではなく、クリップ面の内側に収まる距離 D' に置き、モデル倍率も同じ比率
 * {@code D'/D} で縮める。見かけの大きさ(視角)は {@code サイズ/距離} なので<b>まったく変わらず</b>、
 * 方向も実際の方向のままなので、見た目は「本当にそこに居る」のと区別がつかない。
 * 太陽・月と同じ古典的な手法。ビーム({@link SiegeBeamRenderer})も始点に同じ縮尺を掛けるので、
 * 光線は描画上の花からきちんと出る。
 *
 * <p>モデルは GeckoLib({@link TitanicaImpostorRenderer} + {@link DorgaArchelausTitanicaModel})で、
 * 近距離用の実エンティティ({@link DorgaArchelausTitanicaRenderer})と完全に共通。
 * 実エンティティがこのクライアントに読み込まれているあいだは描かない(二重になるため)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SiegeBossRenderer {

    /** far クリップ面のどこまでを「描画してよい距離」とみなすか(1.0 = クリップ面ぎりぎり)。 */
    private static final double DRAW_DISTANCE_FRACTION = 0.45D;
    /** 霞(大気遠近)が最大になる距離(ブロック)。 */
    private static final double HAZE_FULL_DISTANCE = 2000.0D;
    /** 霞の最大の強さ(1.0 で完全に霧色)。 */
    private static final float HAZE_MAX = 0.55F;
    /**
     * 実エンティティの描画をあきらめてインポスタへ切り替える距離(プレイヤーの描画距離に対する割合)。
     * バニラの霧はちょうど「描画距離」で終わり、そこから先は<b>完全に霧色で塗り潰される</b>ので、
     * 実エンティティとして描いても見えない。手前で切り替えるための余裕を少し取ってある。
     */
    private static final double IMPOSTOR_SWITCH_FRACTION = 0.8D;

    private static final TitanicaImpostor IMPOSTOR = new TitanicaImpostor();
    private static TitanicaImpostorRenderer renderer;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        if (!ClientSiegeBoss.isActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        Vec3 boss = ClientSiegeBoss.position(partialTick);
        if (!impostorDrawn(boss, camera)) {
            // 実エンティティが描いている。二重に描かない。
            return;
        }
        Vec3 offset = boss.subtract(camera);
        double distance = offset.length();
        if (distance < 1.0E-3D) {
            return;
        }

        // 見かけの距離を far クリップ面の内側へ詰め、同じ比率でモデルを縮める(視角は不変)。
        double shrink = impostorShrink(distance);
        Vec3 drawAt = offset.scale(shrink);
        float scale = (float) (ClientSiegeBoss.scale() * shrink);
        if (scale <= 0.0F) {
            return;
        }

        if (renderer == null) {
            renderer = new TitanicaImpostorRenderer(new DorgaArchelausTitanicaModel<>());
        }

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        // AFTER_ENTITIES の PoseStack は単位行列(カメラの回転は modelView 側に入っている)なので、
        // エンティティ描画と同じく「ワールド座標 - カメラ座標」だけ平行移動すればよい。
        poseStack.translate(drawAt.x, drawAt.y, drawAt.z);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - ClientSiegeBoss.yaw(partialTick)));
        // GeckoLib のフレームは Y 上向きのまま(バニラモデルのような -Y 反転は無い)なので、ピッチは符号を反転。
        poseStack.mulPose(Axis.XP.rotationDegrees(-ClientSiegeBoss.pitch(partialTick)));
        poseStack.scale(scale, scale, scale);
        // ボスの「位置」は胴の中心線(モデル y = 17)なので、その分だけモデルを下げる。
        poseStack.translate(0.0F, (float) (-TitanicaKinematics.BODY_LINE_Y / 16.0D), 0.0F);

        renderer.setColor(hazeColor(distance));
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();

        // シェーダーの霧を無効化してから流す(霧の終端より奥なので、そのままだと霧色で塗り潰される)。
        // 代わりの「遠さ」の表現は hazeColor で頂点色に乗せてある。
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        renderer.render(poseStack, IMPOSTOR, bufferSource, null, null, LightTexture.FULL_BRIGHT, partialTick);
        bufferSource.endBatch();
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);

        poseStack.popPose();
    }

    /**
     * いま<b>インポスタのほうが描いている</b>か(= 実エンティティの描画は休む)。
     * ビームの始点({@link SiegeBeamRenderer})も同じ判定を使わないと、光線の根元が本体から外れる。
     *
     * <h2>なぜ「実エンティティが読み込まれているか」だけでは足りないのか</h2>
     * <p>実エンティティは当たり判定と描画のためにプレイヤーの近くへ寄せてある
     * ({@code DorgaArchelausTitanicaEntity.anchorTo})が、<b>モデルを描く場所は本当の胴の中心</b>で、
     * そこはプレイヤーから数百ブロック離れうる(全長 615 ブロックの体の反対側)。
     * バニラの霧は<b>ちょうど描画距離で終わり、そこから先は完全に霧色で塗り潰される</b>ので、
     * エンティティとして描いても<b>空と同じ色になって消える</b> —
     * 「読み込まれる距離と読み込まれない距離の境目でボスが透明になる」の正体(2026-09-13)。
     *
     * <p>インポスタはシェーダーの霧を切って自前の霞を乗せているので、そちらへ渡せば見える。
     * だから切り替えは<b>エンティティが居るかどうかではなく、描く場所までの距離</b>で決める。
     */
    public static boolean impostorDrawn(Vec3 bodyCenter, Vec3 camera) {
        if (!ClientSiegeBoss.realEntityLoaded()) {
            return true;
        }
        double renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0D;
        return bodyCenter.distanceTo(camera) > renderDistance * IMPOSTOR_SWITCH_FRACTION;
    }

    /** 実距離 {@code distance} のものを描くときに掛ける縮尺(1 = そのまま)。 */
    public static double impostorShrink(double distance) {
        double maxDrawDistance = Minecraft.getInstance().gameRenderer.getDepthFar() * DRAW_DISTANCE_FRACTION;
        return distance <= 1.0E-6D ? 1.0D : Math.min(1.0D, maxDrawDistance / distance);
    }

    /** ワールド座標 {@code world} を、縮尺 {@code shrink} でカメラへ寄せた「描画上の」ワールド座標へ。 */
    public static Vec3 toDrawSpace(Vec3 world, Vec3 camera, double shrink) {
        return camera.add(world.subtract(camera).scale(shrink));
    }

    /**
     * 距離に応じて霧色へ寄せた乗算色(ARGB)。遠いほど霞んで見える(大気遠近)。
     * シェーダーの霧を切っているぶんを、ここで手動で補っている。
     */
    public static int hazeColor(double distance) {
        float haze = (float) Mth.clamp(distance / HAZE_FULL_DISTANCE, 0.0D, 1.0D) * HAZE_MAX;
        float[] fog = RenderSystem.getShaderFogColor();
        int r = (int) (Mth.lerp(haze, 1.0F, fog[0]) * 255.0F);
        int g = (int) (Mth.lerp(haze, 1.0F, fog[1]) * 255.0F);
        int b = (int) (Mth.lerp(haze, 1.0F, fog[2]) * 255.0F);
        return 0xFF000000 | (Mth.clamp(r, 0, 255) << 16) | (Mth.clamp(g, 0, 255) << 8) | Mth.clamp(b, 0, 255);
    }

    private SiegeBossRenderer() {
    }
}
