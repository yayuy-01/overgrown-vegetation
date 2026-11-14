package com.yayuy.overgrownvegetation.client.voice;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 汚した声を<b>1 回だけ加工して</b>、ふつうの効果音と同じ「全部読む音」のバッファーとして持つ(2026-11-14)。
 * 流す音の枠(全体で 8 本)を使わないので、群れが一度に鳴いても黙らない。
 *
 * <ul>
 *     <li>鍵 = 元の音のファイル・版・帯・強さ。鍵ごとに偽の道 {@code overgrown_vegetation:sounds/crushed/<版>/<帯>/<強さ>/<元の名前空間>/<元の道>.ogg}
 *         を作り、{@code SoundBufferLibraryCrushedMixin} がその道のときだけここのバッファーを返す。</li>
 *     <li>初めての音: 加工が済むまでは今までどおり流して鳴らし({@link CrushedAudioStream}。黙らせない)、裏で加工する。済んだ次の回から持っている方で鳴る。</li>
 *     <li>加工は IO のスレッド(バニラが ogg を全部読むのと同じ {@code Util.nonCriticalIoPool()})。{@link SoundBuffer} はそこで作り、
 *         OpenAL へ渡すのはバニラどおり音のスレッド(鳴らすときの {@code attachStaticBuffer})。</li>
 *     <li>持つ数の上限 {@value #MAX_ENTRIES}【仮】。超えたら長く鳴っていない古いものから捨てる(最後に鳴らしてから {@value #KEEP_MS} ミリ秒【仮】
 *         経っていないものは捨てない = 再生中のものを捨てない)。捨てるときは OpenAL のバッファーも放す。</li>
 *     <li>再読み込み(F3+T・リソースパックの変更)ではバニラのキャッシュと一緒に全部捨てる(Mixin の clear)。</li>
 *     <li>{@value #MAX_SECONDS} 秒【仮】より長い音は持たない(流す方式のまま)。</li>
 *     <li>Mixin が効いていない(注入先が見つからない)ときは、ここを使わず毎回流す方式で鳴らす({@link #checkHook})。</li>
 * </ul>
 * ここは描画のスレッドから呼ばれる(SoundEngine の play・resolve)。加工の完了だけ IO のスレッドなので、全部 synchronized。
 */
@OnlyIn(Dist.CLIENT)
public final class CrushedVoiceCache {

    static final int MAX_ENTRIES = 256;
    static final long KEEP_MS = 30_000L;
    static final double MAX_SECONDS = 10.0;
    private static final String PREFIX = "sounds/crushed/";
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    /** Mixin が効いているかを確かめる目印。 */
    private static final ResourceLocation PROBE = OvergrownVegetation.id(PREFIX + "probe.ogg");
    private static final CompletableFuture<SoundBuffer> PROBE_FUTURE = new CompletableFuture<>();

    private static final Map<ResourceLocation, Entry> ENTRIES = new LinkedHashMap<>(64, 0.75F, true);
    /** 0 = まだ確かめていない・1 = 効いている・-1 = 効いていない。 */
    private static int hook;
    private static boolean checked;

    private static final class Entry {
        final CompletableFuture<SoundBuffer> future;
        long lastUsed;

        Entry(CompletableFuture<SoundBuffer> future) {
            this.future = future;
            this.lastUsed = System.currentTimeMillis();
        }

        boolean ready() {
            return this.future.isDone() && !this.future.isCompletedExceptionally();
        }
    }

    private CrushedVoiceCache() {
    }

    /** Mixin から: 自前の道ならここのバッファー(無ければ失敗した future。バニラに偽の道を読みに行かせない)、ほかの道は null。 */
    @Nullable
    public static synchronized CompletableFuture<SoundBuffer> lookup(ResourceLocation path) {
        if (!OvergrownVegetation.MODID.equals(path.getNamespace()) || !path.getPath().startsWith(PREFIX)) {
            return null;
        }
        if (path.equals(PROBE)) {
            return PROBE_FUTURE;
        }
        Entry e = ENTRIES.get(path);
        if (e == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("汚した声のバッファーが無い: " + path));
        }
        e.lastUsed = System.currentTimeMillis();
        return e.future;
    }

    /** Mixin から: 再読み込みで全部捨てる(OpenAL のバッファーも)。 */
    public static synchronized void clear() {
        int n = ENTRIES.size();
        for (Entry e : ENTRIES.values()) {
            e.future.thenAccept(SoundBuffer::discardAlBuffer);
        }
        ENTRIES.clear();
        if (DEV_LOG && n > 0) {
            OvergrownVegetation.LOGGER.info("CrushedVoiceCache 再読み込みで {} 個を捨てた", n);
        }
    }

    /** Mixin から: SoundBufferLibrary を作ったとき(起動時)。差し込みが効いている印。 */
    public static synchronized void hookAlive() {
        if (hook == 0) {
            hook = 1;
        }
    }

    /**
     * 流す方式で鳴らしたとき(SoundBufferLibrary が手に入る所)に、取り出しの差し込みが本当に効いているかを 1 回だけ確かめる
     * (作るときの注入だけ効いて取り出しの注入が見つからなかった、を見分ける)。
     */
    static synchronized void checkHook(SoundBufferLibrary library) {
        if (checked) {
            return;
        }
        checked = true;
        hook = library.getCompleteBuffer(PROBE) == PROBE_FUTURE ? 1 : -1;
        if (hook < 0) {
            OvergrownVegetation.LOGGER.warn("汚した声: SoundBufferLibrary への差し込みが効いていない。毎回流す方式で鳴らす(同時に鳴らせる数が少ない)");
        } else if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("CrushedVoiceCache 差し込みが効いている");
        }
    }

    /**
     * 加工中なら、その完了を返す(済んでから鳴らし直すため)。差し込みが効いていない・済んだ・失敗した(長すぎる音など)・頼んでいないなら null。
     * {@link #staticLocation} が null を返した直後に呼ぶ。
     */
    @Nullable
    static synchronized CompletableFuture<SoundBuffer> building(ResourceLocation location, int mode, boolean small, double strength) {
        if (hook <= 0) {
            return null;
        }
        Entry e = ENTRIES.get(fakeLocation(location, mode, small, strength).withPath(p -> "sounds/" + p + ".ogg"));
        return e != null && !e.future.isDone() ? e.future : null;
    }

    private static ResourceLocation fakeLocation(ResourceLocation location, int mode, boolean small, double strength) {
        return OvergrownVegetation.id("crushed/" + mode + "/" + (small ? "s" : "m") + "/" + Math.round(strength * 100)
                + "/" + location.getNamespace() + "/" + location.getPath());
    }

    /**
     * 持っている方で鳴らせるなら偽の場所(Sound の場所)を返す。まだなら null を返し、まだ頼んでいなければ裏で加工を始める。
     *
     * @param location 元の Sound の場所(例 minecraft:mob/cow/say1)
     * @param path     元の音のファイル(例 minecraft:sounds/mob/cow/say1.ogg)
     */
    @Nullable
    static synchronized ResourceLocation staticLocation(ResourceLocation location, ResourceLocation path,
                                                        int mode, boolean small, double strength) {
        if (hook < 0) {
            return null;
        }
        ResourceLocation fake = fakeLocation(location, mode, small, strength);
        ResourceLocation fakePath = fake.withPath(p -> "sounds/" + p + ".ogg");
        Entry e = ENTRIES.get(fakePath);
        if (e == null) {
            ENTRIES.put(fakePath, new Entry(CompletableFuture.supplyAsync(() -> build(path, fake, mode, small, strength), Util.nonCriticalIoPool())));
            evict();
            return null;
        }
        return hook > 0 && e.ready() ? fake : null;
    }

    /** 上限を超えたら、長く鳴っていない古いものから捨てる。 */
    private static void evict() {
        if (ENTRIES.size() <= MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        List<ResourceLocation> drop = new ArrayList<>();
        int over = ENTRIES.size() - MAX_ENTRIES;
        for (Iterator<Map.Entry<ResourceLocation, Entry>> it = ENTRIES.entrySet().iterator(); it.hasNext() && drop.size() < over; ) {
            Map.Entry<ResourceLocation, Entry> me = it.next();
            Entry e = me.getValue();
            if (e.future.isDone() && now - e.lastUsed > KEEP_MS) {
                drop.add(me.getKey());
            }
        }
        for (ResourceLocation key : drop) {
            Entry e = ENTRIES.remove(key);
            e.future.thenAccept(SoundBuffer::discardAlBuffer);
        }
        if (DEV_LOG && !drop.isEmpty()) {
            OvergrownVegetation.LOGGER.info("CrushedVoiceCache 上限で {} 個を捨てた(残り {})", drop.size(), ENTRIES.size());
        }
    }

    /** 元の ogg を全部読み、加工して、全部読む音のバッファーにする(IO のスレッド)。 */
    private static SoundBuffer build(ResourceLocation path, ResourceLocation fake, int mode, boolean small, double strength) {
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(path);
             AudioStream stream = new JOrbisAudioStream(in)) {
            AudioFormat f = stream.getFormat();
            int ch = f.getChannels();
            float sr = f.getSampleRate();
            int maxFrames = (int) (sr * MAX_SECONDS);
            float[] x = new float[Math.max(1, (int) sr)];
            int length = 0;
            while (true) {
                ByteBuffer b = stream.read((int) sr * 2 * ch);
                if (b == null || !b.hasRemaining()) {
                    break;
                }
                int frames = b.remaining() / (2 * ch);
                if (length + frames > maxFrames) {
                    throw new IllegalStateException("長すぎるので持たない(" + MAX_SECONDS + " 秒より長い)");
                }
                if (length + frames > x.length) {
                    x = java.util.Arrays.copyOf(x, Math.max(x.length * 2, length + frames));
                }
                CrushedAudioStream.readMono(b, ch, x, length, frames);
                length += frames;
            }
            VoiceCrusher crusher = new VoiceCrusher(sr, mode,
                    small ? VoiceCrusher.SMALL_LO : VoiceCrusher.MID_LO, small ? VoiceCrusher.SMALL_HI : VoiceCrusher.MID_HI, strength);
            float g = CrushedAudioStream.shape(x, length, sr, crusher, true);
            ByteBuffer pcm = CrushedAudioStream.toPcm(x, length, g);
            if (DEV_LOG) {
                OvergrownVegetation.LOGGER.info("CrushedVoiceCache 加工して持った {}({} フレーム・倍率 {})", fake, length, String.format("%.3f", g));
            }
            return new SoundBuffer(pcm, new AudioFormat(sr, 16, 1, true, false));
        } catch (Exception e) {
            if (DEV_LOG) {
                OvergrownVegetation.LOGGER.info("CrushedVoiceCache 持たない {}: {}", path, e.toString());
            }
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    /** 確かめ用: 今持っている数(加工中を含む)。 */
    static synchronized int size() {
        return ENTRIES.size();
    }
}
