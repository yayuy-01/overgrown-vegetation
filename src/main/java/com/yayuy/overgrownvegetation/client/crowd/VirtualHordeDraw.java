package com.yayuy.overgrownvegetation.client.crowd;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.horde.ClientVirtualHordes;
import com.yayuy.overgrownvegetation.horde.VirtualHordeLayout;
import com.yayuy.overgrownvegetation.network.VirtualHordePayload;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * 仮想の群れ(大群の軽量化 A の {@link ClientVirtualHordes})を、遠くの身代わり({@link HordeImpostors})で描く(2026-11-18 大群の軽量化 C)。
 *
 * <p>一員の位置はサーバーの本物化と同じ式({@link VirtualHordeLayout#offset})なので、身代わりが消えた所に本物が出る。
 * <b>仮想のままの一員は、近くでも描く</b>(本物の上限 300・1 回に 6 体ずつなので、64 ブロックの内側に仮想のまま残る一員がいる。描かないと
 * 群れに穴が開いた)。代わりに、同じ種の本物が 2 ブロック以内にいる一員は描かない(本物になったばかりで、次の送り(20 tick ごと)まで
 * 一覧に残っている一員を 2 重に描かないため)。描画距離より遠い一員は描かない。</p>
 *
 * <p>足元の高さ: 読み込まれた所では、群れの高さ + 6 〜 − 12 で、木(幹・葉)でない地面を探す(過繁茂の森で梢の上を歩かないように。
 * parked の遠景の身代わりで踏んだ罠)。読み込まれていない所は群れの高さ(サーバーが葉を除いて測った値)。並びのずれ・高さ・明るさは
 * 一員ごとに覚え、引き直しは一員ごとに時期をずらす(同じ速さで動く群れ全員が同じフレームで引き直さないように)。</p>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class VirtualHordeDraw {

    /** 確かめ用の切り替え(devbridge の {@code crowd virtual off|on})。 */
    public static volatile boolean enabled = true;

    private static final int UP = 6, DOWN = 12;
    /** 足元を引き直す間隔(tick)。一員ごとに 0〜{@value #REFRESH_SPREAD} tick ずらす。 */
    private static final int REFRESH_TICKS = 20, REFRESH_SPREAD = 20;
    /** これより動いたら、間隔を待たずに引き直す(ブロック。群れは 1 tick 0.12 ブロック程度なので、ふだんは間隔で引き直す)。 */
    private static final double REFRESH_MOVE = 4.0D;

    /** 一員ごとに覚えるもの(キー = 群れの id × 4096 + 番号)。 */
    private static final class Member {
        EntityType<?> type;
        double offX, offZ;
        double x, z, y;
        int light;
        long at;
        long seen;
    }

    private static final Long2ObjectOpenHashMap<Member> MEMBERS = new Long2ObjectOpenHashMap<>();
    /** このフレームの、本物の(この MOD の)Mob の居場所の 2 ブロック升。 */
    private static final LongOpenHashSet REAL = new LongOpenHashSet();
    private static long lastPurge;
    // 確かめ用
    static int lastHidden;

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
            if (!MEMBERS.isEmpty()) MEMBERS.clear();
            return;
        }
        float pt = event.getPartialTick();
        Vec3 cam = event.getCamera().getPosition();
        double far = mc.options.getEffectiveRenderDistance() * 16.0D;
        double far2 = far * far;
        long now = level.getGameTime();
        collectReal(level, cam, far);
        HordeImpostors.begin(cam);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int hidden = 0;
        for (ClientVirtualHordes.Entry e : swarms) {
            VirtualHordePayload.Swarm s = e.swarm();
            double cx = ClientVirtualHordes.x(e, pt), cz = ClientVirtualHordes.z(e, pt);
            boolean moving = s.speed() > 0.01F;
            float heading = (float) Math.toDegrees(Math.atan2(-s.dirX(), s.dirZ()));
            // 一員の番号は firstIndex から(本物になった分は番号を詰めずに firstIndex が進む。horde-flow-a 11-18)
            int index = s.firstIndex();
            for (VirtualHordePayload.Count c : s.counts()) {
                for (int k = 0; k < c.n(); k++, index++) {
                    long key = s.id() * 4096L + index;
                    Member m = MEMBERS.get(key);
                    if (m == null || m.type != c.type()) {
                        // 並びのずれは群れの id・番号・種で決まる(送りで番号が詰まったときは種が変わりうるので引き直す)
                        if (m == null) {
                            m = new Member();
                            MEMBERS.put(key, m);
                        }
                        double[] off = VirtualHordeLayout.offset(s.id(), index, c.type());
                        m.type = c.type();
                        m.offX = off[0];
                        m.offZ = off[1];
                        m.at = Long.MIN_VALUE;
                    }
                    m.seen = now;
                    double x = cx + m.offX, z = cz + m.offZ;
                    double dx = x - cam.x, dz = z - cam.z;
                    if (dx * dx + dz * dz > far2) continue;
                    if (now - m.at > REFRESH_TICKS + (index % REFRESH_SPREAD) || Math.abs(m.x - x) + Math.abs(m.z - z) > REFRESH_MOVE) {
                        ground(level, pos, m, x, s.y(), z, now);
                    }
                    // 本物はクライアントに 128 ブロック(追跡の範囲)ほどまでしかいないので、近い一員だけ調べる
                    if (!REAL.isEmpty() && dx * dx + dz * dz < 16384.0D && realNear(x, z)) {
                        hidden++;
                        continue;
                    }
                    long h = s.id() * 31L + index * 0x9E3779B97F4A7C15L;
                    float jitter = ((h >>> 20) & 1023) / 1023.0F * 40.0F - 20.0F;
                    float yaw = moving ? heading + jitter : jitter * 9.0F;
                    HordeImpostors.add(c.type(), x, m.y, z, yaw, HordePoses.slot(moving, s.id(), index), m.light);
                }
            }
        }
        lastHidden = hidden;
        if (now - lastPurge > 200L) {
            lastPurge = now;
            MEMBERS.values().removeIf(m -> now - m.seen > 100L);
        }
        HordeImpostors.draw(event.getPoseStack().last().pose(), event.getProjectionMatrix(), now + pt);
    }

    /** この MOD の本物の Mob の居場所(2 ブロック升)を集める(描画距離の中だけ)。 */
    private static void collectReal(ClientLevel level, Vec3 cam, double far) {
        REAL.clear();
        double far2 = far * far;
        for (Entity en : level.entitiesForRendering()) {
            if (!OvergrownVegetation.MODID.equals(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).getNamespace())) continue;
            double dx = en.getX() - cam.x, dz = en.getZ() - cam.z;
            if (dx * dx + dz * dz > far2) continue;
            REAL.add(cell(Math.floor(en.getX() / 2.0D), Math.floor(en.getZ() / 2.0D)));
        }
    }

    /** (x, z) の 2 ブロック以内に本物がいそうか(2 ブロック升の 3×3)。 */
    private static boolean realNear(double x, double z) {
        double gx = Math.floor(x / 2.0D), gz = Math.floor(z / 2.0D);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                if (REAL.contains(cell(gx + i, gz + j))) return true;
            }
        }
        return false;
    }

    private static long cell(double gx, double gz) {
        return ((long) gx << 32) ^ ((long) gz & 0xFFFFFFFFL);
    }

    /** 足元: 読み込まれていれば、群れの高さの上 UP〜下 DOWN で、木でない固い地面の上。明るさもそこで引く。 */
    private static void ground(ClientLevel level, BlockPos.MutableBlockPos pos, Member m, double x, double swarmY, double z, long now) {
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
            pos.set(bx, (int) Math.floor(y), bz);
            light = LevelRenderer.getLightColor(level, pos);
        }
        m.x = x;
        m.z = z;
        m.y = y;
        m.light = light;
        m.at = now;
    }
}
