package com.yayuy.overgrownvegetation.client.sound;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.CloudInterior;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * 雲の中の風の音(2026-10-17 深夜。ユーザーが選んだ「雲の中の風の音」)。雲の中にいる間だけ、こもった低い風の音を<b>突風のときだけ</b>鳴らす
 * (鳴りっぱなしにしない。メモリ「合成した環境音にとがった粒を入れない・鳴りっぱなしにしない」)。
 *
 * <ul>
 *   <li>音の中身は林の風と同じ合成器 {@link WindSynth} の「遠くの林」(高い音を削った、こもった音)。ファイルは無い。</li>
 *   <li>突風: 3.5 秒ほどの間隔でゆっくり上下する雑音が高い所だけ鳴る(2〜4 秒の突風が、数秒〜十数秒おきに)。積乱雲の中は強く、間隔も詰まる。</li>
 *   <li>速く飛んでいる(エリトラ)ときは、突風の間でも空気を切る音を少し(動いている間だけ)。</li>
 *   <li>大きさ = 設定 {@code cloudWindSound} × 雲の中の度合い({@link CloudInterior})× 突風。3 秒静かなら止める(音の通り道を返す)。</li>
 * </ul>
 * 数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CloudWindSound {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "cloud_wind");

    /** 合成器へ渡す今の風の強さ・大きさ(描画のスレッドが書き、音のスレッドが読む)。 */
    private static volatile float windW;
    private static volatile float windLevel;
    private static Instance playing;
    private static int quietTicks;
    private static int retryIn;
    private static long ticks;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null || mc.isPaused()) {
            if (mc.level == null) {
                stop(mc);
            }
            return;
        }
        ticks++;
        float setting = OvergrownVegetationConfig.COMMON.cloudWindSound.get().floatValue();
        float inside = CloudInterior.amount(1.0F) * CloudInterior.setting();
        float storm = Mth.clamp(CloudInterior.storm(1.0F) / Math.max(CloudInterior.amount(1.0F), 1.0E-4F), 0.0F, 1.0F);
        double t = ticks / 20.0;
        // 突風: なめらかな雑音の高い所だけ(積乱雲の中は鳴り始めの線を下げて、突風を多く)。
        float n = valueNoise(t / 3.5) * 0.7F + valueNoise(t / 1.3 + 17.0) * 0.3F;
        float gust = smoothstep(Mth.lerp(storm, 0.5F, 0.38F), Mth.lerp(storm, 0.82F, 0.7F), n);
        double speed = player.getDeltaMovement().length();
        float rush = smoothstep(0.6F, 1.8F, (float) speed) * 0.45F;
        float level = setting * inside * Mth.clamp(gust * Mth.lerp(storm, 0.6F, 1.0F) + rush, 0.0F, 1.0F);
        windW = 0.3F + 0.45F * gust + 0.25F * storm * gust + 0.2F * rush;
        // 雲の上の風(10-18、空の世界の順番 3「高い所の風の音」): 雲の外の高い所では、空の突風({@link SkyAir#gust()} = 白い帯と押す力と同じ)と
        // 速く飛んでいるときだけ鳴らす(鳴りっぱなしにしない)。
        float high = com.yayuy.overgrownvegetation.client.HighSky.clarity() * (1.0F - inside);
        float skyGust = com.yayuy.overgrownvegetation.client.SkyAir.gust();
        float highLevel = setting * high * Mth.clamp(skyGust * 0.8F + rush * 0.9F, 0.0F, 1.0F);
        if (highLevel > level) {
            level = highLevel;
            windW = 0.35F + 0.5F * skyGust + 0.2F * rush;
        }
        windLevel = level;

        SoundManager sounds = mc.getSoundManager();
        boolean muted = mc.options.getSoundSourceVolume(SoundSource.AMBIENT) <= 0.0F || mc.options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F;
        if (level > 0.01F && !muted) {
            quietTicks = 0;
            if (retryIn > 0) {
                retryIn--;
            } else if (playing == null || !sounds.isActive(playing)) {
                playing = new Instance();
                sounds.play(playing);
                retryIn = 40;
            }
        } else if (++quietTicks > 60) {
            stop(mc);
        }
        if (DEV_LOG && ticks % 100 == 0 && (inside > 0.01F || level > 0.01F)) {
            OvergrownVegetation.LOGGER.info("CloudWindSound 中={} 積乱雲={} 突風={} 大きさ={} 鳴っている={}", String.format("%.2f", inside),
                    String.format("%.2f", storm), String.format("%.2f", gust), String.format("%.3f", level), playing != null && sounds.isActive(playing));
        }
    }

    private static void stop(Minecraft mc) {
        if (playing != null) {
            mc.getSoundManager().stop(playing);
            playing = null;
        }
        quietTicks = 0;
    }

    /** 0〜1 のなめらかな 1 次元の雑音(整数の所に乱数、間は S 字でつなぐ)。 */
    private static float valueNoise(double x) {
        long i = (long) Math.floor(x);
        float f = (float) (x - i);
        float a = hash(i);
        float b = hash(i + 1);
        return Mth.lerp(f * f * (3.0F - 2.0F * f), a, b);
    }

    private static float hash(long i) {
        long h = i * 0x9E3779B97F4A7C15L + 0x51ED2701L;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1 << 24);
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    /** 音源(聞く人に付いて回る・ステレオ)。 */
    private static final class Instance extends AbstractTickableSoundInstance {
        Instance() {
            super(SoundEvent.createVariableRangeEvent(ID), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.volume = 1.0F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            this.sound = new Sound(ID, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, true, false, 16);
            return new WeighedSoundEvents(ID, null);
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
            return CompletableFuture.completedFuture(new Stream());
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (playing != this) {
                this.stop();
            }
        }
    }

    /** 合成した音の流れ(150 ミリ秒ずつ。今の風の強さ・大きさを読む。合成器が値をなめらかに寄せる)。 */
    private static final class Stream implements AudioStream {
        private static final int FRAMES = WindSynth.SAMPLE_RATE * 150 / 1000;
        private final WindSynth synth = new WindSynth(true, true, 0x0C10D517);
        private final AudioFormat format = new AudioFormat(WindSynth.SAMPLE_RATE, 16, this.synth.channels(), true, false);
        private final float[] scratch = new float[FRAMES * this.synth.channels()];

        @Override
        public AudioFormat getFormat() {
            return this.format;
        }

        @Override
        public ByteBuffer read(int size) {
            int channels = this.synth.channels();
            for (int done = 0; done < FRAMES; done += WindSynth.BLOCK) {
                int n = Math.min(WindSynth.BLOCK, FRAMES - done);
                this.synth.render(this.scratch, done * channels, n, windW, windLevel);
            }
            ByteBuffer buffer = BufferUtils.createByteBuffer(this.scratch.length * 2);
            for (float v : this.scratch) {
                buffer.putShort((short) Math.round(Math.max(-1.0F, Math.min(1.0F, v)) * 32767.0F));
            }
            buffer.flip();
            return buffer;
        }

        @Override
        public void close() {
        }
    }

    private CloudWindSound() {
    }
}
