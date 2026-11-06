package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.ForestPresence;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FluidState;

/**
 * 森の中の<b>水音</b>の係(合間の小物、案 E1。2026-10-31。参考画像 3 の空気)。森の中で近くに流れる水・水面があるとき、合成したなめらかな水の音
 * ({@link WaterSynth}: せせらぎ = 中〜高い帯、たぷたぷ = 低い帯)を小さく鳴らす。風の音と違って凪でも鳴るが小さく、水から離れると消える。
 *
 * <p>毎秒 1 回、目のまわり半径 {@code water.radius} ブロック・高さ −6〜+3 の水面(上が水でない水のマス)を数える。近いほど重く(36 ブロック² で半分)、
 * 流れる水は {@code flowingWeight} 倍。数えた重さが {@code minBlocks} で鳴り始め {@code fullBlocks} で最大。音源は水面の重心に置く(向きが聞こえる)。
 * 大きさ = {@code water.volume} × その割合 × 森の中にいる度合い({@link ForestPresence#forestFactor})× 水の中なら 3 割。0.5 秒ほどで寄せ、
 * 3 秒続けて無音なら止めて音の通り道を返す。音の種類は「環境音」。数値は {@code atmosphere.json} の節 {@code water}(すべて仮決め)。
 *
 * <p><b>10-31 から保留</b>: ユーザー「場違い。水の流れる音ではないように聞こえる」→ 泡の作り直し({@link BubbleWaterSynth})も「まだ変に感じる」。
 * {@code water.enabled} が false(既定)の間は一切鳴らさない。
 */
public final class ForestWaterSound {

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    private static WaterSoundInstance sound;
    private static float gain;
    private static float target;
    /** 流れる水の割合(音のスレッドが読む)。 */
    static volatile float flow;
    private static double x;
    private static double y;
    private static double z;
    private static int quietTicks;
    private static long ticks;
    private static float weighed;

    private ForestWaterSound() {
    }

    static float gain() {
        return gain;
    }

    static double x() {
        return x;
    }

    static double y() {
        return y;
    }

    static double z() {
        return z;
    }

    static boolean shouldStop(WaterSoundInstance instance) {
        return instance != sound;
    }

    public static void stop() {
        if (sound != null) {
            Minecraft.getInstance().getSoundManager().stop(sound);
            sound = null;
        }
        gain = 0.0F;
        target = 0.0F;
    }

    /** 毎 tick({@link com.yayuy.overgrownvegetation.client.atmosphere.Atmosphere} から)。 */
    public static void tick(Minecraft mc, ClientLevel level, LocalPlayer player) {
        ticks++;
        AtmosphereTable.WaterParams p = DesignData.atmosphere().water();
        if (!p.enabled()) {
            // 水音は保留(10-31 ユーザー: 前の音は「場違い」、泡の作り直しも「まだ変に感じる」)。water.enabled が false の間は一切鳴らさない。
            if (sound != null) {
                stop();
            }
            return;
        }
        if (ticks % 20 == 0) {
            scan(level, player, p);
        }
        gain += (target - gain) * 0.1F;
        boolean audible = mc.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0.0F && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F;
        if (gain > 0.003F && audible) {
            quietTicks = 0;
            if (sound == null || !mc.getSoundManager().isActive(sound)) {
                sound = new WaterSoundInstance();
                mc.getSoundManager().play(sound);
            }
        } else if (sound != null && ++quietTicks > 60) {
            stop();
        }
        if (DEV_LOG && ticks % 100 == 0) {
            OvergrownVegetation.LOGGER.info("ForestWaterSound 水 {} 流れ {} 大きさ {} 鳴る {}", String.format("%.1f", weighed),
                    String.format("%.2f", flow), String.format("%.3f", gain), sound != null);
        }
    }

    private static void scan(ClientLevel level, LocalPlayer player, AtmosphereTable.WaterParams p) {
        int r = Mth.ceil(p.radius());
        int ex = Mth.floor(player.getX());
        int ey = Mth.floor(player.getEyeY());
        int ez = Mth.floor(player.getZ());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double total = 0.0;
        double flowing = 0.0;
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2h = dx * dx + dz * dz;
                if (d2h > r * r) {
                    continue;
                }
                for (int dy = 3; dy >= -6; dy--) {
                    FluidState fluid = level.getFluidState(pos.set(ex + dx, ey + dy, ez + dz));
                    if (!fluid.is(FluidTags.WATER)) {
                        continue;
                    }
                    // 水面だけ(上が水でない)。列の中で見つけたら下は見ない。
                    if (level.getFluidState(pos.set(ex + dx, ey + dy + 1, ez + dz)).is(FluidTags.WATER)) {
                        continue;
                    }
                    double near = 1.0 / (1.0 + (d2h + dy * dy) / 36.0);
                    double w = near * (fluid.isSource() ? 1.0 : p.flowingWeight());
                    total += w;
                    if (!fluid.isSource()) {
                        flowing += w;
                    }
                    sx += w * (ex + dx + 0.5);
                    sy += w * (ey + dy + 0.8);
                    sz += w * (ez + dz + 0.5);
                    break;
                }
            }
        }
        weighed = (float) total;
        if (total <= 0.0) {
            target = 0.0F;
            return;
        }
        x = sx / total;
        y = sy / total;
        z = sz / total;
        flow = (float) Mth.clamp(flowing / total, 0.0, 1.0);
        float amount = Mth.clamp((float) ((total - p.minBlocks()) / (p.fullBlocks() - p.minBlocks())), 0.0F, 1.0F);
        amount = amount * amount * (3.0F - 2.0F * amount);
        float underwater = player.isUnderWater() ? 0.3F : 1.0F;
        target = p.volume() * amount * ForestPresence.overgrown() * underwater;
    }
}
