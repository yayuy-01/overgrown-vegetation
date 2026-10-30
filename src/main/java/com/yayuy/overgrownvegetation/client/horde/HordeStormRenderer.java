package com.yayuy.overgrownvegetation.client.horde;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 遠景の身代わりの <b>4 段目: 描画距離の外は地平線に胞子の嵐</b>(2026-09-25、ユーザー指定「大群が迫ってくる演出を TD に組み込みたい」の
 * 続き。runClient 4 回目「続きの 4 段目も実装お願いします」)。
 *
 * <h2>なぜ要るか</h2>
 * 行進中の群れの絵({@link ClientHorde} → {@link HordeRenderer})は、描画距離と地形の霧の終わりの近い方(以下「描く奥行き」)までしか
 * 描かない —— その先は地形が無く、霧で塗り潰されるので、絵を立てても宙に浮くか見えない。描画距離が短い人・過繁茂の霧(終わり 96 ブロック)
 * の中にいる人には、遠くから迫る大群が何も見えなかった。
 *
 * <h2>何を描くか</h2>({@link StormPuffs} が板を並べ、ここは頂点にするだけ)
 * <ul>
 *     <li><b>地平線の嵐の壁</b>: 描く奥行きの 1.3 倍の輪に、TD の地上の群れがいる方角ほど濃く・高く積んだ嵐の壁。群れが全方位にいれば
 *     地平線がぐるりと隠れる(2026-09-25 のユーザー「地平線の胞子の嵐の量を増やせますか?地平線が見えないレベルにまで上げてもらって
 *     構わない」)。群れが手前(描く奥行きの内)にいても、その向こうの地平線に嵐が立つ(霧の中から大群が出てくる見え方)。</li>
 *     <li><b>群れの嵐の柱</b>: 描く奥行きの外の地上の群れごとに、頭数に合わせた土ぼこりの柱(6 体で高さ約 67。壁の上にそびえる)。</li>
 *     <li><b>空の群れ</b>: 一員の絵を、霧を切って空に影として描く({@link HordeRenderer} の遠い側。{@link ClientHorde#collect})。</li>
 * </ul>
 *
 * <h2>描き方</h2>
 * 攻城戦ボスの身代わり({@code client/SiegeBossRenderer})と同じく、描く瞬間だけシェーダーの霧を切り、代わりに距離に応じて霧の色へ寄せる
 * (霞)。近くの地形より奥にあるので、手前の丘・木には隠れる(深さは書かない)。色は霧が明るいほど暗く・暗いほど明るくし(過繁茂の暗い緑の
 * 霧に溶けないように)、昼夜の明るさを掛け、かすかな光を足す(夜でもうっすら見える)。雲の絵は起動後に作る({@link StormPuffs#atlas}。
 * 素材ファイルは増えない)。全部の板を 1 回で描く(遠い順に並べる)。水・溶岩・粉雪の中、盲目・暗闇の間は描かない(霧がごく近い)。
 *
 * <p>設定 {@code hordeStormsEnabled}(と {@code hordeSpritesEnabled})で切れる、{@code hordeStormDensity} で濃さ(壁の濃さと柱の濃さに
 * 掛ける)。GameTest では触れない(クライアントのクラス)。見た目は {@code tools/horde-storm}(起動せずに同じ計算で PNG を描く)で確かめた。
 * 数値は仮決め 2026-09-25。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class HordeStormRenderer {

    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("horde/storm_puffs");
    /** 投影の奥の面のどこまでに収めるか(攻城戦ボスの身代わりと同じ 0.45)。 */
    private static final double DRAW_DISTANCE_FRACTION = 0.45D;
    /** 雲の絵の u・v の内側への寄せ(隣の絵へにじまないように半画素)。 */
    private static final float INSET_U = 0.5F / StormPuffs.ATLAS_W;
    private static final float INSET_V = 0.5F / StormPuffs.ATLAS_H;

    /** 壁の方角ごとの濃さ(時間で寄せる)。 */
    private static final StormPuffs.WallState WALL = new StormPuffs.WallState();
    @Nullable
    private static ClientLevel wallLevel;
    @Nullable
    private static RenderType renderType;
    private static boolean textureReady;
    /** 描画中に例外が出たら、このセッションの間は描かない({@link HordeRenderer} と同じ保険)。 */
    private static boolean broken;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || broken) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.hordeSpritesEnabled.get() || !cfg.hordeStormsEnabled.get()) {
            return;
        }
        try {
            render(event, cfg.hordeStormDensity.get().floatValue());
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("地平線の胞子の嵐の描画で例外が出たので、このセッションでは描かない", e);
        }
    }

    private static void render(RenderLevelStageEvent event, float density) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        if (level != wallLevel) {
            WALL.reset();
            wallLevel = level;
        }
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE || minecraft.player != null
                && (minecraft.player.hasEffect(MobEffects.BLINDNESS) || minecraft.player.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        Collection<ClientHorde.Swarm> swarms = ClientHorde.swarms(level);
        if (swarms.isEmpty() && WALL.total() <= 0.0F) {
            return;
        }
        double limit = HordeRenderer.drawLimit(minecraft);
        if (!(limit > 0.0D)) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double tick = level.getGameTime() + partialTick;
        Vec3 eye = camera.getPosition();
        List<StormPuffs.SwarmView> views = new ArrayList<>(swarms.size());
        double[] center = new double[2];
        for (ClientHorde.Swarm swarm : swarms) {
            if (swarm.air() || swarm.heads() <= 0) {
                continue; // 空の群れはほこりを立てない(影で描く。HordeRenderer)
            }
            swarm.center(tick, center);
            float heading = swarm.heading(tick);
            views.add(new StormPuffs.SwarmView(swarm.id, swarm.heads(), center[0] - eye.x, swarm.y() - eye.y, center[1] - eye.z,
                    (float) Math.cos(heading), (float) Math.sin(heading)));
        }
        float[] fog = RenderSystem.getShaderFogColor();
        StormPuffs.Frame frame = new StormPuffs.Frame(limit, minecraft.gameRenderer.getDepthFar() * DRAW_DISTANCE_FRACTION, tick / 20.0D,
                new float[]{fog[0], fog[1], fog[2]}, level.getSkyDarken(partialTick), Math.max(0.0F, density));
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(renderType());
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        StormPuffs.Sink sink = (x, y, z, halfWidth, halfHeight, spin, tile, r, g, b, alpha) ->
                emit(consumer, (float) x, (float) y, (float) z, halfWidth, halfHeight, spin, tile, left, up, argb(alpha, r, g, b));
        StormPuffs.wall(views, frame, WALL, sink);
        StormPuffs.storms(views, frame, sink);
        // シェーダーの霧を切って流す(描く奥行きの外なので、そのままだと霧の色に塗り潰される)。遠さは霞として色に入れてある。
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        try {
            buffers.endBatch(renderType());
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
        }
    }

    /** 色と濃さ(0〜1)を ARGB に。 */
    private static int argb(float alpha, float r, float g, float b) {
        return clamp255(alpha) << 24 | clamp255(r) << 16 | clamp255(g) << 8 | clamp255(b);
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0F)));
    }

    /**
     * カメラを向く板 1 枚(中心 = カメラから (x, y, z)、半分の横幅・高さ、板の面内で {@code spin} 回す)。横幅が負なら左右を裏返す
     * (頂点が入れ替わり、絵が鏡に映る)。
     */
    private static void emit(VertexConsumer consumer, float x, float y, float z, float halfWidth, float halfHeight, float spin, int tile,
                             Vector3f left, Vector3f up, int color) {
        float cos = (float) Math.cos(spin);
        float sin = (float) Math.sin(spin);
        // 回した右(= −左)と上。
        float rx = (-left.x() * cos + up.x() * sin) * halfWidth;
        float ry = (-left.y() * cos + up.y() * sin) * halfWidth;
        float rz = (-left.z() * cos + up.z() * sin) * halfWidth;
        float ux = (left.x() * sin + up.x() * cos) * halfHeight;
        float uy = (left.y() * sin + up.y() * cos) * halfHeight;
        float uz = (left.z() * sin + up.z() * cos) * halfHeight;
        float u0 = StormPuffs.tileU(tile) + INSET_U;
        float v0 = StormPuffs.tileV(tile) + INSET_V;
        float u1 = StormPuffs.tileU(tile) + StormPuffs.TILE_U - INSET_U;
        float v1 = StormPuffs.tileV(tile) + StormPuffs.TILE_V - INSET_V;
        int light = LightTexture.FULL_BRIGHT;
        int overlay = OverlayTexture.NO_OVERLAY;
        consumer.addVertex(x - rx - ux, y - ry - uy, z - rz - uz, color, u0, v1, overlay, light, 0.0F, 1.0F, 0.0F);
        consumer.addVertex(x + rx - ux, y + ry - uy, z + rz - uz, color, u1, v1, overlay, light, 0.0F, 1.0F, 0.0F);
        consumer.addVertex(x + rx + ux, y + ry + uy, z + rz + uz, color, u1, v0, overlay, light, 0.0F, 1.0F, 0.0F);
        consumer.addVertex(x - rx + ux, y - ry + uy, z - rz + uz, color, u0, v0, overlay, light, 0.0F, 1.0F, 0.0F);
    }

    /**
     * 嵐を描く型: 半透明の Mob と同じシェーダー、雲の絵は線形補間(ミップマップなし)、両面、深さは読むが書かない(塊どうしが重なって
     * 透ける)、描く前に遠い順に並べる。明るさは昼夜の明るさを色に入れてあるので常に最大。
     */
    private static RenderType renderType() {
        if (renderType == null) {
            ensureTexture();
            renderType = RenderType.create(
                    OvergrownVegetation.MODID + "_horde_storm",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    65536,
                    false,
                    true,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(TEXTURE, true, false))
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setLightmapState(RenderStateShard.LIGHTMAP)
                            .setOverlayState(RenderStateShard.OVERLAY)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return renderType;
    }

    /** 雲の絵を GPU へ上げてテクスチャとして登録する(描画スレッド、初めて描くとき 1 回)。 */
    private static void ensureTexture() {
        if (!textureReady) {
            Minecraft.getInstance().getTextureManager().register(TEXTURE, new StormTexture());
            textureReady = true;
        }
    }

    /** {@link StormPuffs#atlas} を上げたテクスチャ(0 段目だけ。遠くで小さくなっても雲はぼやけているので、ミップマップは要らない)。 */
    private static final class StormTexture extends AbstractTexture {

        StormTexture() {
            RenderSystem.assertOnRenderThreadOrInit();
            int[] argb = StormPuffs.atlas();
            TextureUtil.prepareImage(this.getId(), 0, StormPuffs.ATLAS_W, StormPuffs.ATLAS_H);
            try (NativeImage image = new NativeImage(StormPuffs.ATLAS_W, StormPuffs.ATLAS_H, false)) {
                for (int y = 0; y < StormPuffs.ATLAS_H; y++) {
                    for (int x = 0; x < StormPuffs.ATLAS_W; x++) {
                        int c = argb[y * StormPuffs.ATLAS_W + x];
                        image.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16));
                    }
                }
                this.bind();
                image.upload(0, 0, 0, false);
            }
        }

        @Override
        public void load(ResourceManager manager) {
            // 中身は作ったときに上げてある(資源から読むものは無い)。
        }
    }

    private HordeStormRenderer() {
    }
}
