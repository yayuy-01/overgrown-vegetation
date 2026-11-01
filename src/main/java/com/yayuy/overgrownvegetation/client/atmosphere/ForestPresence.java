package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * カメラが<b>過繁茂の森の中にいる度合い</b>と<b>岩の天井の下にいる度合い</b>(どちらも 0〜1)。雰囲気の演出はどれもこの度合いで出し入れする
 * (2026-10-30 作り直し。元は parked の {@code client/ForestPresence})。
 *
 * <p><b>森の度合い = 頭上の覆いの割合</b>(提案 5、10-30 ユーザー採用「森の縁の移り変わりは時間の寄せではなく頭上の覆い具合で連続に」)。
 * 0.5 秒おきに、カメラのまわり({@link #RINGS} の 17 か所。真ん中ほど重く)の列で「目より上に何かある(林冠)か」を見て、過繁茂の地域の列の
 * 覆いの割合(湿地は {@code swampPresence} 倍)を出し、{@code coverStart}〜{@code coverFull} で 0 → 1 にする。外から歩いて入ると梢の下に
 * 入るにつれて少しずつ濃くなり、空き地では自然に薄れ、梢の上へ出ると消える。目標へは {@code presenceSmoothSeconds}(ちらつき止め)で寄る。
 * 地下の度合いは真上の岩の天井を探し、時定数 {@code transitionCaveSeconds}(11-01 夜から 4 秒。前は 1 秒の直線)で寄る。霧(第 3 段)・空(第 4 段)もこの値を読む。
 * バニラの霧が読む森の霧の度合い {@link #fogForest()} は、{@link #openForest()} をさらに {@code transitionForestSeconds}(4 秒)で寄せたもの
 * (11-01 夜 ユーザー「シェーダーの変わり目をもっとゆっくり」。濃い霧の森から薄い所へ出たとき急に晴れないように)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class ForestPresence {
    private static final int ROOF_SCAN = 4096;
    /** 覆いを見る所: {半径, 重さ}。真ん中 1 か所・半径 4 と 9 の 8 方向ずつ。 */
    private static final float[][] RINGS = {{0.0F, 3.0F}, {4.0F, 1.5F}, {9.0F, 1.0F}};
    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    private static float target;

    private static float forest;
    private static float underground;
    private static float fogForest;
    private static boolean roofed;
    private static long ticks;
    private static long lastMillis;

    /** 過繁茂の森の中にいる度合い。 */
    public static float forestFactor() {
        return forest;
    }

    /** カメラが岩の天井の下にいる度合い。 */
    public static float underground() {
        return underground;
    }

    /** 森の中で、空の下にいる度合い(林冠の光の演出の出し入れ)。 */
    public static float openForest() {
        return forest * (1.0F - underground);
    }

    /** 森の胞子の霧の度合い(バニラの霧の距離と色が読む)。{@link #openForest()} を時定数 {@code transitionForestSeconds} で寄せたもの。 */
    public static float fogForest() {
        return fogForest;
    }

    /** {@code value} を {@code target} へ時定数 {@code seconds} の指数で {@code elapsed} 秒ぶん寄せる(0 秒なら即。ごく近ければ揃える)。 */
    public static float approach(float value, float target, float elapsed, float seconds) {
        float next = seconds <= 0.0F ? target : value + (target - value) * (1.0F - (float) Math.exp(-elapsed / seconds));
        return Math.abs(next - target) < 0.002F ? target : next;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            forest = 0.0F;
            underground = 0.0F;
            fogForest = 0.0F;
            lastMillis = 0L;
            return;
        }
        if (mc.isPaused()) {
            lastMillis = 0L;
            return;
        }
        ticks++;
        if (ticks % 10 == 0) {
            roofed = hasRockRoof(level, mc.gameRenderer.getMainCamera().getPosition());
        }
        if (ticks % 10 == 5) {
            target = coverTarget(level, mc.gameRenderer.getMainCamera().getPosition());
        }
        long now = Util.getMillis();
        float elapsed = lastMillis == 0L ? 0.0F : (now - lastMillis) / 1000.0F;
        lastMillis = now;
        var fog = DesignData.atmosphere().fog();
        underground = approach(underground, roofed ? 1.0F : 0.0F, Math.min(elapsed, 0.5F), fog.transitionCaveSeconds());
        float smooth = DesignData.atmosphere().presenceSmoothSeconds();
        forest = smooth <= 0.0F ? target : forest + (target - forest) * (1.0F - (float) Math.exp(-Math.min(elapsed, 0.5F) / smooth));
        if (Math.abs(forest - target) < 0.002F) {
            forest = target;
        }
        fogForest = approach(fogForest, openForest(), Math.min(elapsed, 0.5F), fog.transitionForestSeconds());
    }

    /** 頭上の覆いの割合から森の度合いの目標を出す。 */
    private static float coverTarget(ClientLevel level, Vec3 eye) {
        var t = DesignData.atmosphere();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int eyeY = (int) Math.floor(eye.y);
        float covered = 0.0F;
        float total = 0.0F;
        for (float[] ring : RINGS) {
            for (int i = 0; i < (ring[0] == 0.0F ? 1 : DIRS.length); i++) {
                double len = DIRS[i][0] != 0 && DIRS[i][1] != 0 ? Math.sqrt(2.0) : 1.0;
                int x = (int) Math.floor(eye.x + DIRS[i][0] * ring[0] / len);
                int z = (int) Math.floor(eye.z + DIRS[i][1] * ring[0] / len);
                total += ring[1];
                var biome = level.getBiome(pos.set(x, eyeY, z));
                if (!ModBiomes.isOvergrownLike(biome)) {
                    continue;
                }
                // 目より 2 ブロック以上上に何かある列 = 梢の下(梢の上に出れば覆われていない)。
                if (level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) > eyeY + 2) {
                    covered += ring[1] * (ModBiomes.isSwampLike(biome) ? t.swampPresence() : 1.0F);
                }
            }
        }
        float cover = total > 0.0F ? covered / total : 0.0F;
        return SunLight.smooth(net.minecraft.util.Mth.clamp((cover - t.coverStart()) / (t.coverFull() - t.coverStart()), 0.0F, 1.0F));
    }

    /** {@code eye} の上に、木の一部でない不透明なブロックがあるか(洞窟・建物の中)。木の一部は飛ばしてその上を見る。 */
    static boolean hasRockRoof(ClientLevel level, Vec3 eye) {
        if (!level.dimensionType().hasSkyLight()) {
            return true;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x = (int) Math.floor(eye.x);
        int z = (int) Math.floor(eye.z);
        int y0 = (int) Math.floor(eye.y) + 1;
        int top = Math.min(level.getMaxBuildHeight() - 1, y0 + ROOF_SCAN);
        for (int y = y0; y <= top; y++) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (state.isAir() || !state.canOcclude() || TreeCanopyGroundFinder.isTreePart(state)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private ForestPresence() {
    }
}
