package com.yayuy.overgrownvegetation.client.crowd;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.horde.ClientVirtualHordes;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.horde.VirtualHordeLayout;
import com.yayuy.overgrownvegetation.network.VirtualHordePayload;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 仮想の群れ(大群の軽量化 A の {@link ClientVirtualHordes})を、遠くの身代わり({@link HordeImpostors})で描く(2026-11-18 大群の軽量化 C)。
 *
 * <p>一員の位置はサーバーの本物化と同じ式({@link VirtualHordeLayout#offset})なので、身代わりが消えた所に本物が出る。
 * 本物になる距離(perf.json の virtualHorde.materializeBlocks【仮】64)より近い一員と、描画距離より遠い一員は描かない。</p>
 *
 * <p>足元の高さ: 読み込まれた所では、群れの高さ ± 数ブロックで木(幹・葉)でない地面を探す(過繁茂の森で梢の上を歩かないように。
 * parked の遠景の身代わりで踏んだ罠)。読み込まれていない所は群れの高さ(サーバーが葉を除いて測った値)。高さと明るさは一員ごとに覚え、
 * 1.5 ブロック動くか 40 tick 経つまで引き直さない。</p>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class VirtualHordeDraw {

    /** 確かめ用の切り替え(devbridge の {@code crowd virtual off|on})。 */
    public static volatile boolean enabled = true;

    private static final int UP = 6, DOWN = 12;

    /** 一員ごとの足元の高さと明るさ(キー = 群れの id × 4096 + 番号)。 */
    private record Ground(double x, double z, double y, int light, long at) {
    }

    private static final Long2ObjectOpenHashMap<Ground> GROUND = new Long2ObjectOpenHashMap<>();
    private static long lastPurge;

    private VirtualHordeDraw() {
    }

    // まとめ描き(CrowdDraw、HIGHEST)の後
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || !enabled) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        var swarms = ClientVirtualHordes.view();
        if (swarms.isEmpty()) {
            if (!GROUND.isEmpty()) GROUND.clear();
            return;
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        Vec3 player = mc.player.getPosition(pt);
        // サーバーは観戦モードのプレイヤーのそばでは本物にしない(horde/VirtualHordes#materialize)。観戦中は近くも身代わりで描く
        double near = mc.player.isSpectator() ? 0.0D : DesignData.perf().virtualMaterializeBlocks;
        double far = mc.options.getEffectiveRenderDistance() * 16.0D;
        long now = level.getGameTime();
        HordeImpostors.begin(cam);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (ClientVirtualHordes.Entry e : swarms) {
            VirtualHordePayload.Swarm s = e.swarm();
            double cx = ClientVirtualHordes.x(e, pt), cz = ClientVirtualHordes.z(e, pt);
            boolean moving = s.speed() > 0.01F;
            float heading = (float) Math.toDegrees(Math.atan2(-s.dirX(), s.dirZ()));
            int index = 0;
            for (VirtualHordePayload.Count c : s.counts()) {
                for (int k = 0; k < c.n(); k++, index++) {
                    double[] off = VirtualHordeLayout.offset(s.id(), index, c.type());
                    double x = cx + off[0], z = cz + off[1];
                    double hx = x - player.x, hz = z - player.z;
                    double h2 = hx * hx + hz * hz;
                    if (h2 < near * near || h2 > far * far) continue;
                    long key = s.id() * 4096L + index;
                    Ground g = GROUND.get(key);
                    if (g == null || Math.abs(g.x - x) + Math.abs(g.z - z) > 1.5D || now - g.at > 40L) {
                        g = ground(level, pos, x, s.y(), z, now);
                        GROUND.put(key, g);
                    }
                    long h = s.id() * 31L + index * 0x9E3779B97F4A7C15L;
                    float jitter = ((h >>> 20) & 1023) / 1023.0F * 40.0F - 20.0F;
                    float yaw = moving ? heading + jitter : jitter * 9.0F;
                    HordeImpostors.add(c.type(), x, g.y, z, yaw, HordePoses.slot(moving, s.id(), index), g.light);
                }
            }
        }
        if (now - lastPurge > 200L) {
            lastPurge = now;
            GROUND.values().removeIf(g -> now - g.at > 400L);
        }
        HordeImpostors.draw(event.getModelViewMatrix(), event.getProjectionMatrix(), now + pt);
    }

    /** 足元: 読み込まれていれば、群れの高さの上 UP〜下 DOWN で、木でない固い地面の上。明るさもそこで引く。 */
    private static Ground ground(ClientLevel level, BlockPos.MutableBlockPos pos, double x, double swarmY, double z, long now) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        double y = swarmY;
        int light = LightTexture.pack(0, 15);
        if (level.getChunkSource().hasChunk(bx >> 4, bz >> 4)) {
            int top = (int) Math.floor(swarmY) + UP;
            for (int yy = top; yy >= top - UP - DOWN; yy--) {
                pos.set(bx, yy, bz);
                BlockState st = level.getBlockState(pos);
                if (st.blocksMotion() && !TreeCanopyGroundFinder.isTreePart(st)) {
                    pos.set(bx, yy + 1, bz);
                    if (!level.getBlockState(pos).blocksMotion()) {
                        y = yy + 1;
                        break;
                    }
                }
            }
            pos.set(bx, (int) Math.floor(y) , bz);
            light = LevelRenderer.getLightColor(level, pos);
        }
        return new Ground(x, z, y, light, now);
    }
}
