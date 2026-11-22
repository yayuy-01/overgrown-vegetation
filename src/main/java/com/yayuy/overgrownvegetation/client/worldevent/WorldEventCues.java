package com.yayuy.overgrownvegetation.client.worldevent;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.GiantTrees;
import com.yayuy.overgrownvegetation.client.atmosphere.SporeAir;
import com.yayuy.overgrownvegetation.worldevent.WorldEventKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import com.yayuy.overgrownvegetation.port.event.tick.ClientTickEvent;

/**
 * <b>フェーズの合図の印 F3・F7 の受け側</b>(2026-11-16。ユーザー決定: F3 = その夜、胞子のオーロラが短く初めて現れる / F7 = 一夜だけ、森の方角の地平線に
 * 胞子の柱が何本も立ち上がる。Vault「フェーズの仕組み」の「フェーズが上がったときの現象」)。
 *
 * <p>「その夜」を覚えて起こすのはサーバー({@code worldevent/WorldEventSchedule} の合図の枠。くじとは別): F3 は短いオーロラ(種 {@code aurora}、長さだけ短い)、
 * F7 は種 {@code spore_pillars}(日没から夜明けまで)。だから F3 はここでは何もしない(ふつうのオーロラの描き方がそのまま出す)。
 * ここが持つのは F7 の柱の<b>森の方角</b>だけ: クライアントは森の場所を知らないので、(1) フェーズが上がったときの合図に付いてきた向き
 * ({@code PhaseCuePayload}。入り直すと忘れる)、(2) いちばん近い巨大樹、(3) 胞子の空気がいちばん濃い方、の順に使う。どれも無ければ柱は出さない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class WorldEventCues {

    private WorldEventCues() {
    }

    /** 柱が消えるのに掛ける tick。 */
    private static final float PILLAR_FALL = 600.0F;

    private static boolean cueDir;
    private static float cueX;
    private static float cueZ;
    private static boolean hasDir;
    private static float dirX;
    private static float dirZ;
    private static int ticks;
    private static boolean wasOn;

    /** {@code PhaseCue.accept} から: フェーズ 7 の合図に付いてきた森の方角を覚える。 */
    public static void onPhaseCue(int phase, boolean hasForest, float forestX, float forestZ) {
        if (phase == 7 && hasForest) {
            cueDir = true;
            cueX = forestX;
            cueZ = forestZ;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) {
            return;
        }
        ticks++;
        boolean on = WorldEventClient.is(WorldEventKind.SPORE_PILLARS);
        if (on && (!wasOn || !hasDir && ticks % 40 == 0)) {
            resolve(mc.gameRenderer.getMainCamera().getPosition());
            if (!wasOn) {
                OvergrownVegetation.LOGGER.info("フェーズ 7 の印: 胞子の柱(森の方角 {})", hasDir ? dirX + ", " + dirZ : "なし");
            }
        }
        if (!on) {
            hasDir = false;
        }
        wasOn = on;
    }

    /** 森の方角を決める(柱が始まるときに 1 回。一夜の間は動かさない)。 */
    private static void resolve(Vec3 cam) {
        if (cueDir) {
            dirX = cueX;
            dirZ = cueZ;
            hasDir = true;
            return;
        }
        GiantTrees.Info t = GiantTrees.nearest(cam.x, cam.z, 20000.0);
        double dx = 0.0, dz = 0.0;
        if (t != null) {
            dx = t.axisX() - cam.x;
            dz = t.axisZ() - cam.z;
        }
        if (dx * dx + dz * dz < 64.0 * 64.0) {
            // 巨大樹が無い・すぐそば: 胞子の空気の濃い方(まわり ±512 を 64 おきに見た重心)。
            dx = 0.0;
            dz = 0.0;
            for (int j = -8; j <= 8; j++) {
                for (int i = -8; i <= 8; i++) {
                    if (i * i + j * j < 9) {
                        continue;
                    }
                    float w = SporeAir.levelAt(cam.x + i * 64.0, cam.z + j * 64.0);
                    dx += i * w;
                    dz += j * w;
                }
            }
        }
        int forest = 0;
        if (dx * dx + dz * dz < 1.0e-6) {
            // 胞子の空気の地図も無い(場が止まっている): 読み込まれたチャンクのバイオームで、過繁茂の森のある方(半径 64〜192 を 16 方向)。
            ClientLevel level = Minecraft.getInstance().level;
            net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
            for (int ring = 1; ring <= 3 && level != null; ring++) {
                for (int k = 0; k < 16; k++) {
                    double a = k * Math.PI / 8.0;
                    pos.set(cam.x + Math.cos(a) * ring * 64.0, cam.y, cam.z + Math.sin(a) * ring * 64.0);
                    if (level.hasChunkAt(pos) && com.yayuy.overgrownvegetation.registry.ModBiomes.isOvergrownLike(level.getBiome(pos))) {
                        dx += Math.cos(a);
                        dz += Math.sin(a);
                        forest++;
                    }
                }
            }
        }
        double l = Math.sqrt(dx * dx + dz * dz);
        if (l > 1.0e-3) {
            dirX = (float) (dx / l);
            dirZ = (float) (dz / l);
            hasDir = true;
        } else if (forest > 0) {
            // 森のただ中(どの方角も森): 決まった向き(東)に立てる。
            dirX = 1.0F;
            dirZ = 0.0F;
            hasDir = true;
        }
    }

    /** フェーズ 7 の柱: 始まってからの tick(出ていなければ負)。 */
    static float pillarsElapsed(float partialTick) {
        if (!hasDir || !WorldEventClient.is(WorldEventKind.SPORE_PILLARS)) {
            return -1.0F;
        }
        return WorldEventClient.elapsedTicks(partialTick);
    }

    /** フェーズ 7 の柱の消え際(1 = 出ている、0 = 消えた)。終わりの {@link #PILLAR_FALL} tick で消える。 */
    static float pillarsFade(float partialTick) {
        float left = WorldEventClient.lengthTicks() - WorldEventClient.elapsedTicks(partialTick);
        return Math.max(0.0F, Math.min(1.0F, left / PILLAR_FALL));
    }

    static float dirX() {
        return dirX;
    }

    static float dirZ() {
        return dirZ;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        cueDir = false;
        hasDir = false;
        wasOn = false;
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        return String.format("フェーズ 7 の柱: %s・森の方角 %s", WorldEventClient.is(WorldEventKind.SPORE_PILLARS) ? "出ている" : "なし",
                hasDir ? String.format("(%.2f, %.2f)", dirX, dirZ) : "なし");
    }
}
