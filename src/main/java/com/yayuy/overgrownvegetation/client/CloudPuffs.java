package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.CloudPuffPayload;
import com.yayuy.overgrownvegetation.sky.CloudField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 吐いた雲のクライアント側(2026-10-19。空の巨大生物が雲を吐いて身を隠す技、サーバーは {@link com.yayuy.overgrownvegetation.sky.CloudPuffs})。
 *
 * <ul>
 *   <li>形: {@link CloudField#blob}(サーバーが送った位置と seed から同じ式で)を箱で詰めた面({@link VoxelClouds#buildPuffMesh}、別スレッド)。箱の大きさは
 *       横の半径の 1/32(16〜48 ブロック)。ボクセルの雲と同じシェーダーで、近く・遠くの両方の描画の中で描く(雲の場の座標なので風で流れる)。</li>
 *   <li>膨らみ・消え方: {@link com.yayuy.overgrownvegetation.sky.CloudPuffs#state}(サーバーと同じ式。既定は 80 tick で 0.12 → 1 倍、終わりの 400 tick で薄れながら
 *       0.9 倍。10-19 から吐いた側が長さを渡せる)。薄れている間は奥行きを書かない(奥の雲を隠さない)。</li>
 *   <li>雲の中: 中に入ると積乱雲と同じ霧({@link CloudInterior#solidAt} が {@link #inside} を引く。薄れて 3 割を切ったら外)。</li>
 *   <li>積乱雲と同じく遠くまで消さない(描く間だけ積乱雲の消える距離を遠くへ)。雲の影・雷・空の気流には入れない。</li>
 * </ul>
 * 数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CloudPuffs {
    private static final class Entry {
        CloudPuffPayload p;
        List<CloudField.Puff> puffs;
        int originX;
        int originZ;
        float centerX;
        float centerY;
        float centerZ;
        CompletableFuture<VoxelClouds.PuffMesh> pending;
        VertexBuffer vbo;
        boolean failed;
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static ClientLevel lastLevel;

    /** サーバーから吐いた雲が来た。形を別スレッドで作り始める。 */
    public static void onPayload(CloudPuffPayload p) {
        for (Entry e : ENTRIES) {
            if (e.p.id() == p.id() && e.p.start() == p.start()) {
                return;
            }
        }
        Entry e = new Entry();
        e.p = p;
        e.puffs = com.yayuy.overgrownvegetation.sky.CloudPuffs.shape(p);
        com.yayuy.overgrownvegetation.sky.CloudPuffs.clientAdd(p);
        int v = Mth.clamp(Math.round(p.radius() / 32.0F / 8.0F) * 8, 16, 48);
        double reach = p.radius() * 1.45;
        int x0 = Mth.floor((p.fx() - reach) / v);
        int z0 = Mth.floor((p.fz() - reach) / v);
        int n = Mth.ceil(2.0 * reach / v) + 1;
        int ny = Math.max(1, Mth.ceil((p.y() + p.height() * 0.7 - CloudField.LOW_BASE) / v) + 1);
        e.originX = Mth.floor(p.fx() / CloudField.CELL_XZ);
        e.originZ = Mth.floor(p.fz() / CloudField.CELL_XZ);
        e.centerX = (float) (p.fx() - e.originX * (double) CloudField.CELL_XZ);
        e.centerY = (float) (p.y() - CloudField.LOW_BASE);
        e.centerZ = (float) (p.fz() - e.originZ * (double) CloudField.CELL_XZ);
        List<CloudField.Puff> puffs = e.puffs;
        int ox = e.originX;
        int oz = e.originZ;
        e.pending = CompletableFuture.supplyAsync(() -> VoxelClouds.buildPuffMesh(puffs, ox, oz, x0, z0, n, ny, v), CloudThread.EXECUTOR);
        ENTRIES.add(e);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            clear();
        }
        if (mc.level == null || ENTRIES.isEmpty()) {
            return;
        }
        long now = mc.level.getGameTime();
        com.yayuy.overgrownvegetation.sky.CloudPuffs.clientPrune(now);
        for (Iterator<Entry> it = ENTRIES.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (now > e.p.start() + e.p.life() + 20) {
                close(e);
                it.remove();
            }
        }
    }

    private static void clear() {
        for (Entry e : ENTRIES) {
            close(e);
        }
        ENTRIES.clear();
        com.yayuy.overgrownvegetation.sky.CloudPuffs.clientClear();
    }

    private static void close(Entry e) {
        if (e.vbo != null) {
            e.vbo.close();
            e.vbo = null;
        }
        if (e.pending != null) {
            e.pending.thenAccept(m -> {
                if (m != null) {
                    m.bytes().close();
                }
            });
            e.pending = null;
        }
    }

    /** 膨らみ(大きさの倍率)と濃さ(0〜1)。t = 吐いてからの tick(サーバーと同じ式)。 */
    static float[] state(CloudPuffPayload p, double t) {
        return com.yayuy.overgrownvegetation.sky.CloudPuffs.state(p, t);
    }

    /** 場の座標 (fx, y, fz) が吐いた雲の中か(薄れて 3 割を切ったら外)。 */
    static boolean inside(double fx, double y, double fz) {
        Minecraft mc = Minecraft.getInstance();
        if (ENTRIES.isEmpty() || mc.level == null) {
            return false;
        }
        return com.yayuy.overgrownvegetation.sky.CloudPuffs.inside(true, mc.level.getGameTime(), fx, y, fz);
    }

    /** ボクセルの雲の描画の中から(近く・遠くのどちらも)。StormFade は描いた後に fadeNow へ戻す。 */
    static void draw(ShaderInstance shader, Matrix4f modelView, Matrix4f projection, double fx, double fz, double dy, float[] fadeNow) {
        Minecraft mc = Minecraft.getInstance();
        if (ENTRIES.isEmpty() || mc.level == null) {
            return;
        }
        double now = mc.level.getGameTime() + (double) mc.getTimer().getGameTimeDeltaPartialTick(false);
        boolean any = false;
        for (Entry e : ENTRIES) {
            if (e.vbo == null && !e.failed && e.pending != null && e.pending.isDone()) {
                VoxelClouds.PuffMesh m = e.pending.join();
                e.pending = null;
                if (m == null) {
                    e.failed = true;
                } else {
                    try {
                        e.vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                        e.vbo.bind();
                        e.vbo.upload(m.mesh());
                        VertexBuffer.unbind();
                    } finally {
                        m.bytes().close();
                    }
                }
            }
            double t = now - e.p.start();
            if (e.vbo == null || t < 0.0 || t > e.p.life()) {
                continue;
            }
            float[] st = state(e.p, t);
            if (st[1] <= 0.003F) {
                continue;
            }
            if (!any) {
                any = true;
                shader.safeGetUniform("StormFade").set(40000.0F, 40100.0F, 40000.0F, 40100.0F);
            }
            shader.safeGetUniform("Offset").set((float) (e.originX * (double) CloudField.CELL_XZ - fx), (float) dy,
                    (float) (e.originZ * (double) CloudField.CELL_XZ - fz));
            shader.safeGetUniform("Puff").set(st[0], st[1], 0.0F, 1.0F);
            shader.safeGetUniform("PuffCenter").set(e.centerX, e.centerY, e.centerZ);
            e.vbo.bind();
            e.vbo.drawWithShader(modelView, projection, shader);
        }
        if (any) {
            shader.safeGetUniform("Puff").set(1.0F, 1.0F, 0.0F, 0.0F);
            shader.safeGetUniform("StormFade").set(fadeNow[0], fadeNow[1], fadeNow[2], fadeNow[3]);
        }
    }

    /** 開発用: 今ある吐いた雲の数。 */
    static int count() {
        return ENTRIES.size();
    }

    private CloudPuffs() {
    }
}
