package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.shader.PortRender;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;

import java.util.Iterator;

/**
 * <b>地面の上面だけの林冠の影</b>(2026-10-15 ユーザー「林冠の下に薄い影を落として、木漏れ日を際立たせて」、10-30 作り直し。元は parked の {@code client/CanopyShade})。
 * 地形のシェーダーで全部の面に影を落とせない間({@link CanopySunMap#active()} が false: Sodium の差し込みが使えない・Embeddium・シェーダーパック使用中で
 * 演出を描く設定)だけ描く代わり。地面から太陽の方へたどって葉・幹を通る地面の上面に、掛け算で暗くする板を重ねる(色は {@link CanopySunMap#shadeColor})。
 *
 * <p>プレイヤーの周り {@link #RADIUS} ブロックを毎 tick {@link #SCAN_PER_TICK} マスずつ見直し(1 周約 1.4 秒)、影の濃さは毎 tick 少しずつ寄せる。
 * 板の角は周りのマスの濃さの平均にするので、影の縁はぼやける。強さは昼の強さ × 森の中で空の下にいる度合い。
 */
final class CanopyShade {

    static final int RADIUS = 18;
    private static final int SCAN_PER_TICK = 48;

    private static final Long2ObjectOpenHashMap<Cell> CELLS = new Long2ObjectOpenHashMap<>();
    private static final float[] RGB = new float[3];
    private static int cursor;
    private static RenderType renderType;

    private static final class Cell {
        final int x;
        final int z;
        float y;
        float target;
        float value;

        Cell(int x, int z, float y) {
            this.x = x;
            this.z = z;
            this.y = y;
        }
    }

