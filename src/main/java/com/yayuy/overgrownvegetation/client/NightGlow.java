package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.shaders.AbstractUniform;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 夜だけ灯る面の発光(2026-10-24、ユーザー決定「昼は弱く(消さない)、夕方から灯り夜に最大」)。
 *
 * <p>模型の面の発光({@code neoforge_data.block_light})は焼いた頂点の明るさなので、そのままでは昼も夜も同じに光る。
 * そこで地脈の結晶・エネルギー結晶・地脈の花・胞子嚢の<b>焼いた明るさを持つ面</b>の頂点の透明度に印「発光の面」を付け
 * (255 − 透明度 = 192 + 揺れの印。{@link PlantLayers} の 64 ずつの段の 4 つ目)、地形のシェーダー
 * ({@code rendertype_cutout_mipped.vsh}・Sodium 用 {@code block_layer_opaque.vsh}、{@code ov_plant.glsl} の {@code ovGlowLight})が
 * その面の明るさ(ライトマップの色)を {@code OvGlow.x} 倍する。ただし同じ頂点の空の光だけの明るさより暗くはしない。
 * ブロックの光の段を下げると、ライトマップの中ほどの暖かい色で結晶が茶色く濁ったので(10-24 の写真)、色ごと掛ける。模型の焼き直しは要らない。
 *
 * <p>印を読むのは cutout_mipped の層なので、設定「植物のミップマップ」が切れているとき(結晶が cutout の層に残る)は印を付けない
 * = 一日中同じに光る。Iris のシェーダーパック中は地形のシェーダーが替わるので効かず、焼いた明るさのまま(ユーザー了承済み)。
 * 面の明るさは「焼いた明るさ」と「周りの明るさ」の大きい方なので、昼に弱めると隣の松明の光も同じだけ弱まる(結晶の面だけ。空の光は弱めない)。
 */
public final class NightGlow {
    /** 発光の面の段(255 − 透明度の 192〜255)。 */
    static final int GLOW = 192;
    /** 昼の明るさの倍率(仮決め)。夜は 1。 */
    static final float DAY_FACTOR = 0.5F;
    /** 開発用の口(devbridge の nightglow)。false で倍率を 1 に固定 = 前と同じ見た目。 */
    public static volatile boolean devEnabled = true;

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Models {
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
            if (!PlantLayers.mipmapsWanted()) {
                return;
            }
            Block[] blocks = {ModBlocks.BUDDING_LEY_CRYSTAL.get(), ModBlocks.SMALL_LEY_CRYSTAL_BUD.get(), ModBlocks.MEDIUM_LEY_CRYSTAL_BUD.get(),
                    ModBlocks.LARGE_LEY_CRYSTAL_BUD.get(), ModBlocks.LEY_CRYSTAL.get(), ModBlocks.ENERGY_CRYSTAL.get(),
                    ModBlocks.LEY_FLOWER.get(), ModBlocks.CAVE_SPORE_SAC.get()};
            Map<ModelResourceLocation, BakedModel> models = event.getModels();
            int states = 0;
            for (Block block : blocks) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    ModelResourceLocation key = BlockModelShaper.stateToModelLocation(state);
                    BakedModel base = models.get(key);
                    if (base != null) {
                        models.put(key, new Marked(base));
                        states++;
                    }
                }
            }
            OvergrownVegetation.LOGGER.info("夜だけ灯る面の印を付けた模型: {} 状態", states);
        }

        private Models() {
        }
    }

    /** 焼いた明るさを持つ面に「発光の面」の印を付けて返す模型。 */
    static final class Marked extends BakedModelWrapper<BakedModel> {
        private final Map<BakedQuad, BakedQuad> marked = new ConcurrentHashMap<>();

        Marked(BakedModel original) {
            super(original);
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            return mark(state, super.getQuads(state, side, rand));
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData data,
                                        @Nullable RenderType renderType) {
            return mark(state, super.getQuads(state, side, rand, data, renderType));
        }

        private List<BakedQuad> mark(@Nullable BlockState state, List<BakedQuad> quads) {
            if (state == null || quads.isEmpty()) {
                return quads;
            }
            if (this.marked.size() > 4096) {
                this.marked.clear();
            }
            List<BakedQuad> out = new ArrayList<>(quads.size());
            for (BakedQuad quad : quads) {
                out.add(this.marked.computeIfAbsent(quad, NightGlow::markGlow));
            }
            return out;
        }
    }

    /** 焼いた明るさ(頂点の UV2 のブロックの光)を持つ面なら、4 頂点の段を「発光の面」に替えた写しを返す。揺れの印(下の 6 ビット)は残す。 */
    static BakedQuad markGlow(BakedQuad quad) {
        int[] v = quad.getVertices();
        int stride = v.length / 4;
        boolean emissive = false;
        for (int i = 0; i < 4; i++) {
            if ((v[i * stride + 6] & 0xFFFF) > 0) {
                emissive = true;
                break;
            }
        }
        if (!emissive) {
            return quad;
        }
        int[] w = v.clone();
        for (int i = 0; i < 4; i++) {
            int at = i * stride + 3;
            int code = 255 - (w[at] >>> 24);
            int alpha = 255 - (GLOW + (code & 63));
            w[at] = (w[at] & 0x00FFFFFF) | (alpha << 24);
        }
        return new BakedQuad(w, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }

    /** 今の発光の倍率: 昼 {@link #DAY_FACTOR}、夕方から上がり夜 1。空の無い世界(ネザー・エンド)は 1。 */
    static float glowFactor(@Nullable ClientLevel level, float partialTick) {
        if (!devEnabled || level == null || !level.dimensionType().hasSkyLight()) {
            return 1.0F;
        }
        // getSkyDarken は夜 0.2・昼 1(雨・雷で下がる)。昼の強さ 0〜1 にして、夕方(0.75 → 0.15)の間で灯す
        float daySky = Mth.clamp((level.getSkyDarken(partialTick) - 0.2F) / 0.8F, 0.0F, 1.0F);
        float t = Mth.clamp((daySky - 0.15F) / 0.6F, 0.0F, 1.0F);
        float day = t * t * (3.0F - 2.0F * t);
        return Mth.lerp(day, 1.0F, DAY_FACTOR);
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
    public static final class Uniforms {
        /** OvGlow = (発光の面の明るさの倍率, 0, 0, 0)。Sodium へは SodiumTerrain が名前で写す。 */
        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
                return;
            }
            ShaderInstance shader = GameRenderer.getRendertypeCutoutMippedShader();
            if (shader == null) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            float k = glowFactor(mc.level, event.getPartialTick().getGameTimeDeltaPartialTick(false));
            AbstractUniform u = shader.safeGetUniform("OvGlow");
            u.set(k, 0.0F, 0.0F, 0.0F);
        }

        private Uniforms() {
        }
    }

    private NightGlow() {
    }
}
