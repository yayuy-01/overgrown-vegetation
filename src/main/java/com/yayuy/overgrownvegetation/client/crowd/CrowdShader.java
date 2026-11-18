package com.yayuy.overgrownvegetation.client.crowd;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.lwjgl.opengl.GL20;

import javax.annotation.Nullable;
import java.io.IOException;

/**
 * 大群のまとめ描き({@link CrowdDraw})のシェーダー {@code crowd_entity}(2026-11-18)。バニラの
 * {@code rendertype_entity_cutout_no_cull} と同じ光・霧(この MOD の {@code fog.glsl} もそのまま効く)・傷の色で、
 * 頂点の位置と法線だけを骨の行列(texture buffer)から作る。
 *
 * <p>{@code client/shader/ModShaders} とは別に、ここで登録する(持ち場を分けるため)。資源の読み直しでも呼ばれ直すので、
 * そのたびに形({@link CrowdMesh})を作り直させる。</p>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CrowdShader {

    /** 頂点の属性・自前の uniform の場所(シェーダーが変わるたびに引き直す)。 */
    record Locations(int position, int uv0, int normal, int boneInfo, int bones, int stride, int base) {
    }

    @Nullable
    private static ShaderInstance shader;
    @Nullable
    private static Locations locations;

    private CrowdShader() {
    }

    @Nullable
    static ShaderInstance shader() {
        return shader;
    }

    @Nullable
    static Locations locations() {
        return locations;
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            // 属性の場所はバニラのエンティティの形式に合わせて結ぶ(Position・UV0・Normal)。BoneInfo はリンクのときに決まるので引く
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "crowd_entity"), DefaultVertexFormat.NEW_ENTITY),
                    s -> {
                        int id = s.getId();
                        shader = s;
                        locations = new Locations(GL20.glGetAttribLocation(id, "Position"), GL20.glGetAttribLocation(id, "UV0"),
                                GL20.glGetAttribLocation(id, "Normal"), GL20.glGetAttribLocation(id, "BoneInfo"),
                                GL20.glGetUniformLocation(id, "OvCrowdBones"), GL20.glGetUniformLocation(id, "OvCrowdStride"),
                                GL20.glGetUniformLocation(id, "OvCrowdBase"));
                        CrowdDraw.onShaderReloaded();
                    });
        } catch (IOException e) {
            OvergrownVegetation.LOGGER.error("大群のまとめ描きのシェーダーを読み込めなかったので、いつもの描き方のまま", e);
            shader = null;
            locations = null;
            CrowdDraw.onShaderReloaded();
        }
    }
}
