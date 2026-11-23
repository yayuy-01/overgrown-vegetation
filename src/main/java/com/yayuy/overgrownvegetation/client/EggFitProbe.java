package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.EggPreviewPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 確かめ用: スポーンエッグの {@code eggFit} を、実際の描画から測る(2026-11-16。G1 の作り直しで体から植物が生え、古い値では枠からあふれた)。
 * {@link SpawnEggEntityItemRenderer} と同じ作りの描画用 Mob を、頂点を覗くだけのバッファへ描き、
 * {@link SpawnEggEntityItemRenderer#fitSizeOf} の式(画面X・画面Y の中心からの最大 × 2)を数 tick ぶん取って最大を出す。
 * DevBridge の {@code eggfit [名前の一部] [tick 数]} から呼ぶ。
 */
public final class EggFitProbe {

    private static final float YAW = -25.0F;
    private static final float COS15 = (float) Math.cos(Math.toRadians(15.0)), SIN15 = (float) Math.sin(Math.toRadians(15.0));

    private record Probe(String id, Entity entity, float halfHeight, float[] max) {
    }

    private static final List<Probe> PROBES = new ArrayList<>();
    private static int ticksLeft;
    private static Consumer<String> out;

    private EggFitProbe() {
    }

    /** 測り始める。{@code filter} = id に含む文字(空で全部)。結果は {@code ticks} tick 後に 1 行ずつ返す。 */
    public static String start(String filter, int ticks, Consumer<String> log) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return "EGGFIT ワールドの外";
        }
        PROBES.clear();
        Map<String, EntityType<?>> types = new LinkedHashMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item instanceof SpawnEggItem egg) {
                EntityType<?> type = egg.getType(null);
                ResourceLocation id = EntityType.getKey(type);
                if (id.getNamespace().equals(OvergrownVegetation.MODID) && id.getPath().contains(filter)) {
                    types.put(id.getPath(), type);
                }
            }
        }
        for (Map.Entry<String, EntityType<?>> e : types.entrySet()) {
            try {
                Entity preview = e.getValue().create(mc.level);
                if (preview == null) {
                    continue;
                }
                preview.setPos(0.0D, 0.0D, 0.0D);
                preview.setYRot(YAW);
                preview.setXRot(0.0F);
                preview.setOldPosAndRot();
                float scale = 1.0F;
                if (preview instanceof LivingEntity living) {
                    living.yBodyRot = YAW;
                    living.yBodyRotO = YAW;
                    living.yHeadRot = YAW;
                    living.yHeadRotO = YAW;
                    scale = living.getScale();
                }
                if (preview instanceof EggPreviewPose pose) {
                    pose.prepareEggPreview();
                }
                PROBES.add(new Probe(e.getKey(), preview, e.getValue().getDimensions().height * scale * 0.5F, new float[1]));
            } catch (Exception ex) {
                log.accept("EGGFIT 作れない " + e.getKey() + " " + ex);
            }
        }
        ticksLeft = Math.max(1, ticks);
        out = log;
        return "EGGFIT 測り始め " + PROBES.size() + " 種 " + ticksLeft + " tick";
    }

    /** クライアントの tick ごとに呼ぶ(測っている間だけ動く)。 */
    public static void tick() {
        if (ticksLeft <= 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.getEntityRenderDispatcher().camera == null) {
            return;
        }
        var dispatcher = mc.getEntityRenderDispatcher();
        dispatcher.setRenderShadow(false);
        for (Probe p : PROBES) {
            p.entity.tickCount = (int) mc.level.getGameTime();
            Tap tap = new Tap(p);
            try {
                dispatcher.render(p.entity, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F, new PoseStack(), tap, 0xF000F0);
            } catch (Exception ex) {
                // 描けない種は 0 のまま返す
            }
        }
        dispatcher.setRenderShadow(true);
        if (--ticksLeft == 0) {
            for (Probe p : PROBES) {
                out.accept(String.format(java.util.Locale.ROOT, "EGGFIT %s %.2f (今 %.2f)", p.id, p.max[0],
                        SpawnEggEntityItemRenderer.fitSizeOf(p.entity.getType(), -1.0F)));
            }
            out.accept("EGGFIT 終わり");
            PROBES.clear();
        }
    }

    /** 頂点の位置だけを覗くバッファ。 */
    private static final class Tap implements MultiBufferSource, VertexConsumer {
        private final Probe probe;

        Tap(Probe probe) {
            this.probe = probe;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            return this;
        }

        @Override
        public VertexConsumer vertex(double dx, double dy, double dz) {
            float x = (float) dx, y = (float) dy, z = (float) dz;
            float sy = (y - probe.halfHeight) * COS15 - z * SIN15;
            float v = 2.0F * Math.max(Math.abs(x), Math.abs(sy));
            if (v > probe.max[0]) {
                probe.max[0] = v;
            }
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }
}
