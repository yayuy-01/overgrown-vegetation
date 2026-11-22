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
 * カメラの<b>頭上の覆いの度合い</b>・<b>過繁茂の森の中にいる度合い</b>・<b>岩の天井の下にいる度合い</b>(どれも 0〜1)。雰囲気の演出はどれもこの度合いで出し入れする
 * (2026-10-30 作り直し。元は parked の {@code client/ForestPresence})。
 *
 * <p><b>2 層</b>(2026-11-06、ユーザー決定「雰囲気の演出を世界全体 + 過繁茂で強まる、の 2 層に」):
 * <ul>
 *   <li>{@link #cover()} = バイオームを問わない頭上の覆いの割合。世界全体の演出(林冠の影・木漏れ日・透過光・林床の持ち上げ)はこれで出し入れする。</li>
 *   <li>{@link #overgrown()} = 17 列のうち過繁茂の地域の列のうち覆われた列の割合(湿地は {@code swampPresence} 倍)を同じく 0 → 1 にしたもの(2 層に分ける前の森の度合いと同じ。≤ {@link #cover()})。
 *       過繁茂で強まる演出(霞と森の霧・緑の照り返し・森の色・胞子の粒・光の筋・虫の音)はこれで出し入れする。</li>
 *   <li>時刻の色と夜の暗さは覆いが無くても効く({@link #sky()})。</li>
 * </ul>
 *
 * <p><b>覆いの度合い = 頭上の覆いの割合</b>(提案 5、10-30 ユーザー採用「森の縁の移り変わりは時間の寄せではなく頭上の覆い具合で連続に」)。
 * 0.5 秒おきに、カメラのまわり({@link #RINGS} の 17 か所。真ん中ほど重く)の列で「目より上に何かある(林冠)か」を見て覆いの割合を出し、
 * {@code coverStart}〜{@code coverFull} で 0 → 1 にする。外から歩いて入ると梢の下に入るにつれて少しずつ濃くなり、空き地では自然に薄れ、
 * 梢の上へ出ると消える。目標へは {@code presenceSmoothSeconds}(ちらつき止め)で寄る。
 * 地下の度合いは真上の岩の天井を探し、時定数 {@code transitionCaveSeconds}(11-01 夜から 4 秒。前は 1 秒の直線)で寄る。霧(第 3 段)・空(第 4 段)もこの値を読む。
 * バニラの霧が読む森の霧の度合い {@link #fogForest()} は、{@link #openOvergrown()} をさらに {@code transitionForestSeconds}(4 秒)で寄せたもの
 * (11-01 夜 ユーザー「シェーダーの変わり目をもっとゆっくり」。濃い霧の森から薄い所へ出たとき急に晴れないように)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class ForestPresence {
    private static final int ROOF_SCAN = 4096;
    /** 覆いを見る所: {半径, 重さ}。真ん中 1 か所・半径 4 と 9 の 8 方向ずつ。 */
    private static final float[][] RINGS = {{0.0F, 3.0F}, {4.0F, 1.5F}, {9.0F, 1.0F}};
    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    private static float targetCover;
    private static float targetOvergrown;

    private static float cover;
    private static float overgrown;
    private static float underground;
    private static float fogForest;
    private static boolean roofed;
    private static long ticks;
    private static long lastMillis;

    /** 頭上の覆いの度合い(バイオームを問わない。世界全体の層)。 */
    public static float cover() {
        return cover;
    }

    /** 過繁茂の森の中にいる度合い(過繁茂の列の割合 × 覆い。過繁茂で強まる層)。 */
    public static float overgrown() {
        return overgrown;
    }

    /**
     * 覆いのうち過繁茂の分(0〜1。{@link #overgrown()} ÷ {@link #cover()})。過繁茂の森の中なら覆いが薄くても 1、バニラの森・平原なら 0。
     * 「外の値」と過繁茂の値の間を寄せるのに使う(夜の林床の持ち上げ・透過光の色)。
     */
    public static float overgrownShare() {
        return cover > 0.001F ? Math.min(1.0F, overgrown / cover) : (overgrown > 0.0F ? 1.0F : 0.0F);
    }

    /** カメラが岩の天井の下にいる度合い。 */
    public static float underground() {
        return underground;
    }

    /** 覆いの下で、空の下にいる度合い(世界全体の林冠の光の出し入れ)。 */
    public static float openCover() {
        return cover * (1.0F - underground);
    }

    /** 過繁茂の森の中で、空の下にいる度合い(過繁茂で強まる光の演出の出し入れ。元の openForest)。 */
    public static float openOvergrown() {
        return overgrown * (1.0F - underground);
    }

    /**
     * 時刻の色(夕方・朝)と夜の暗さの強さ(世界全体の層。覆いが無い平原でも 1)。地下では切る —— ただし過繁茂の地下では
     * 2 層に分ける前と同じく過繁茂の度合いのまま({@code max(1 − 地下, 過繁茂)}。過繁茂の外の洞窟は前と同じく 0)。
     */
    public static float sky() {
        return Math.max(1.0F - underground, overgrown);
    }

    /** 森の胞子の霧の度合い(バニラの霧の距離と色が読む)。{@link #openOvergrown()} を時定数 {@code transitionForestSeconds} で寄せたもの。 */
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
            cover = 0.0F;
            overgrown = 0.0F;
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
            coverTargets(level, mc.gameRenderer.getMainCamera().getPosition());
        }
        long now = Util.getMillis();
        float elapsed = lastMillis == 0L ? 0.0F : (now - lastMillis) / 1000.0F;
        lastMillis = now;
        float dt = Math.min(elapsed, 0.5F);
        var fog = DesignData.atmosphere().fog();
        underground = approach(underground, roofed ? 1.0F : 0.0F, dt, fog.transitionCaveSeconds());
        float smooth = DesignData.atmosphere().presenceSmoothSeconds();
        cover = approach(cover, targetCover, dt, smooth);
        overgrown = approach(overgrown, targetOvergrown, dt, smooth);
        fogForest = approach(fogForest, openOvergrown(), dt, fog.transitionForestSeconds());
    }

    /**
     * 頭上の覆いの割合から 2 つの度合いの目標を出す(同じ 17 列を 1 回だけ見る)。覆い = 全部の列の覆いの割合を {@code coverStart}〜{@code coverFull} で
     * 0 → 1 にしたもの、過繁茂 = 過繁茂の列のうち覆われた列の割合(湿地は {@code swampPresence} 倍)を同じく 0 → 1 にしたもの(2 層に分ける前の森の度合いと同じ式。
     * 「過繁茂の列の割合 × 覆い」も試したが、境目で霧が前より早く抜けたので前の式にした。11-06)。過繁茂 ≤ 覆い。
     */
    private static void coverTargets(ClientLevel level, Vec3 eye) {
        var t = DesignData.atmosphere();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int eyeY = (int) Math.floor(eye.y);
        float covered = 0.0F;
        float overgrownCovered = 0.0F;
        float total = 0.0F;
        for (float[] ring : RINGS) {
            for (int i = 0; i < (ring[0] == 0.0F ? 1 : DIRS.length); i++) {
                double len = DIRS[i][0] != 0 && DIRS[i][1] != 0 ? Math.sqrt(2.0) : 1.0;
                int x = (int) Math.floor(eye.x + DIRS[i][0] * ring[0] / len);
                int z = (int) Math.floor(eye.z + DIRS[i][1] * ring[0] / len);
                total += ring[1];
                // 目より 2 ブロック以上上に何かある列 = 梢の下(梢の上に出れば覆われていない)。
                if (level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) > eyeY + 2) {
                    covered += ring[1];
                    var biome = level.getBiome(pos.set(x, eyeY, z));
                    if (ModBiomes.isOvergrownLike(biome)) {
                        overgrownCovered += ring[1] * (ModBiomes.isSwampLike(biome) ? t.swampPresence() : 1.0F);
                    }
                }
            }
        }
        targetCover = ramp(total > 0.0F ? covered / total : 0.0F);
        targetOvergrown = ramp(total > 0.0F ? overgrownCovered / total : 0.0F);
    }

    /** 覆いの割合を {@code coverStart}〜{@code coverFull} で 0 → 1 に(なめらかに)。 */
    private static float ramp(float share) {
        var t = DesignData.atmosphere();
        return SunLight.smooth(net.minecraft.util.Mth.clamp((share - t.coverStart()) / (t.coverFull() - t.coverStart()), 0.0F, 1.0F));
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
