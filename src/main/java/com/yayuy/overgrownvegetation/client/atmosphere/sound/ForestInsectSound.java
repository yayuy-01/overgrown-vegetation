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
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

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
 *     <li>どちらも森の度合い({@link ForestPresence#forestFactor})が {@code minForest} より下では鳴らない。大物の Mob が近いと黙る({@link #hushed})は
 *     判定の入口だけ(Mob が退避中なので今は常に false。半径は {@code hushRadius})。</li>
 * </ul>
 * 音の種類は「環境音」。大きさは音源からの距離で減る(線形、32 / 24 ブロックで 0)。数値は {@code atmosphere.json} の節 {@code insects}(すべて 10-31 仮決め)。
 */
public final class ForestInsectSound {

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static final RandomSource RANDOM = RandomSource.create();

    /** 鳴いている声 1 つ(音のスレッドが読む値は volatile)。 */
    static final class Voice {
        final InsectSynth.Cricket cricket;
        final InsectSynth.Gecko gecko;
        final double x;
        final double y;
        final double z;
        volatile float volume;
        volatile boolean singing = true;
        volatile boolean removed;
        InsectSoundInstance sound;
        /** 黙っている間は、この tick まで鳴き直さない。黙らせてから消すまでの tick。 */
        long mutedUntil;
        int retireTicks = -1;

        Voice(InsectSynth.Cricket cricket, InsectSynth.Gecko gecko, double x, double y, double z) {
            this.cricket = cricket;
            this.gecko = gecko;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final List<Voice> CRICKETS = new ArrayList<>();
    private static final List<Voice> GECKOS = new ArrayList<>();
    private static int wantCrickets = -1;
    private static float cricketFade;
    private static long nextGecko = -1L;
    private static long ticks;
    private static int seeds = 1;
    private static int geckoCalls;
    private static int cricketsMuted;

    private ForestInsectSound() {
    }

    public static void stop() {
        for (Voice v : CRICKETS) {
            stopVoice(v);
        }
        for (Voice v : GECKOS) {
            stopVoice(v);
        }
        CRICKETS.clear();
        GECKOS.clear();
        wantCrickets = -1;
        cricketFade = 0.0F;
        nextGecko = -1L;
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
        ticks++;
        AtmosphereTable.InsectsParams p = DesignData.atmosphere().insects();
        // 音が 0 なら作らない(Claude 用のテストクライアントは音 0 なので、確かめのときだけは鳴らす = 無音で流す)。
        boolean audible = DEV_LOG || mc.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0.0F && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F;
        float hour = (float) (Math.floorMod(level.getDayTime(), 24000L) / 1000.0 + 6.0) % 24.0F;
        float forest = ForestPresence.forestFactor();
        boolean place = audible && forest >= p.minForest() && !hushed(level, player, p.hushRadius()) && !player.isUnderWater();
        float forestWeight = Mth.clamp((forest - p.minForest()) / 0.2F, 0.0F, 1.0F);

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
            v.singing = ticks >= v.mutedUntil;
            if (!v.singing) {
                cricketsMuted++;
            }
            v.volume = p.cricketVolume() * forestWeight * cricketFade;
            ensurePlaying(mc, v);
        }
        if (crickets && countLive(CRICKETS) < wantCrickets && ticks % 20 == 0) {
            BlockPos at = floorSpot(level, player, p.cricketDistanceMin(), p.cricketDistanceMax());
            if (at != null) {
                Voice v = new Voice(new InsectSynth.Cricket(0x51C0_0000 + seeds++ * 7919), null, at.getX() + 0.5, at.getY() + 0.3, at.getZ() + 0.5);
                v.volume = 0.0F;
                CRICKETS.add(v);
                ensurePlaying(mc, v);
                if (DEV_LOG) {
                    OvergrownVegetation.LOGGER.info("ForestInsectSound コオロギを置く 位置 {} {} {}", at.getX(), at.getY(), at.getZ());
                }
            }
        }

        // ---- ヤモリ
        for (int i = GECKOS.size() - 1; i >= 0; i--) {
            Voice v = GECKOS.get(i);
            // 鳴き終えた・音源が止まった・10 秒たっても鳴らない(音のエンジンが受け取らなかった)なら片付ける。
            if (v.gecko.done() || v.sound != null && !mc.getSoundManager().isActive(v.sound) && v.sound.started || ++v.retireTicks > 200) {
                stopVoice(v);
                GECKOS.remove(i);
            }
        }
        boolean geckos = place && inHours(hour, p.geckoFromHour(), p.geckoToHour());
        if (!geckos) {
            nextGecko = -1L;
        } else if (nextGecko < 0L) {
            nextGecko = ticks + interval(p);
        } else if (ticks >= nextGecko) {
            nextGecko = ticks + interval(p);
            BlockPos at = trunkSpot(level, player, p.geckoDistanceMin(), p.geckoDistanceMax());
            if (at != null) {
                int clicks = p.geckoClicksMin() + RANDOM.nextInt(p.geckoClicksMax() - p.geckoClicksMin() + 1);
                Voice v = new Voice(null, new InsectSynth.Gecko(0x6E00_0000 + seeds++ * 104729, clicks), at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
                v.volume = p.geckoVolume() * forestWeight;
                GECKOS.add(v);
                ensurePlaying(mc, v);
                geckoCalls++;
                if (DEV_LOG) {
                    OvergrownVegetation.LOGGER.info("ForestInsectSound ヤモリが鳴く チッ {} 回 位置 {} {} {} 距離 {}", clicks, at.getX(), at.getY(), at.getZ(),
                            String.format("%.1f", Math.sqrt(player.distanceToSqr(v.x, v.y, v.z))));
                }
            }
        }
        if (DEV_LOG && ticks % 100 == 0) {
            OvergrownVegetation.LOGGER.info("ForestInsectSound {}時 森 {} コオロギ {} 匹(黙っている {}・鳴っている音源 {})大きさ {} / ヤモリ 鳴いた {} 回・次まで {} 秒",
                    String.format("%.1f", hour), String.format("%.2f", forest), countLive(CRICKETS), cricketsMuted, countActive(mc, CRICKETS),
                    String.format("%.3f", p.cricketVolume() * forestWeight * cricketFade), geckoCalls, nextGecko < 0L ? -1 : (nextGecko - ticks) / 20);
        }
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

    private static long interval(AtmosphereTable.InsectsParams p) {
        return (long) ((p.geckoIntervalMin() + RANDOM.nextFloat() * (p.geckoIntervalMax() - p.geckoIntervalMin())) * 20.0F);
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
