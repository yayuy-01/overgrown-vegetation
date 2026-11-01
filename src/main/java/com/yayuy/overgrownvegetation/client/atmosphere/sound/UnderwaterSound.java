package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.corruption.UnderwaterPlants;
import com.yayuy.overgrownvegetation.data.AtmosphereSound;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

/**
 * <b>水中の環境音</b>の係(2026-11-01。見本 W1 をユーザー採用。合成は {@link CaveWaterSynth.Underwater})。過繁茂の濁った川・池(黄緑の濁り)ともやの海(青緑)に
 * 潜っている間、バニラの水中の音より低く静かなこもった低音を流す。
 *
 * <ul>
 *     <li>鳴る所: 目が水の中で、目の所のバイオームが過繁茂・繁茂の 6 つ(陸・川・海。水中の粒の色を変える {@code UnderwaterMoteParticle} と同じ)。
 *     川・池か海かは {@link UnderwaterPlants#isSea} と同じ判定(合成の中で 2 秒ほどで寄せる)。</li>
 *     <li>大きさ {@code volume}。潜るとき {@code fadeIn} 秒で入り、浮かぶとき {@code fadeOut} 秒で消える。水深(目の上の水のマス)が {@code depthFull} で
 *     {@code depthVolume} 倍、低域通過も最大 25% 下げる(深いほど低く静か)。</li>
 *     <li>同じ所ではバニラの水中の環境音(ambient.underwater.loop と additions)を {@code vanillaScale} 倍に下げる({@link #vanillaScale}、
 *     {@code mixin.client.UnderwaterAmbientVolumeMixin} が読む)。</li>
 * </ul>
 * 音の種類は「環境音」。数値は {@code atmosphere.json} の節 {@code sound.water}(仮決め)。
 */
public final class UnderwaterSound {

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    private static final Source SOURCE = new Source();
    @Nullable
    private static LoopSoundInstance sound;
    /** バニラの水中の環境音に掛ける倍率(音のスレッドからも読まれる)。 */
    private static volatile float vanillaScale = 1.0F;
    private static long ticks;
    private static boolean wasIn;
    private static int depth;
    private static boolean sea;

    private UnderwaterSound() {
    }

    /** バニラの水中の環境音に掛ける倍率(過繁茂の水に潜っている間だけ 1 未満)。 */
    public static float vanillaScale() {
        return vanillaScale;
    }

    private static final class Source implements LoopSoundInstance.Source {
        private CaveWaterSynth.Underwater synth;
        volatile float gain;

        @Override
        public float volume() {
            return this.gain;
        }

        @Override
        public double x() {
            return 0.0;
        }

        @Override
        public double y() {
            return 0.0;
        }

        @Override
        public double z() {
            return 0.0;
        }

        @Override
        public boolean shouldStop(LoopSoundInstance instance) {
            return instance != sound;
        }

        @Override
        public void render(float[] out, int frames) {
            CaveWaterSynth.Underwater s = this.synth;
            if (s != null) {
                s.render(out, 0, frames, 1.0);
            }
        }
    }

    public static void stop() {
        if (sound != null) {
            Minecraft.getInstance().getSoundManager().stop(sound);
            sound = null;
        }
        SOURCE.gain = 0.0F;
        vanillaScale = 1.0F;
        wasIn = false;
    }

    /** 毎 tick({@link com.yayuy.overgrownvegetation.client.atmosphere.Atmosphere} から)。 */
    public static void tick(Minecraft mc, ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.ambientUnderwater()) {
            if (sound != null || vanillaScale != 1.0F) {
                stop();
            }
            return;
        }
        ticks++;
        AtmosphereSound.WaterSoundParams p = DesignData.atmosphere().sound().water();
        boolean audible = DEV_LOG || mc.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0.0F && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F;
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        boolean in = player.isEyeInFluid(FluidTags.WATER) && overgrownWater(level.getBiome(eye));
        vanillaScale = in ? p.vanillaScale() : 1.0F;
        float target = 0.0F;
        if (in) {
            if (ticks % 10 == 0 || !wasIn) {
                depth = 0;
                BlockPos.MutableBlockPos pos = eye.mutable();
                while (depth < 64 && level.getFluidState(pos.move(0, 1, 0)).is(FluidTags.WATER)) {
                    depth++;
                }
                sea = UnderwaterPlants.isSea(level.getBiome(eye));
            }
            float deep = Math.min(1.0F, depth / p.depthFull());
            target = p.volume() * (1.0F - (1.0F - p.depthVolume()) * deep);
            if (SOURCE.synth == null) {
                SOURCE.synth = new CaveWaterSynth.Underwater(0x0A7E_0001, sea);
            }
            SOURCE.synth.sea = sea ? 1.0F : 0.0F;
            SOURCE.synth.depth = deep;
        }
        // 入るときは fadeIn 秒、消えるときは fadeOut 秒で(直線。歩幅は大きさ volume を基準に固定)。
        float step = p.volume() / ((target > SOURCE.gain ? p.fadeIn() : p.fadeOut()) * 20.0F);
        SOURCE.gain = target > SOURCE.gain ? Math.min(target, SOURCE.gain + step) : Math.max(target, SOURCE.gain - step);
        if (SOURCE.gain > 0.001F && audible) {
            if (sound == null || !mc.getSoundManager().isActive(sound)) {
                sound = new LoopSoundInstance("underwater", SOURCE, true);
                mc.getSoundManager().play(sound);
            }
        } else if (sound != null && !in) {
            mc.getSoundManager().stop(sound);
            sound = null;
        }
        if (DEV_LOG && (in != wasIn || ticks % 100 == 0 && (in || sound != null))) {
            OvergrownVegetation.LOGGER.info("UnderwaterSound {} {} 水深 {} 大きさ {}(目標 {})バニラの水中音 ×{} 流れ {}", in != wasIn ? (in ? "潜った" : "浮かんだ") : "潜っている",
                    sea ? "海" : "川・池", depth, String.format("%.3f", SOURCE.gain), String.format("%.3f", target), String.format("%.2f", vanillaScale),
                    sound != null && sound.streaming ? "あり" : "なし");
        }
        wasIn = in;
    }

    /** 過繁茂・繁茂の水のバイオーム 6 つ(陸・川・海。UnderwaterMoteParticle と同じ)。 */
    static boolean overgrownWater(Holder<Biome> b) {
        return b.is(ModBiomes.OVERGROWN_OCEAN) || b.is(ModBiomes.OVERGROWTH_OCEAN) || b.is(ModBiomes.OVERGROWN_RIVER)
                || b.is(ModBiomes.OVERGROWTH_RIVER) || b.is(ModBiomes.OVERGROWN) || b.is(ModBiomes.OVERGROWTH);
    }
}
