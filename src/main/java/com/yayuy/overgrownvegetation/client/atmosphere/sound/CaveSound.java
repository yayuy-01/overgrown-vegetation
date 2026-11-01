package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.atmosphere.CaveLights;
import com.yayuy.overgrownvegetation.data.AtmosphereSound;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>地下の環境音</b>の係(2026-11-01。見本 C1・C2・C3 をユーザー採用。合成は {@link CaveWaterSynth})。過繁茂の地域の地下(地下モードの係数 {@link CaveLights#mix})で鳴る。
 *
 * <ul>
 *     <li><b>滴</b>(C1): 黒い霧で見通しが効かない洞窟の広さと湿り気を耳で。mix が {@code minMix} を超えている間、{@code dripIntervalMin}〜{@code Max} 秒に 1 滴
 *     (雨の最中は間隔 × {@code dripRainScale})。{@code dripDistanceMin}〜{@code Max} ブロック先の天井のある列で、真下が水なら水面の滴・石なら岩の滴。
 *     大きさ {@code dripVolume}、線形に減って {@code dripRange} で 0。</li>
 *     <li><b>根の軋み</b>(C2): 巨大樹の根が天井を貫いている。{@code creakIntervalMin}〜{@code Max} 秒に 1 回、{@code creakDistanceMin}〜{@code Max} ブロック先から。
 *     半径 {@code creakRootRadius} にタグ {@code #overgrown_vegetation:cave_creak_roots} のブロックがあれば、一番近いものの向き(無ければ任意の向き)。</li>
 *     <li><b>胞子の溜まりのうなり</b>(C3): タグ {@code #overgrown_vegetation:spore_pool} のブロック(今は空のタグ。地下の植生で溜まりを足すと鳴り出す)の
 *     一番近いものから、半径 {@code humRadius} の中で 近さ(0〜1)の 2 乗 × {@code humVolume}。流し続け、離れると消える。mix に関係なく鳴る。</li>
 * </ul>
 * 単発の音は虫の声と同じ音源({@link InsectSoundInstance} に {@link ForestInsectSound.Voice} を渡す)。音の種類は「環境音」。数値は {@code atmosphere.json} の節 {@code sound.cave}(仮決め)。
 * 森の音(虫・風)は地下モードの分だけ下がる(1 − mix 倍。それぞれの係で掛ける)。
 */
public final class CaveSound {

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static final RandomSource RANDOM = RandomSource.create();

    private static final List<ForestInsectSound.Voice> CALLS = new ArrayList<>();
    private static long ticks;
    private static long nextDrip = -1L;
    private static long nextCreak = -1L;
    private static int seeds = 1;
    private static int drips;
    private static int creaks;
    @Nullable
    private static BlockPos root;
    private static long rootScannedAt = -1_000_000L;

    // ---- うなり(C3)
    private static final Hum HUM = new Hum();
    @Nullable
    private static LoopSoundInstance humSound;
    @Nullable
    private static BlockPos pool;

    private CaveSound() {
    }

    /** うなりの音源の出どころ(大きさ・位置は係が毎 tick 決める)。 */
    private static final class Hum implements LoopSoundInstance.Source {
        private final CaveWaterSynth.Hum synth = new CaveWaterSynth.Hum(0x4B0A_0001);
        volatile float gain;
        float target;
        volatile double x;
        volatile double y;
        volatile double z;

        @Override
        public float volume() {
            return this.gain;
        }

        @Override
        public double x() {
            return this.x;
        }

        @Override
        public double y() {
            return this.y;
        }

        @Override
        public double z() {
            return this.z;
        }

        @Override
        public boolean shouldStop(LoopSoundInstance instance) {
            return instance != humSound;
        }

        @Override
        public void render(float[] out, int frames) {
            this.synth.render(out, 0, frames, 1.0);
        }
    }

    public static void stop() {
        for (ForestInsectSound.Voice v : CALLS) {
            stopVoice(v);
        }
        CALLS.clear();
        nextDrip = -1L;
        nextCreak = -1L;
        root = null;
        rootScannedAt = -1_000_000L;
        if (humSound != null) {
            Minecraft.getInstance().getSoundManager().stop(humSound);
            humSound = null;
        }
        HUM.gain = 0.0F;
        HUM.target = 0.0F;
        pool = null;
    }

    private static void stopVoice(ForestInsectSound.Voice v) {
        v.removed = true;
        if (v.sound != null) {
            Minecraft.getInstance().getSoundManager().stop(v.sound);
            v.sound = null;
        }
    }

    /** 毎 tick({@link com.yayuy.overgrownvegetation.client.atmosphere.Atmosphere} から)。 */
    public static void tick(Minecraft mc, ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.ambientCave()) {
            if (!CALLS.isEmpty() || humSound != null) {
                stop();
            }
            return;
        }
        ticks++;
        AtmosphereSound.CaveSoundParams p = DesignData.atmosphere().sound().cave();
        // 音が 0 なら作らない(Claude 用のテストクライアントは音 0 なので、確かめのときだけは鳴らす = 無音で流す)。
        boolean audible = DEV_LOG || mc.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0.0F && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F;
        float mix = CaveLights.mix();
        boolean cave = audible && mix > p.minMix() && !player.isUnderWater();

        for (int i = CALLS.size() - 1; i >= 0; i--) {
            ForestInsectSound.Voice v = CALLS.get(i);
            boolean started = v.sound != null && v.sound.started;
            if (v.call.done() || started && !mc.getSoundManager().isActive(v.sound) || ++v.retireTicks > (started ? 600 : 200)) {
                if (DEV_LOG) {
                    OvergrownVegetation.LOGGER.info("CaveSound {} を片付ける(流れた {}・鳴り終えた {}・{} 秒)", v.kind, started, v.call.done(), v.retireTicks / 20);
                }
                stopVoice(v);
                CALLS.remove(i);
            }
        }

        // ---- 滴
        if (!cave) {
            nextDrip = -1L;
        } else if (nextDrip < 0L) {
            nextDrip = ticks + dripInterval(level, p);
        } else if (ticks >= nextDrip) {
            nextDrip = ticks + dripInterval(level, p);
            drip(mc, level, player, p);
        }

        // ---- 根の軋み
        if (!cave) {
            nextCreak = -1L;
        } else {
            if (ticks - rootScannedAt >= 200L) {
                rootScannedAt = ticks;
                root = nearest(level, player.blockPosition(), Math.round(p.creakRootRadius()), ModTags.CAVE_CREAK_ROOTS);
            }
            if (nextCreak < 0L) {
                nextCreak = ticks + span(p.creakIntervalMin(), p.creakIntervalMax());
            } else if (ticks >= nextCreak) {
                nextCreak = ticks + span(p.creakIntervalMin(), p.creakIntervalMax());
                creak(mc, player, p);
            }
        }

        // ---- 胞子の溜まりのうなり
        if (ticks % 20 == 7) {
            pool = audible ? nearest(level, BlockPos.containing(player.getEyePosition()), Mth.ceil(p.humRadius()), ModTags.SPORE_POOL) : null;
        }
        float near = 0.0F;
        if (pool != null) {
            double d = Math.sqrt(player.distanceToSqr(pool.getX() + 0.5, pool.getY() + 0.5, pool.getZ() + 0.5));
            near = Mth.clamp(1.0F - (float) d / p.humRadius(), 0.0F, 1.0F);
            HUM.x = pool.getX() + 0.5;
            HUM.y = pool.getY() + 0.5;
            HUM.z = pool.getZ() + 0.5;
        }
        HUM.target = p.humVolume() * near * near;
        HUM.gain += (HUM.target - HUM.gain) * 0.1F;
        if (HUM.gain > 0.003F) {
            if (humSound == null || !mc.getSoundManager().isActive(humSound)) {
                humSound = new LoopSoundInstance("cave_hum", HUM, false);
                mc.getSoundManager().play(humSound);
            }
        } else if (humSound != null && HUM.target <= 0.0F && HUM.gain < 0.001F) {
            mc.getSoundManager().stop(humSound);
            humSound = null;
        }

        if (DEV_LOG && ticks % 100 == 0) {
            int playing = 0;
            for (ForestInsectSound.Voice v : CALLS) {
                if (v.sound != null && v.sound.started) {
                    playing++;
                }
            }
            OvergrownVegetation.LOGGER.info("CaveSound 地下 {} 滴 {} 回(次まで {} 秒)軋み {} 回(次まで {} 秒・根 {})流れている単発 {} / うなり 溜まり {} 大きさ {} 流れ {}",
                    String.format("%.2f", mix), drips, nextDrip < 0L ? -1 : (nextDrip - ticks) / 20, creaks, nextCreak < 0L ? -1 : (nextCreak - ticks) / 20,
                    root == null ? "なし" : root.toShortString(), playing, pool == null ? "なし" : pool.toShortString(), String.format("%.3f", HUM.gain),
                    humSound != null && humSound.streaming ? "あり" : "なし");
        }
    }

    private static long dripInterval(ClientLevel level, AtmosphereSound.CaveSoundParams p) {
        long t = span(p.dripIntervalMin(), p.dripIntervalMax());
        return level.getRainLevel(1.0F) >= 0.2F ? Math.max(20L, (long) (t * p.dripRainScale())) : t;
    }

    private static long span(float min, float max) {
        return (long) ((min + RANDOM.nextFloat() * (max - min)) * 20.0F);
    }

    /** 1 滴: 天井のある列を探し、真下の水面・石の上に置く。見つからなければこの回は鳴らさない。 */
    private static void drip(Minecraft mc, ClientLevel level, LocalPlayer player, AtmosphereSound.CaveSoundParams p) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int eye = Mth.floor(player.getEyeY());
        for (int attempt = 0; attempt < 10; attempt++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = p.dripDistanceMin() + RANDOM.nextDouble() * (p.dripDistanceMax() - p.dripDistanceMin());
            int x = Mth.floor(player.getX() + Math.cos(a) * r);
            int z = Mth.floor(player.getZ() + Math.sin(a) * r);
            if (!level.hasChunkAt(pos.set(x, eye, z))) {
                continue;
            }
            // 目の高さ ±6 で空いたマス → 上へ 16 までに天井 → 下へ 24 までに床(水か、動きを止めるブロック)。
            int air = Integer.MIN_VALUE;
            for (int dy = 0; dy <= 6 && air == Integer.MIN_VALUE; dy++) {
                if (open(level, pos.set(x, eye + dy, z))) {
                    air = eye + dy;
                } else if (open(level, pos.set(x, eye - dy, z))) {
                    air = eye - dy;
                }
            }
            if (air == Integer.MIN_VALUE) {
                continue;
            }
            int ceiling = Integer.MIN_VALUE;
            for (int y = air + 1; y <= air + 16; y++) {
                if (level.getBlockState(pos.set(x, y, z)).blocksMotion()) {
                    ceiling = y;
                    break;
                }
            }
            if (ceiling == Integer.MIN_VALUE) {
                continue;
            }
            for (int y = air; y >= air - 24; y--) {
                pos.set(x, y, z);
                boolean water = level.getFluidState(pos).is(FluidTags.WATER);
                if (water || level.getBlockState(pos).blocksMotion()) {
                    double fy = y + (water ? 0.9 : 1.0);
                    play(mc, new CaveWaterSynth.Drip(0x0D21_0000 + seeds++ * 7919, water, Math.sqrt(player.distanceToSqr(x + 0.5, fy, z + 0.5))),
                            water ? "cave_drip_pool" : "cave_drip_rock", p.dripRange(), x + 0.5, fy, z + 0.5, p.dripVolume());
                    drips++;
                    if (DEV_LOG) {
                        OvergrownVegetation.LOGGER.info("CaveSound 滴({}) 位置 {} {} {} 天井 {} 距離 {}", water ? "水面" : "岩", x, String.format("%.1f", fy), z,
                                ceiling, String.format("%.1f", Math.sqrt(player.distanceToSqr(x + 0.5, fy, z + 0.5))));
                    }
                    return;
                }
            }
        }
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("CaveSound 滴を置く列が見つからなかった");
        }
    }

    private static boolean open(ClientLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !s.blocksMotion() && s.getFluidState().isEmpty();
    }

    /** 1 回の軋み: 根があればその向き、無ければ任意の向きの creakDistance ブロック先(目の高さ +4)。 */
    private static void creak(Minecraft mc, LocalPlayer player, AtmosphereSound.CaveSoundParams p) {
        double a;
        if (root != null) {
            a = Math.atan2(root.getZ() + 0.5 - player.getZ(), root.getX() + 0.5 - player.getX()) + (RANDOM.nextDouble() - 0.5) * 0.5;
        } else {
            a = RANDOM.nextDouble() * Math.PI * 2.0;
        }
        double r = p.creakDistanceMin() + RANDOM.nextDouble() * (p.creakDistanceMax() - p.creakDistanceMin());
        double x = player.getX() + Math.cos(a) * r;
        double y = player.getEyeY() + 4.0;
        double z = player.getZ() + Math.sin(a) * r;
        play(mc, new CaveWaterSynth.Creak(0xC4EA_0000 + seeds++ * 104729, r), "cave_creak", p.creakRange(), x, y, z, p.creakVolume());
        creaks++;
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("CaveSound 軋み 位置 {} {} {} 距離 {} 向き {}", String.format("%.1f", x), String.format("%.1f", y), String.format("%.1f", z),
                    String.format("%.1f", r), root != null ? "根 " + root.toShortString() : "任意");
        }
    }

    private static void play(Minecraft mc, InsectSynth.Call call, String kind, int range, double x, double y, double z, float volume) {
        ForestInsectSound.Voice v = new ForestInsectSound.Voice(null, call, kind, range, x, y, z);
        v.volume = volume;
        v.sound = new InsectSoundInstance(v);
        CALLS.add(v);
        mc.getSoundManager().play(v.sound);
    }

    /**
     * 半径 {@code radius} の中でタグ {@code tag} の一番近いブロック(無ければ null)。区画のパレットにそのタグのブロックが無ければ中を読まない
     * ({@link CaveLights} の光源の集め方と同じ。空のタグならほぼ只)。
     */
    @Nullable
    static BlockPos nearest(ClientLevel level, BlockPos center, int radius, TagKey<Block> tag) {
        if (radius <= 0) {
            return null;
        }
        int r2 = radius * radius;
        BlockPos best = null;
        int bestD = Integer.MAX_VALUE;
        int minSy = Math.max(level.getMinSection(), SectionPos.blockToSectionCoord(center.getY() - radius));
        int maxSy = Math.min(level.getMaxSection() - 1, SectionPos.blockToSectionCoord(center.getY() + radius));
        for (int sx = SectionPos.blockToSectionCoord(center.getX() - radius); sx <= SectionPos.blockToSectionCoord(center.getX() + radius); sx++) {
            for (int sz = SectionPos.blockToSectionCoord(center.getZ() - radius); sz <= SectionPos.blockToSectionCoord(center.getZ() + radius); sz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = minSy; sy <= maxSy; sy++) {
                    LevelChunkSection section = sections[level.getSectionIndexFromSectionY(sy)];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.is(tag))) {
                        continue;
                    }
                    int bx = SectionPos.sectionToBlockCoord(sx);
                    int by = SectionPos.sectionToBlockCoord(sy);
                    int bz = SectionPos.sectionToBlockCoord(sz);
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (!section.getBlockState(x, y, z).is(tag)) {
                                    continue;
                                }
                                int dx = bx + x - center.getX();
                                int dy = by + y - center.getY();
                                int dz = bz + z - center.getZ();
                                int d = dx * dx + dy * dy + dz * dz;
                                if (d <= r2 && d < bestD) {
                                    bestD = d;
                                    best = new BlockPos(bx + x, by + y, bz + z);
                                }
                            }
                        }
                    }
                }
            }
        }
        return best;
    }
}
