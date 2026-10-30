package com.yayuy.overgrownvegetation.client.sound;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
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
import net.minecraft.util.valueproviders.ConstantFloat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * 雷の前兆のバチバチ(2026-10-18。ユーザー「前兆は先にバチバチなりながら細い雷が走り、そこから太い雷が走る」)。
 * ファイルを使わず、その場で合成した音を流す({@link WindSoundInstance} と同じ道。{@code sounds.json} にも載せない)。世界の中の 1 点から鳴り、前兆の長さだけ鳴って止まる。
 *
 * <ul>
 *   <li>放電の粒: 0.3〜3 ms で消える雑音の粒。大きさは小さい粒が多く大きい粒がまれ(3 乗)。</li>
 *   <li>束: 粒の出る速さを 80 ms ほどごとに揺らす(バチバチ…バチバチバチ)。終わり(太い雷)に向けて速く・大きく詰める。</li>
 *   <li>ジジッ: ときどき 10〜30 ms の唸り(120〜320 Hz の矩形波に雑音)。</li>
 * </ul>
 * 林の風の音では「とがった粒のバチバチ」は違和感だった(メモリ)が、これは雷の前兆として求められた音で、前兆の 2 秒ほどしか鳴らない。数値はすべて仮決め。
 */
@OnlyIn(Dist.CLIENT)
public final class CrackleSound extends AbstractTickableSoundInstance {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "lightning_crackle");
    /** 聞こえる距離(ブロック。直線で弱まる)。 */
    static final int RANGE = 96;
    private static int seeds = 1;

    private final int lifeTicks;
    private final int seed;
    private int age;

    /** (x, y, z) から ticks の間鳴らす。 */
    public static void play(double x, double y, double z, int ticks, float volume) {
        Minecraft.getInstance().getSoundManager().play(new CrackleSound(x, y, z, ticks, volume));
    }

    private CrackleSound(double x, double y, double z, int ticks, float volume) {
        super(SoundEvent.createVariableRangeEvent(ID), SoundSource.WEATHER, SoundInstance.createUnseededRandom());
        this.x = x;
        this.y = y;
        this.z = z;
        this.lifeTicks = ticks;
        this.volume = Math.min(1.0F, volume);
        this.looping = false;
        this.delay = 0;
        this.relative = false;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.seed = 0x2C4A7E1 + seeds++ * 7919;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        this.sound = new Sound(ID, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, true, false, RANGE);
        return new WeighedSoundEvents(ID, null);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new Stream(this.lifeTicks / 20.0, this.seed));
    }

    @Override
    public void tick() {
        if (++this.age > this.lifeTicks + 6) {
            this.stop();
        }
    }

    /** 合成した放電の音の流れ(モノラル。向きが付く)。長さを過ぎたら無音を返す(止めるのは音源の tick)。 */
    private static final class Stream implements AudioStream {
        static final int RATE = WindSynth.SAMPLE_RATE;
        static final int CHUNK = RATE / 10;

        private final double length;
        private final Random rng;
        private final AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        private long frame;
        private double burst = 0.5;
        private double burstTarget = 0.5;
        private double click;
        private double clickDecay = 0.9;
        private double buzzLeft;
        private double buzzPhase;
        private double buzzFreq;
        private double buzzAmp;
        private double prevIn;
        private double prevOut;

        Stream(double length, int seed) {
            this.length = Math.max(0.2, length);
            this.rng = new Random(seed);
        }

        @Override
        public AudioFormat getFormat() {
            return this.format;
        }

        @Override
        public ByteBuffer read(int size) {
            ByteBuffer buffer = BufferUtils.createByteBuffer(CHUNK * 2);
            for (int i = 0; i < CHUNK; i++, this.frame++) {
                double t = this.frame / (double) RATE;
                double u = t / this.length;
                double s = 0.0;
                if (u < 1.0) {
                    if (this.frame % (RATE / 12) == 0) {
                        // 束の強さを揺らす(ときどき静まる)。
                        this.burstTarget = this.rng.nextDouble() < 0.3 ? 0.12 : 0.55 + 0.45 * this.rng.nextDouble();
                    }
                    this.burst += (this.burstTarget - this.burst) * 0.003;
                    double rate = (60.0 + 1100.0 * u * u) * this.burst;
                    if (this.rng.nextDouble() < rate / RATE) {
                        double r = this.rng.nextDouble();
                        double amp = 0.06 + r * r * r * (0.5 + 0.5 * u);
                        if (amp > this.click) {
                            this.click = amp;
                            this.clickDecay = Math.exp(-1.0 / (RATE * (0.0003 + 0.0027 * this.rng.nextDouble())));
                        }
                    }
                    s += this.click * (this.rng.nextDouble() * 2.0 - 1.0);
                    this.click *= this.clickDecay;
                    if (this.buzzLeft <= 0.0 && this.rng.nextDouble() < (1.5 + 6.0 * u) / RATE) {
                        this.buzzLeft = RATE * (0.01 + 0.02 * this.rng.nextDouble());
                        this.buzzFreq = 120.0 + 200.0 * this.rng.nextDouble();
                        this.buzzAmp = 0.08 + 0.18 * this.rng.nextDouble() * (0.4 + 0.6 * u);
                    }
                    if (this.buzzLeft > 0.0) {
                        this.buzzPhase += this.buzzFreq / RATE;
                        double square = (this.buzzPhase - Math.floor(this.buzzPhase)) < 0.5 ? 1.0 : -1.0;
                        s += this.buzzAmp * square * (0.6 + 0.4 * this.rng.nextDouble());
                        this.buzzLeft--;
                    }
                    // 出だし 20 ms と終わり 40 ms をなめらかに。
                    s *= Math.min(1.0, t / 0.02) * Math.min(1.0, (this.length - t) / 0.04);
                }
                // 低い音のうなりを削る(1 次の高域通過)。
                double out = s - this.prevIn + 0.995 * this.prevOut;
                this.prevIn = s;
                this.prevOut = out;
                buffer.putShort((short) Math.round(Math.tanh(out * 1.4) * 0.9 * 32767.0));
            }
            buffer.flip();
            return buffer;
        }

        @Override
        public void close() {
        }
    }
}