    static void clear() {
        CELLS.clear();
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.canopyLight()) {
            clear();
            return;
        }
        if (CanopySunMap.active() || ForestPresence.openCover() <= 0.001F) {
            CELLS.clear();
            return;
        }
        Vec3 sun = SunLight.sunDirection(level, 1.0F);
        float day = SunLight.daylight(level, sun, 1.0F);
        int px = Mth.floor(player.getX());
        int pz = Mth.floor(player.getZ());
        int py = Mth.floor(player.getY());
        int side = RADIUS * 2 + 1;
        for (Iterator<Cell> it = CELLS.values().iterator(); it.hasNext(); ) {
            Cell c = it.next();
            if (Math.abs(c.x - px) > RADIUS + 2 || Math.abs(c.z - pz) > RADIUS + 2) {
                it.remove();
                continue;
            }
            c.value += (c.target - c.value) * 0.08F;
        }
        if (day <= 0.0F) {
            return;
        }
        for (int i = 0; i < SCAN_PER_TICK; i++) {
            // 格子を飛び飛びの順で回す(近い所から順に偏らないように、素数の歩幅で)。
            cursor = (cursor + 977) % (side * side);
            int x = px - RADIUS + cursor % side;
            int z = pz - RADIUS + cursor / side;
            long key = BlockPos.asLong(x, 0, z);
            float ground = SunTrace.findGround(level, x, py, z);
            if (Float.isNaN(ground)) { // 11-06 から世界全体(バイオームを問わない)
                Cell gone = CELLS.get(key);
                if (gone != null) {
                    gone.target = 0.0F;
                }
                continue;
            }
            int layers = SunTrace.leafLayersOnSunPath(level, x, ground, z, sun, CanopyDapple.MAX_LAYERS);
            Cell c = CELLS.get(key);
            if (c == null) {
                if (layers == 0) {
                    continue;
                }
                c = new Cell(x, z, ground);
                CELLS.put(key, c);
            }
            c.y = ground;
            c.target = layers == 0 ? 0.0F : 1.0F;
        }
    }

    static void render(RenderLevelStageEvent event) {
        if (!VisualQuality.canopyLight()) {
            return;
        }
        ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        if (CELLS.isEmpty() || level == null) {
            return;
        }
        float partialTick = event.getPartialTick();
        Vec3 sun = SunLight.sunDirection(level, partialTick);
        float amount = SunLight.daylight(level, sun, partialTick) * Math.min(1.0F, DesignData.atmosphere().intensity())
                * ForestPresence.openCover();
        if (amount <= 0.0F) {
            return;
        }
        Vec3 eye = event.getCamera().getPosition();
        float far = Math.max(12.0F, Math.min(RenderSystem.getShaderFogEnd(), RADIUS + 4.0F));
        MultiBufferSource.BufferSource buffers = AddLight.buffers();
        VertexConsumer c = buffers.getBuffer(renderType());
        CanopySunMap.shadeColor(RGB);
        float kr = 1.0F - RGB[0];
        float kg = 1.0F - RGB[1];
        float kb = 1.0F - RGB[2];
        for (Cell cell : CELLS.values()) {
            if (cell.value < 0.01F && !anyNeighbourShaded(cell)) {
                continue;
            }
            float x0 = (float) (cell.x - eye.x);
            float z0 = (float) (cell.z - eye.z);
            float y = (float) (cell.y + 0.01 - eye.y);
            double dx = x0 + 0.5;
            double dz = z0 + 0.5;
            float dist = (float) Math.sqrt(dx * dx + y * y + dz * dz);
            float fade = 1.0F - SunLight.smooth(Mth.clamp((dist - far * 0.6F) / (far * 0.4F), 0.0F, 1.0F));
            float k = amount * fade;
            if (k * kr <= 0.003F) {
                continue;
            }
            vertex(c, x0, y, z0, k * corner(cell, -1, -1), kr, kg, kb);
            vertex(c, x0, y, z0 + 1.0F, k * corner(cell, -1, 0), kr, kg, kb);
            vertex(c, x0 + 1.0F, y, z0 + 1.0F, k * corner(cell, 0, 0), kr, kg, kb);
            vertex(c, x0 + 1.0F, y, z0, k * corner(cell, 0, -1), kr, kg, kb);
        }
        buffers.endBatch(renderType());
    }

    /** 影の濃さ {@code s}(0〜1)の頂点。色ごとに 1 − 濃さ × (1 − 影の色)。 */
    private static void vertex(VertexConsumer c, float x, float y, float z, float s, float kr, float kg, float kb) {
        c.vertex(x, y, z).color(1.0F - s * kr, 1.0F - s * kg, 1.0F - s * kb, 1.0F).endVertex();
    }

    /** 角を囲む 4 マス((x + ox, z + oz) から 2×2)のうち、同じ高さのマスの濃さの平均。まだ見ていない・日が当たるマスは影なし。 */
    private static float corner(Cell cell, int ox, int oz) {
        float sum = 0.0F;
        int n = 0;
        for (int ix = 0; ix < 2; ix++) {
            for (int iz = 0; iz < 2; iz++) {
                Cell other = CELLS.get(BlockPos.asLong(cell.x + ox + ix, 0, cell.z + oz + iz));
                if (other == null) {
                    n++;
                    continue;
                }
                if (other.y == cell.y) {
                    sum += other.value;
                    n++;
                }
            }
        }
        return n == 0 ? cell.value : sum / n;
    }

    private static boolean anyNeighbourShaded(Cell cell) {
        for (int ix = -1; ix <= 1; ix++) {
            for (int iz = -1; iz <= 1; iz++) {
                Cell other = CELLS.get(BlockPos.asLong(cell.x + ix, 0, cell.z + iz));
                if (other != null && other.value >= 0.01F) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 掛け算(下の色 × 板の色)の型。深さは読むが書かない。地面とちらつかないよう少し手前へずらす。 */
    private static RenderType renderType() {
        if (renderType == null) {
            RenderStateShard.TransparencyStateShard multiply = new RenderStateShard.TransparencyStateShard(
                    OvergrownVegetation.MODID + "_multiply",
                    () -> {
                        RenderSystem.enableBlend();
                        RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
                    },
                    () -> {
                        RenderSystem.disableBlend();
                        RenderSystem.defaultBlendFunc();
                    });
            renderType = RenderType.create(
                    OvergrownVegetation.MODID + "_canopy_shade",
                    DefaultVertexFormat.POSITION_COLOR,
                    VertexFormat.Mode.QUADS,
                    65536,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(PortRender.Shards.POSITION_COLOR_SHADER)
                            .setTransparencyState(multiply)
                            .setCullState(PortRender.Shards.NO_CULL)
                            .setLayeringState(PortRender.Shards.VIEW_OFFSET_Z_LAYERING)
                            .setWriteMaskState(PortRender.Shards.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return renderType;
    }

    private CanopyShade() {
    }
}
