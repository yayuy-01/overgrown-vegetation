package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.shader.PortRender;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.shader.ModShaders;
import com.yayuy.overgrownvegetation.client.shader.ShaderPacks;
import com.yayuy.overgrownvegetation.data.AtmosphereEye;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * <b>隙間と根元の陰り</b>(簡易 AO。2026-11-14 シェーダーの仕上げ H。ユーザー決定: 作るが<b>既定でオフ</b>、設定で入れる。重さを測って報告する)。
 *
 * <p>理由: 林床・根元・壁の隅に、物が地面に乗っている感じ(接地感)が出る。バニラのなめらかな照明はブロックの面の頂点にしか陰りを付けないので、
 * 草・シダ・根などの切り抜きの植物や、細かな段差には陰りが無い。重い(画面全体の処理が毎フレーム 2 回)ので既定でオフ。
 *
 * <h2>やり方</h2>
 * 地形・Mob・粒を描いた後、手を描く前(描画の段 AFTER_LEVEL。光のにじみ・目の慣れより先)に、
 * <ol>
 *     <li>画面の奥行きだけから、半分の大きさの器へ陰りを出す({@code ao_compute.fsh}): 画素ごとに視点からの位置と面の向き(隣の奥行きから)を出し、
 *     まわり {@code radius} ブロックの円の上の 8 点のうち、その面より手前へせり出している点の分だけ暗くする。空と {@code distance} より遠い所は 0。</li>
 *     <li>それを 4 点でならして、画面へ掛け算で重ねる({@code ao_composite.fsh})。</li>
 * </ol>
 * 射影行列は地形を描いたときのもの。手は後から描かれるので、手には陰りが掛からない。
 * 掛けない場合: 見え方の設定「隙間と根元の陰り」({@code simpleAo}、既定オフ)・シェーダーパック使用中。数は design の節 {@code simpleAo}(仮決め)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class SimpleAo {

    private SimpleAo() {
    }

    private static boolean broken;
    private static TextureTarget target;
    private static boolean filterSet;
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static boolean haveProjection;

    /** 世界を描き終えた所(AFTER_LEVEL。手を描く前): 射影行列を控えて、陰りを掛ける。 */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            PROJECTION.set(event.getProjectionMatrix());
            haveProjection = true;
            // 2026-11-16: ここで掛ける。前は GameRenderer の renderLevel の直後に掛けていたが、手を描く直前に画面の奥行きが消されるので、この日の時点では
            // 奥行きが全部「空」になって陰りが 1 つも出ていなかった(11-14 の写真には写っていた。何が変わったかは未特定)。
            renderPost();
        }
    }

    private static void renderPost() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || broken || !haveProjection) {
            return;
        }
        AtmosphereEye.SimpleAoParams p = DesignData.atmosphere().simpleAo();
        if (p.strength() <= 0.0F || !VisualQuality.simpleAo() || ShaderPacks.active()) {
            return;
        }
        ShaderInstance compute = ModShaders.aoCompute();
        ShaderInstance composite = ModShaders.aoComposite();
        if (compute == null || composite == null) {
            return;
        }
        try {
            draw(mc, compute, composite, p);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("隙間と根元の陰り(簡易 AO)で例外が出たので、このセッションでは掛けない", e);
        }
    }

    private static void quad(ShaderInstance shader) {
        RenderSystem.setShader(() -> shader);
        BufferBuilder buffer = PortRender.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        buffer.vertex(-1.0F, -1.0F, 0.0F).endVertex();
        buffer.vertex(1.0F, -1.0F, 0.0F).endVertex();
        buffer.vertex(1.0F, 1.0F, 0.0F).endVertex();
        buffer.vertex(-1.0F, 1.0F, 0.0F).endVertex();
        BufferUploader.drawWithShader(buffer.end());
    }

    private static void draw(Minecraft mc, ShaderInstance compute, ShaderInstance composite, AtmosphereEye.SimpleAoParams p) {
        RenderTarget main = mc.getMainRenderTarget();
        int w = Math.max(1, main.width / 2);
        int h = Math.max(1, main.height / 2);
        if (target == null) {
            target = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            filterSet = false;
        } else if (target.width != w || target.height != h) {
            target.resize(w, h, Minecraft.ON_OSX);
            filterSet = false;
        }
        if (!filterSet) {
            int previous = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
            GlStateManager._bindTexture(target.getColorTextureId());
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._bindTexture(previous);
            filterSet = true;
        }
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        // 1: 奥行きから陰りを出す(半分の大きさ)。
        target.bindWrite(true);
        compute.setSampler("DepthTex", main.getDepthTextureId());
        compute.safeGetUniform("AoA").set(p.radius(), p.strength(), p.distance(), 0.0F);
        compute.safeGetUniform("AoB").set(PROJECTION.m00(), PROJECTION.m11(), PROJECTION.m22(), PROJECTION.m32());
        quad(compute);
        // 2: 画面へ掛け算で重ねる。
        main.bindWrite(true);
        composite.setSampler("AoTex", target.getColorTextureId());
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        quad(composite);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }
}
