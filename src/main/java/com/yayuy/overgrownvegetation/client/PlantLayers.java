package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.shaders.AbstractUniform;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
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
 * この MOD の植物の層の寄せ(2026-10-23、軽量化の F4)と、下草の距離の段(遠くの下草を地面へ縮めて描かない)。
 *
 * <p><b>cutout → cutout_mipped</b>: 模型の層が cutout のこの MOD のブロックを cutout_mipped の層へ移す(区画の層が 1 つ減る・遠くの草がミップマップでちらつかない)。
 * ただし cutout_mipped の地形シェーダーは「日の当たらない向きの面を暗くする」「葉の色の符号を読む」ので、そのままだと草・シダの見た目が変わる。
 * そこで移した面の頂点の透明度に<b>「薄い植物」の印</b>を足す: 揺れの印(255 − 透明度 = 0〜63、{@link SwayModels})に 64 を足し、
 * 下草(茂みの仲間 {@link BushBlock})はさらに 64 を足す(128〜191)。シェーダー({@code rendertype_cutout_mipped.vsh}・Sodium 用 {@code block_layer_opaque.vsh})は
 * 64 ずつの段を読んで外し、薄い植物は前の cutout と同じ扱い(向きの影なし・葉の色なし)、下草は {@code OvPlantLod} の距離で地面へ縮める。
 * 揺れの印との順はどちらでもよい(後から付く揺れの印は段を残す)。持ち物・手(state が null)には付けない。
 *
 * <p>設定 {@link OvergrownClientConfig.Client#plantMipmaps}(切ると前の cutout の層へ戻す。資源の読み直しが要る)・
 * {@link OvergrownClientConfig.Client#undergrowthDistance}(0 = 縮めない)。
 */
public final class PlantLayers {

    /** 薄い植物(前の cutout)の段。 */
    static final int THIN = 64;
    /** 下草の段(距離で縮める)。 */
    static final int UNDERGROWTH = 128;

    /** 模型を焼いたときに寄せたか(設定を変えたら読み直す)。 */
    private static boolean bakedMipmaps;

    static boolean mipmapsWanted() {
        return !OvergrownClientConfig.SPEC.isLoaded() || OvergrownClientConfig.CLIENT.plantMipmaps.get();
    }

    /** 演出の詳細設定を保存した後。層の寄せを入り切りしたら資源を読み直す。 */
    public static boolean onSettingsSaved() {
        if (mipmapsWanted() != bakedMipmaps) {
            Minecraft.getInstance().reloadResourcePacks();
            return true; // 資源を読み直した(呼んだ側はほかの読み直しを重ねない。10-23)
        }
        return false;
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Models {
        @SubscribeEvent(priority = EventPriority.LOWEST)
        public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
            bakedMipmaps = mipmapsWanted();
            if (!bakedMipmaps) {
                return;
            }
            Map<ModelResourceLocation, BakedModel> models = event.getModels();
            RandomSource rand = RandomSource.create(0L);
            int thin = 0;
            int undergrowth = 0;
            java.util.Set<Block> hangingBlocks = new java.util.HashSet<>();
            for (var h : com.yayuy.overgrownvegetation.registry.ModBlocks.hangingGrowthBlocks()) {
                hangingBlocks.add(h.get());
            }
            for (Block block : BuiltInRegistries.BLOCK) {
                if (!OvergrownVegetation.MODID.equals(BuiltInRegistries.BLOCK.getKey(block).getNamespace())) {
                    continue;
                }
                boolean hanging = hangingBlocks.contains(block);
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    int group = !hanging && isUndergrowth(state) ? UNDERGROWTH : THIN;
                    ModelResourceLocation key = BlockModelShaper.stateToModelLocation(state);
                    BakedModel base = models.get(key);
                    if (base == null || base instanceof Mipped) {
                        continue;
                    }
                    ChunkRenderTypeSet types = base.getRenderTypes(state, rand, ModelData.EMPTY);
                    if (!types.contains(RenderType.cutout())) {
                        continue;
                    }
                    models.put(key, new Mipped(base, types, group));
                    if (group == UNDERGROWTH) {
                        undergrowth++;
                    } else {
                        thin++;
                    }
                }
            }
            OvergrownVegetation.LOGGER.info("cutout から cutout_mipped へ寄せた模型: 薄い植物 {} 状態・下草 {} 状態", thin, undergrowth);
        }

        /**
         * 下草か: 当たり判定が無く、茂みの仲間か草のように置き換えられるもの。壁の蔦(面に張り付く)は遠くでも消すと目立つので外す。
         */
        private static boolean isUndergrowth(BlockState state) {
            Block block = state.getBlock();
            if (block instanceof net.minecraft.world.level.block.MultifaceBlock || block instanceof net.minecraft.world.level.block.VineBlock) {
                return false;
            }
            try {
                if (!state.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO).isEmpty()) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false;
            }
            return block instanceof BushBlock || state.canBeReplaced();
        }

        private Models() {
        }
    }

    /** 層を cutout_mipped と言い、面に段の印を足して返す模型。 */
    static final class Mipped extends BakedModelWrapper<BakedModel> {
        private final ChunkRenderTypeSet types;
        private final boolean alsoMipped;
        private final int group;
        private final Map<BakedQuad, BakedQuad> marked = new ConcurrentHashMap<>();

        Mipped(BakedModel original, ChunkRenderTypeSet originalTypes, int group) {
            super(original);
            List<RenderType> list = new ArrayList<>();
            for (RenderType t : originalTypes) {
                list.add(t == RenderType.cutout() ? RenderType.cutoutMipped() : t);
            }
            this.types = ChunkRenderTypeSet.of(list);
            this.alsoMipped = originalTypes.contains(RenderType.cutoutMipped());
            this.group = group;
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
            return this.types;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
            return mark(state, super.getQuads(state, side, rand));
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData data,
                                        @Nullable RenderType renderType) {
            if (renderType != RenderType.cutoutMipped()) {
                return super.getQuads(state, side, rand, data, renderType);
            }
            // 元の模型には cutout として聞く(層で面を選ぶ模型があるので)。元から cutout_mipped の面があればそれも足す。
            List<BakedQuad> quads = mark(state, super.getQuads(state, side, rand, data, RenderType.cutout()));
            if (!this.alsoMipped) {
                return quads;
            }
            List<BakedQuad> all = new ArrayList<>(quads);
            all.addAll(super.getQuads(state, side, rand, data, renderType));
            return all;
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
                out.add(this.marked.computeIfAbsent(quad, q -> addGroup(q, this.group)));
            }
            return out;
        }
    }

    /** 面の 4 頂点の印(255 − 透明度)に段を足した写し。すでに段があれば足さない。 */
    static BakedQuad addGroup(BakedQuad quad, int group) {
        int[] v = quad.getVertices().clone();
        int stride = v.length / 4;
        for (int i = 0; i < 4; i++) {
            int at = i * stride;
            int code = 255 - (v[at + 3] >>> 24);
            if (code >= THIN) {
                continue;
            }
            int alpha = 255 - (code + group);
            v[at + 3] = (v[at + 3] & 0x00FFFFFF) | (alpha << 24);
        }
        return new BakedQuad(v, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }

    /** 揺れの印を書くとき、先に付いていた段を残す({@link SwayModels#markQuad})。 */
    static int groupOf(int alphaByte) {
        int code = 255 - alphaByte;
        return code >= UNDERGROWTH ? UNDERGROWTH : (code >= THIN ? THIN : 0);
    }

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
    public static final class Uniforms {
        /** 下草の縮め方を葉の層のシェーダーへ渡す: OvPlantLod = (縮み始め, 消える距離, 0, 0)。消える距離 0 = 縮めない。 */
        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
                return;
            }
            ShaderInstance shader = GameRenderer.getRendertypeCutoutMippedShader();
            if (shader == null) {
                return;
            }
            int end = OvergrownClientConfig.SPEC.isLoaded() ? OvergrownClientConfig.CLIENT.undergrowthDistance.get() : 0;
            AbstractUniform u = shader.safeGetUniform("OvPlantLod");
            u.set(end * 0.5F, (float) end, 0.0F, 0.0F);
        }

        private Uniforms() {
        }
    }

    private PlantLayers() {
    }
}
