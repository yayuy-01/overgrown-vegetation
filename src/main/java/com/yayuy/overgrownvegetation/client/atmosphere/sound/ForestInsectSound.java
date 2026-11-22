package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.Atmosphere;
import com.yayuy.overgrownvegetation.client.atmosphere.ForestPresence;
import com.yayuy.overgrownvegetation.client.atmosphere.GustField;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * 森の<b>虫・爬虫類の声</b>の係(2026-10-31。鳥の代わり)。過繁茂の森では鳥の声が消える設定(一覧 32)なので、鳥の代わりに虫と爬虫類の声を置く
 * (10-31 ユーザー採用: a コオロギ系・c ヤモリ系。音は {@link InsectSynth}、見本 final_*.wav をユーザー了承「良かった」)。
 *
 * <ul>
 *     <li><b>コオロギ系</b>({@link InsectSynth.Cricket}): 鳥のいない古い時代の森の夜を、翅をこすって鳴く虫が埋める。{@code cricketFromHour}〜{@code cricketToHour} 時
 *     (日をまたぐ)、雨の最中は鳴かない。{@code cricketVoicesMin}〜{@code Max} 匹を {@code cricketDistanceMin}〜{@code Max} ブロック先の林床に置き、鳴き続ける
 *     (1 回の鳴きのあと 0.5 秒ほど休む)。プレイヤーが {@code cricketQuietDistance} ブロックまで寄るとその 1 匹は鳴き終えてから黙り、
 *     {@code cricketResumeSeconds} 秒たって離れていれば鳴き直す。離れすぎた 1 匹は鳴き終えてから黙り、別の所に置き直す。</li>
 *     <li><b>ヤモリ系</b>({@link InsectSynth.Gecko}): 小さな爬虫類が近くの幹に隠れている気配。{@code geckoFromHour}〜{@code geckoToHour} 時に
 *     {@code geckoIntervalMin}〜{@code Max} 秒おき、{@code geckoDistanceMin}〜{@code Max} ブロック先の原木(無ければ林床)で、チッを {@code geckoClicksMin}〜{@code Max} 回。</li>
 *     <li>10-31 夜 ユーザー採用(見本 01・02・04・05「いい感じ」)の 1 回きりの声 4 種。どれも {@code <種>FromHour}〜{@code ToHour} 時に
 *     {@code IntervalMin}〜{@code Max} 秒おき、{@code DistanceMin}〜{@code Max} ブロック先で鳴く:
 *     <b>昼の合唱</b>({@link InsectSynth.Chorus}、遠くの方向。大きな虫の体の震えで昼を埋める)・<b>キリギリス様</b>({@link InsectSynth.Katydid}、林床の 1 匹が
 *     区切りを {@code katydidPhrasesMin}〜{@code Max} 回。同時に {@code katydidVoicesMax} 匹まで)・<b>下草のガサガサ</b>({@link InsectSynth.Rustle}、大きな多足類が
 *     落ち葉を踏む、0.6〜1.5 秒)・<b>爬虫類の息とコッ</b>({@link InsectSynth.Reptile}、倒木・原木の根元)。
 *     11-01 ユーザー採用(見本 07b・08b)の動く音 2 種: <b>頭上の枝葉の揺れ</b>({@link InsectSynth.CanopyShake}、見えない大きなものが林冠を渡る。目の高さ +8〜+14、
 *     風が弱いときだけ)・<b>小枝の折れ</b>({@link InsectSynth.TwigSnap}、1 歩ぶんの折れを、ときどき周りの林床から)。種類を足すときは {@link Caller} を 1 つ足す。</li>
 *     <li>どれも森の度合い({@link ForestPresence#forestFactor})が {@code minForest} より下・水の中では鳴らない。ヤモリ以外は雨の最中も鳴かない。
 *     同時に鳴る声は全種で {@code maxVoices} まで(コオロギも数える。上限なら 1 回きりの声は 10 秒後に出直す)。大物の Mob が近いと黙る({@link #hushed})は
 *     判定の入口だけ(Mob が退避中なので今は常に false。半径は {@code hushRadius})。</li>
 * </ul>
 * 音の種類は「環境音」。大きさは音源からの距離で減る(線形。0 になる距離は {@link Caller#range} とコオロギ 32)。数値は {@code atmosphere.json} の節 {@code insects}(すべて 10-31 仮決め)。
 */
public final class ForestInsectSound {

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static final RandomSource RANDOM = RandomSource.create();
    /** 鳴く区間・凪の乱数(SongRhythm は Minecraft の型を使わないので java の Random)。 */
    private static final Random RHYTHM = new Random();
    /** 森全体の凪(11-01)。 */
    private static final SongRhythm.Lull LULL = new SongRhythm.Lull();
    private static boolean lull;

    /** 鳴いている声 1 つ(音のスレッドが読む値は volatile)。 */
    static final class Voice {
        final InsectSynth.Cricket cricket;
        /** 1 回きりの声(コオロギなら null)。 */
        final InsectSynth.Call call;
        /** 音の名前(forest_<種>)と、線形に減って 0 になる距離。 */
        final String kind;
        final int range;
        final double x;
        final double y;
        final double z;
        volatile float volume;
        volatile boolean singing = true;
        volatile boolean removed;
        InsectSoundInstance sound;
        /** 黙っている間は、この tick まで鳴き直さない。黙らせてから消すまでの tick。 */
        long mutedUntil;
        /** コオロギの鳴く区間・黙る区間(11-01)。 */
        final SongRhythm.Bouts bouts = new SongRhythm.Bouts();
        /** キリギリス様の鳴く区間の終わり(tick。-1 = 無し)。過ぎたら今の区切りを鳴き終えて黙る。 */
        long stopAt = -1L;
        int retireTicks = -1;

        Voice(InsectSynth.Cricket cricket, InsectSynth.Call call, String kind, int range, double x, double y, double z) {
            this.cricket = cricket;
            this.call = call;
            this.kind = kind;
            this.range = range;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** 音源を置く所の探し方。 */
    enum Spot {
        /** 林床(梢から下へたどった最初の地面の上)。 */
        FLOOR,
        /** 原木(無ければ林床)。 */
        TRUNK,
        /** 遠くの方向(地面を探さず、目の高さ +4 の点)。 */
        FAR,
        /** 頭上の林冠(地面を探さず、目の高さ +8〜+14 の点)。 */
        CANOPY
    }

    /**
     * 1 回きりの声の種類 1 つ(ヤモリと 10-31 夜の 4 種、11-01 の枝葉の揺れ・小枝の折れ)。いつ・どこで・どれだけ間をあけて鳴くかは {@link AtmosphereTable.CallParams}(毎 tick design から読む)。
     * 種類を足すときは {@link InsectSynth.Call} を作ってここへ 1 行足す。
     */
    static final class Caller {
        final String kind;
        /** 線形に減って 0 になる距離(ブロック。鳴らす距離の上限より少し遠く。仮決め)。 */
        final int range;
        /** 雨の最中は鳴かないか。 */
        final boolean dry;
        /** 風が弱いときだけ鳴るか(風の音の鳴り出し calmThreshold より弱いとき。強い風の葉ずれと混ざらないように)。 */
        final boolean calmOnly;
        /** 鳴く区間・休む区間で鳴くか(キリギリス様。鳴く区間は katydidSing 秒、休む区間は間隔 IntervalMin〜Max = 鳴き終えてから次まで。凪では鳴き始めない)。 */
        final boolean bouts;
        final Spot spot;
        final Function<AtmosphereTable.InsectsParams, AtmosphereTable.CallParams> params;
        /** 同じ種類が同時にいくつまで鳴くか。 */
        final ToIntFunction<AtmosphereTable.InsectsParams> maxSame;
        final Maker make;
        long next = -1L;
        int calls;

        Caller(String kind, int range, boolean dry, boolean calmOnly, Spot spot, Function<AtmosphereTable.InsectsParams, AtmosphereTable.CallParams> params,
               ToIntFunction<AtmosphereTable.InsectsParams> maxSame, Maker make) {
            this.kind = kind;
            this.range = range;
            this.dry = dry;
            this.calmOnly = calmOnly;
            this.bouts = kind.equals("forest_katydid");
            this.spot = spot;
            this.params = params;
            this.maxSame = maxSame;
            this.make = make;
        }
    }

    /** 声を作る(seed・設計値・プレイヤーからの距離)。 */
    interface Maker {
        InsectSynth.Call make(int seed, AtmosphereTable.InsectsParams p, double distance);
    }

    private static final List<Caller> CALLERS = List.of(
            new Caller("forest_gecko", 24, false, false, Spot.TRUNK, AtmosphereTable.InsectsParams::gecko, p -> 6,
                    (seed, p, d) -> new InsectSynth.Gecko(seed, p.geckoClicksMin() + RANDOM.nextInt(p.geckoClicksMax() - p.geckoClicksMin() + 1))),
            new Caller("forest_chorus", 96, true, false, Spot.FAR, AtmosphereTable.InsectsParams::chorus, p -> 1,
                    (seed, p, d) -> new InsectSynth.Chorus(seed, 2 + RANDOM.nextInt(2))),
            new Caller("forest_katydid", 36, true, false, Spot.FLOOR, AtmosphereTable.InsectsParams::katydid, AtmosphereTable.InsectsParams::katydidVoicesMax,
                    (seed, p, d) -> new InsectSynth.Katydid(seed, p.katydidPhrasesMin() + RANDOM.nextInt(p.katydidPhrasesMax() - p.katydidPhrasesMin() + 1))),
            new Caller("forest_rustle", 14, true, false, Spot.FLOOR, AtmosphereTable.InsectsParams::rustle, p -> 1,
                    (seed, p, d) -> new InsectSynth.Rustle(seed, 0.6 + 0.9 * RANDOM.nextDouble())),
            new Caller("forest_reptile", 24, true, false, Spot.TRUNK, AtmosphereTable.InsectsParams::reptile, p -> 1,
                    (seed, p, d) -> new InsectSynth.Reptile(seed)),
            new Caller("forest_canopy", 32, true, true, Spot.CANOPY, AtmosphereTable.InsectsParams::canopy, p -> 1,
                    (seed, p, d) -> new InsectSynth.CanopyShake(seed)),
            new Caller("forest_twig", 32, true, false, Spot.FLOOR, AtmosphereTable.InsectsParams::twig, p -> 1,
                    (seed, p, d) -> new InsectSynth.TwigSnap(seed, d)));

    private static final List<Voice> CRICKETS = new ArrayList<>();
    /** 鳴いている 1 回きりの声(全種)。 */
    private static final List<Voice> CALLS = new ArrayList<>();
    private static int wantCrickets = -1;
    private static float cricketFade;
    private static long ticks;
    private static int seeds = 1;
    private static int cricketsMuted;

    private ForestInsectSound() {
    }

    public static void stop() {
        for (Voice v : CRICKETS) {
            stopVoice(v);
        }
        for (Voice v : CALLS) {
            stopVoice(v);
        }
        CRICKETS.clear();
        CALLS.clear();
        wantCrickets = -1;
        cricketFade = 0.0F;
        LULL.reset();
        lull = false;
        for (Caller c : CALLERS) {
            c.next = -1L;
        }
    }

    private static void stopVoice(Voice v) {
        v.removed = true;
        if (v.sound != null) {
            Minecraft.getInstance().getSoundManager().stop(v.sound);
            v.sound = null;
        }
    }

    /** 毎 tick({@link com.yayuy.overgrownvegetation.client.atmosphere.Atmosphere} から)。 */
    public static void tick(Minecraft mc, ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.ambientInsects()) {
            if (DEV_LOG && CRICKETS.size() + CALLS.size() > 0) {
                OvergrownVegetation.LOGGER.info("ForestInsectSound 設定でオフ: 音源 {} を止める", CRICKETS.size() + CALLS.size());
            }
            stop();
            return;
        }
        ticks++;
        AtmosphereTable.InsectsParams p = DesignData.atmosphere().insects();
        // 音が 0 なら作らない(Claude 用のテストクライアントは音 0 なので、確かめのときだけは鳴らす = 無音で流す)。
        boolean audible = DEV_LOG || mc.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0.0F && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F;
        float hour = (float) (Math.floorMod(level.getDayTime(), 24000L) / 1000.0 + 6.0) % 24.0F;
        // 森全体の凪: lullIntervalMin〜Max 秒おきに lullMin〜Max 秒、コオロギ・キリギリス様が区切りで黙り、新しく鳴き始めない(11-01)。
        lull = LULL.tick(ticks, RHYTHM, p.lullIntervalMin(), p.lullIntervalMax(), p.lullMin(), p.lullMax());
        float forest = ForestPresence.overgrown();
        boolean place = audible && forest >= p.minForest() && !hushed(level, player, p.hushRadius()) && !player.isUnderWater();
        // 地下モードの分だけ下げる(地下では森の音を 1 − mix 倍。2026-11-01 地下と水中の環境音)。
        float forestWeight = Mth.clamp((forest - p.minForest()) / 0.2F, 0.0F, 1.0F) * (1.0F - com.yayuy.overgrownvegetation.client.atmosphere.CaveLights.mix());

        // ---- コオロギ
        boolean crickets = place && inHours(hour, p.cricketFromHour(), p.cricketToHour()) && level.getRainLevel(1.0F) < 0.2F;
        cricketFade += ((crickets ? 1.0F : 0.0F) - cricketFade) * 0.02F;
        if (crickets && wantCrickets < 0) {
            wantCrickets = p.cricketVoicesMin() + RANDOM.nextInt(p.cricketVoicesMax() - p.cricketVoicesMin() + 1);
        } else if (!crickets && CRICKETS.isEmpty()) {
            wantCrickets = -1;
        }
        double far = p.cricketDistanceMax() + 12.0;
        cricketsMuted = 0;
        for (int i = CRICKETS.size() - 1; i >= 0; i--) {
            Voice v = CRICKETS.get(i);
            double d = Math.sqrt(player.distanceToSqr(v.x, v.y, v.z));
            if (v.retireTicks < 0 && (!crickets || d > far)) {
                // 鳴き終えてから消す(鳴きの途中で切らない)。
                v.singing = false;
                v.retireTicks = 0;
            }
            if (v.retireTicks >= 0) {
                v.retireTicks++;
                if (v.retireTicks > 30 && !v.cricket.inChirp() || v.retireTicks > 100) {
                    stopVoice(v);
                    CRICKETS.remove(i);
                }
                continue;
            }
            if (d < p.cricketQuietDistance()) {
                v.mutedUntil = ticks + (long) (p.cricketResumeSeconds() * 20.0F);
            }
            // 鳴く区間・黙る区間(cricketSing / cricketRest 秒)と凪。黙るのは今の鳴きを鳴き終えてから(Cricket#setSinging)。
            boolean bout = v.bouts.tick(ticks, RHYTHM, p.cricketSingMin(), p.cricketSingMax(), p.cricketRestMin(), p.cricketRestMax());
            v.singing = ticks >= v.mutedUntil && bout && !lull;
            if (!v.singing) {
                cricketsMuted++;
            }
            v.volume = p.cricketVolume() * forestWeight * cricketFade;
            ensurePlaying(mc, v);
        }
        if (crickets && countLive(CRICKETS) < wantCrickets && countLive(CRICKETS) + CALLS.size() < p.maxVoices() && ticks % 20 == 0) {
            BlockPos at = floorSpot(level, player, p.cricketDistanceMin(), p.cricketDistanceMax());
            if (at != null) {
                Voice v = new Voice(new InsectSynth.Cricket(0x51C0_0000 + seeds++ * 7919), null, "forest_cricket", 32,
                        at.getX() + 0.5, at.getY() + 0.3, at.getZ() + 0.5);
                v.volume = 0.0F;
                CRICKETS.add(v);
                ensurePlaying(mc, v);
                if (DEV_LOG) {
                    OvergrownVegetation.LOGGER.info("ForestInsectSound コオロギを置く 位置 {} {} {}", at.getX(), at.getY(), at.getZ());
                }
            }
        }

        // ---- 1 回きりの声(ヤモリ・昼の合唱・キリギリス様・下草のガサガサ・爬虫類)
        for (int i = CALLS.size() - 1; i >= 0; i--) {
            Voice v = CALLS.get(i);
            // 鳴き終えた・音源が止まった・10 秒たっても鳴り始めない(音のエンジンが受け取らなかった)・2 分を超えたなら片付ける。
            if (v.stopAt >= 0L && (ticks >= v.stopAt || lull) && v.call instanceof InsectSynth.Katydid katydid) {
                katydid.requestStop();
                v.stopAt = -1L;
            }
            boolean started = v.sound != null && v.sound.started;
            if (v.call.done() || started && !mc.getSoundManager().isActive(v.sound) || ++v.retireTicks > (started ? 2400 : 200)) {
                stopVoice(v);
                CALLS.remove(i);
            }
        }
        boolean wet = level.getRainLevel(1.0F) >= 0.2F;
        boolean calm = GustField.INSTANCE.strength(player.getX(), player.getZ(), Atmosphere.time(1.0F)) < DesignData.atmosphere().sound().calmThreshold();
        StringBuilder log = DEV_LOG && ticks % 100 == 0 ? new StringBuilder() : null;
        for (Caller c : CALLERS) {
            AtmosphereTable.CallParams cp = c.params.apply(p);
            boolean on = place && inHours(hour, cp.fromHour(), cp.toHour()) && !(c.dry && wet) && (calm || !c.calmOnly) && !(c.bouts && lull);
            if (!on) {
                c.next = -1L;
            } else if (c.next < 0L) {
                c.next = ticks + interval(cp);
            } else if (ticks >= c.next) {
                if (countLive(CRICKETS) + CALLS.size() >= p.maxVoices() || countKind(c.kind) >= c.maxSame.applyAsInt(p)) {
                    // 上限: 10 秒後に出直す。
                    c.next = ticks + 200L;
                } else {
                    c.next = ticks + interval(cp);
                    startCall(mc, level, player, c, cp, p, forestWeight);
                }
            }
            if (log != null) {
                log.append(String.format(" / %s 鳴いた %d 回・次まで %d 秒", c.kind.substring(7), c.calls, c.next < 0L ? -1 : (c.next - ticks) / 20));
            }
        }
        if (log != null) {
            OvergrownVegetation.LOGGER.info("ForestInsectSound {}時 森 {} コオロギ {} 匹(黙っている {}・鳴っている音源 {})大きさ {}{} / 1 回きりの声 {} 個{}",
                    String.format("%.1f", hour), String.format("%.2f", forest), countLive(CRICKETS), cricketsMuted, countActive(mc, CRICKETS),
                    String.format("%.3f", p.cricketVolume() * forestWeight * cricketFade), lull ? "(凪)" : "", CALLS.size(), log);
        }
    }

    private static void startCall(Minecraft mc, ClientLevel level, LocalPlayer player, Caller c, AtmosphereTable.CallParams cp,
                                  AtmosphereTable.InsectsParams p, float forestWeight) {
        double x;
        double y;
        double z;
        if (c.spot == Spot.FAR || c.spot == Spot.CANOPY) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = cp.distanceMin() + RANDOM.nextDouble() * (cp.distanceMax() - cp.distanceMin());
            x = player.getX() + Math.cos(a) * r;
            y = player.getEyeY() + (c.spot == Spot.FAR ? 4.0 : 8.0 + 6.0 * RANDOM.nextDouble());
            z = player.getZ() + Math.sin(a) * r;
        } else {
            BlockPos at = c.spot == Spot.TRUNK ? trunkSpot(level, player, cp.distanceMin(), cp.distanceMax())
                    : floorSpot(level, player, cp.distanceMin(), cp.distanceMax());
            if (at == null) {
                return;
            }
            x = at.getX() + 0.5;
            y = at.getY() + (c.spot == Spot.TRUNK ? 0.5 : 0.3);
            z = at.getZ() + 0.5;
        }
        Voice v = new Voice(null, c.make.make(0x6E00_0000 + seeds++ * 104729, p, Math.sqrt(player.distanceToSqr(x, y, z))), c.kind, c.range, x, y, z);
        v.volume = cp.volume() * forestWeight;
        CALLS.add(v);
        if (c.bouts) {
            v.stopAt = ticks + (long) (SongRhythm.span(RHYTHM, p.katydidSingMin(), p.katydidSingMax()) * 20.0F);
            c.next = v.stopAt + interval(cp);
        }
        ensurePlaying(mc, v);
        c.calls++;
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("ForestInsectSound {} が鳴く 位置 {} {} {} 距離 {}", c.kind, String.format("%.1f", x), String.format("%.1f", y),
                    String.format("%.1f", z), String.format("%.1f", Math.sqrt(player.distanceToSqr(x, y, z))));
        }
    }

    private static int countKind(String kind) {
        int n = 0;
        for (Voice v : CALLS) {
            if (v.kind.equals(kind)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 大物の Mob が近いか(近いと虫が一斉に黙る。一覧 32 の「静けさ」)。<b>今は入口だけ</b>: Mob は退避中なので常に false(10-31 相談セッションの指示)。
     * Mob を戻すときに、半径 {@code radius} の中の大きな Mob(体格・グループで決める)を数える。
     */
    static boolean hushed(ClientLevel level, LocalPlayer player, float radius) {
        return false;
    }

    private static void ensurePlaying(Minecraft mc, Voice v) {
        if (v.sound == null || !mc.getSoundManager().isActive(v.sound) && v.sound.started && v.cricket != null) {
            v.sound = new InsectSoundInstance(v);
            mc.getSoundManager().play(v.sound);
        }
    }

    private static int countLive(List<Voice> list) {
        int n = 0;
        for (Voice v : list) {
            if (v.retireTicks < 0) {
                n++;
            }
        }
        return n;
    }

    private static int countActive(Minecraft mc, List<Voice> list) {
        int n = 0;
        for (Voice v : list) {
            if (v.sound != null && mc.getSoundManager().isActive(v.sound)) {
                n++;
            }
        }
        return n;
    }

    private static long interval(AtmosphereTable.CallParams cp) {
        return (long) ((cp.intervalMin() + RANDOM.nextFloat() * (cp.intervalMax() - cp.intervalMin())) * 20.0F);
    }

    /** 時刻 hour が from〜to 時の中か(from > to なら日をまたぐ)。 */
    static boolean inHours(float hour, float from, float to) {
        return from <= to ? hour >= from && hour < to : hour >= from || hour < to;
    }

    /** min〜max ブロック先の林床(梢から下へたどり、葉・幹・草を抜けた最初の地面の上)。目の高さから ±12 に無ければ null。 */
    private static BlockPos floorSpot(ClientLevel level, LocalPlayer player, float min, float max) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int eye = Mth.floor(player.getEyeY());
        for (int attempt = 0; attempt < 8; attempt++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = min + RANDOM.nextDouble() * (max - min);
            int x = Mth.floor(player.getX() + Math.cos(a) * r);
            int z = Mth.floor(player.getZ() + Math.sin(a) * r);
            if (!level.hasChunkAt(pos.set(x, eye, z))) {
                continue;
            }
            // クライアントの高さ地図は WORLD_SURFACE と MOTION_BLOCKING だけ(memory client-heightmaps-only-two)。
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
            for (int y = Math.min(top, eye + 12); y >= eye - 12; y--) {
                BlockState s = level.getBlockState(pos.set(x, y, z));
                if (s.blocksMotion() && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS)) {
                    if (y + 1 > eye - 12) {
                        return new BlockPos(x, y + 1, z);
                    }
                    break;
                }
            }
        }
        return null;
    }

    /** min〜max ブロック先の原木(目の高さ −6〜+4)。無ければ林床。 */
    private static BlockPos trunkSpot(ClientLevel level, LocalPlayer player, float min, float max) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int eye = Mth.floor(player.getEyeY());
        for (int attempt = 0; attempt < 12; attempt++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = min + RANDOM.nextDouble() * (max - min);
            int x = Mth.floor(player.getX() + Math.cos(a) * r);
            int z = Mth.floor(player.getZ() + Math.sin(a) * r);
            for (int y = eye + 4; y >= eye - 6; y--) {
                if (level.getBlockState(pos.set(x, y, z)).is(BlockTags.LOGS)) {
                    return pos.immutable();
                }
            }
        }
        return floorSpot(level, player, min, max);
    }
}
