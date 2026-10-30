package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;

/**
 * 汚染地域の<b>林冠の影</b>(2026-10-15 ユーザー「林冠の下に薄い影を落として、木漏れ日を際立たせてください」)。
 *
 * <p>Minecraft では葉の下の地面もほとんど暗くならない(空の光は葉 1 枚で 1 しか減らない)ので、足し算の木漏れ日
 * ({@link CanopyDapple})は明るい地面の上で目立たなかった。そこで、地面から太陽の方へたどって<b>じかに日が当たらない
 * 地面</b>(葉・幹を通る)の上面に、掛け算で暗くする板を重ねる。木漏れ日はその上に足すので、影の中の日だまりになる。
 * 光の筋の落ちる所(何も通らない)は暗くしない。幹の影も落ちる。
 *
 * <ul>
 *     <li>プレイヤーの周り {@link #RADIUS} ブロックの地面を、毎 tick {@link #SCAN_PER_TICK} マスずつ順に見直す(1 周約 1.4 秒)。</li>
 *     <li>影の濃さは見直しのたびに急に変えず、毎 tick 少しずつ寄せる(太陽が動いても影の縁が飛ばない)。</li>
 *     <li>板の角は周りのマス(同じ高さ)の濃さの平均にするので、影の縁はぼやける。</li>
 *     <li>影の色は {@link CanopySunMap#shadeColor}(75% の明るさ。0.35 だけ暗くすると林冠が閉じた森で暗すぎた。逆光で 58%、2026-10-16 から青緑)。昼だけ・遠くは薄れる。下草(当たり判定の無い植物)は暗くならない。</li>
 * </ul>
 *
 * <p>描くのは {@link CanopyDapple} から(木漏れ日より先に描く必要があるので、自分ではイベントを受けない)。
 */
final class CanopyShade {

    static final int RADIUS = 18;
    private static final int SCAN_PER_TICK = 48;

    private static final Long2ObjectOpenHashMap<Cell> CELLS = new Long2ObjectOpenHashMap<>();
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

    static void tick(ClientLevel level, LocalPlayer player, Vec3 sun, float day) {
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
            float ground = CanopyDapple.findGround(level, x, py, z);
            if (Float.isNaN(ground) || !ModBiomes.isOvergrownLike(level.getBiome(new BlockPos(x, Mth.floor(ground), z)))) {
                Cell gone = CELLS.get(key);
                if (gone != null) {
                    gone.target = 0.0F;
                }
                continue;
            }
            int leaves = CanopyDapple.leavesOnSunPath(level, x, ground, z, sun);
            Cell c = CELLS.get(key);
            if (c == null) {
                if (leaves == 0) {
                    continue;
                }
                c = new Cell(x, z, ground);
                CELLS.put(key, c);
            }
            c.y = ground;
            c.target = leaves == 0 ? 0.0F : 1.0F;
        }
    }

    /** 影の板を描いて流す(呼び手は {@link CanopyDapple#render})。{@code amount} は昼の強さ × 設定の明るさ。 */
    static void render(net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers, Vec3 eye, float amount, float far) {
        if (CELLS.isEmpty() || amount <= 0.0F) {
            return;
        }
        VertexConsumer c = buffers.getBuffer(renderType());
        // 影の色(全部影のときの掛け算)。逆光で深く・青緑へ寄せる。シェーダーの影と同じ色({@link CanopySunMap#shadeColor})。
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
            float fade = 1.0F - CanopyLight.smooth(Mth.clamp((dist - far * 0.6F) / (far * 0.4F), 0.0F, 1.0F));
            float k = amount * fade;
            if (k * kr <= 0.003F) {
                continue;
            }
            // 角ごとの濃さ = その角を囲む 4 マス(同じ高さ)の平均。
            float v00 = corner(cell, -1, -1);
            float v10 = corner(cell, 0, -1);
            float v11 = corner(cell, 0, 0);
            float v01 = corner(cell, -1, 0);
            vertex(c, x0, y, z0, k * v00, kr, kg, kb);
            vertex(c, x0, y, z0 + 1.0F, k * v01, kr, kg, kb);
            vertex(c, x0 + 1.0F, y, z0 + 1.0F, k * v11, kr, kg, kb);
            vertex(c, x0 + 1.0F, y, z0, k * v10, kr, kg, kb);
        }
        buffers.endBatch(renderType());
    }

    /** 影の濃さ {@code s}(0〜1)の頂点。色ごとに 1 − 濃さ × (1 − 影の色)。 */
    private static void vertex(VertexConsumer c, float x, float y, float z, float s, float kr, float kg, float kb) {
        c.addVertex(x, y, z).setColor(1.0F - s * kr, 1.0F - s * kg, 1.0F - s * kb, 1.0F);
    }

    private static final float[] RGB = new float[3];

    /** 角を囲む 4 マス((x + ox, z + oz) から 2×2)のうち、同じ高さのマスの濃さの平均。ox, oz は −1 か 0。 */
    private static float corner(Cell cell, int ox, int oz) {
        float sum = 0.0F;
        int n = 0;
        for (int ix = 0; ix < 2; ix++) {
            for (int iz = 0; iz < 2; iz++) {
                Cell other = CELLS.get(BlockPos.asLong(cell.x + ox + ix, 0, cell.z + oz + iz));
                if (other == null) {
                    // まだ見ていない・日が当たっていて記録の無いマスは影なし。
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
                            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                            .setTransparencyState(multiply)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));
        }
        return renderType;
    }

    private CanopyShade() {
    }
}
