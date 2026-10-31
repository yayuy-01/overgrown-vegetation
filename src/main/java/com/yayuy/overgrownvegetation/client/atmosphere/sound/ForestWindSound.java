package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.GustField;
import com.yayuy.overgrownvegetation.client.atmosphere.SunTrace;
import com.yayuy.overgrownvegetation.client.atmosphere.Wind;
import com.yayuy.overgrownvegetation.client.atmosphere.WindGate;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 過繁茂の森の<b>突風の風の音</b>の係(2026-10-30 夜 第 2 段「動き」。元は parked の {@code CanopyWindSound}、2026-10-15 夜 ユーザー
 * 「突風が吹いた際に風の音が鳴るように。現実の音を参考に、しっかりリアリティのある環境音に」→ v3 で了承)。
 *
 * <p>音源は 2 つ({@link WindSoundModel}):
 * <ul>
 *     <li><b>まわり</b>: 聞く人のまわりの葉擦れ(ステレオ・真ん中)。</li>
 *     <li><b>遠く</b>: 林を渡ってくる突風の前線の音(モノラル・向きつき)。聞く人から 10 ブロック・梢の高さに置き、毎 tick 向きを変える
 *     —— 風上の梢が鳴り出して近づき、頭の上を通って風下へ遠ざかるのが、左右と大きさで聞こえる(提案 6)。</li>
 * </ul>
 * 音の中身は {@link WindSynth} がその場で作る(なめらかな雑音の層だけ。とがった粒は入れない。{@link WindAudioStream}。ファイルも
 * {@code sounds.json} も無い。memory procedural-sound-stream)。ここは毎 tick({@link com.yayuy.overgrownvegetation.client.atmosphere.Atmosphere}
 * から。風を進めた後):
 * <ol>
 *     <li>1.5 秒先までの風を見積もって並べ({@link #window()}。音のスレッドが読む)、</li>
 *     <li>全体の大きさを決める: 設計値 {@code sound.volume} × 過繁茂の森の中にいる割合(足元と周り 12 ブロックの 8 か所のバイオーム)
 *     × 空の見え方(真上に岩・屋根が無い列の割合か、空の光。洞窟・建物の中では消える)× 水の中なら 2 割。
 *     <b>雷雨</b>({@link GustField#storm})では大きさに {@code 1 + sound.stormVolume × 嵐} を掛け、森の外でも 7 割は聞こえる。
 *     凪の強さ自体が上がるので、雷雨の間だけは鳴り続ける(「凪は無音」の唯一の例外)。</li>
 *     <li>鳴らし始め・止める(突風の音が要るときだけ鳴らし、凪で 3 秒静かなら止めて、音の通り道を返す)。</li>
 * </ol>
 * 音の種類は「環境音」(バニラの設定の音量の「環境音」で一緒に変わる)。葉の揺れは同じ見積もりの {@link WindGate} が持つ
 * (音の大きさ・消音の設定には左右されない)。森の中にいる割合・空の見え方の数値は仮決め。
 */
public final class ForestWindSound {

    /** 先の風を何 tick 先まで並べるか(1.5 秒。流す側に積まれた音が鳴るまでの遅れより十分長く)。 */
    private static final int AHEAD = 30;
    /** 遠くの音源を置く距離(ブロック)と高さ(目から)。 */
    private static final double FAR_DISTANCE = 10.0;
    private static final double FAR_RISE = 6.0;
    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、5 秒ごとに音の状態をログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static final int[][] OPEN_COLUMNS = {{0, 0}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}};

    private static WindSoundModel model;
    private static volatile Window window = Window.SILENT;
    private static WindSoundInstance near;
    private static WindSoundInstance far;
    /** なめらかに寄せた、全体の大きさ・森の中にいる割合・空の見え方。 */
    private static float gain;
    private static float inside;
    private static float insideTarget;
    private static float open;
    private static float openColumns;
    private static int quietTicks;
    private static int retryIn;
    private static long ticks;
    private static double farX;
    private static double farY;
    private static double farZ;

    /** 1.5 秒先までの風の見積もり(描画のスレッドが作り、音のスレッドが読む。作った後は書き換えない)。 */
    static final class Window {
        static final Window SILENT = new Window(System.nanoTime(), new float[AHEAD + 1], new float[AHEAD + 1],
                new float[AHEAD + 1], new float[AHEAD + 1]);

        private final long nanos;
        private final float[] nearW;
        private final float[] nearLevel;
        private final float[] farW;
        private final float[] farLevel;

        Window(long nanos, float[] nearW, float[] nearLevel, float[] farW, float[] farLevel) {
            this.nanos = nanos;
            this.nearW = nearW;
            this.nearLevel = nearLevel;
            this.farW = farW;
            this.farLevel = farLevel;
        }

        float w(boolean far, double aheadSeconds) {
            return sample(far ? this.farW : this.nearW, aheadSeconds);
        }

        float level(boolean far, double aheadSeconds) {
            return sample(far ? this.farLevel : this.nearLevel, aheadSeconds);
        }

        /** 並べた値を、作った時から {@code aheadSeconds} 後(+ 作ってから今までの時間)で引く。 */
        private float sample(float[] values, double aheadSeconds) {
            // 作ってからの時間は 0.1 秒までにとどめる(ポーズ明けなど、古い見積もりで遠い先を読まない)。
            double since = Math.min(0.1, Math.max(0.0, (System.nanoTime() - this.nanos) / 1.0E9));
            double at = (since + aheadSeconds) * 20.0;
            if (at <= 0.0) {
                return values[0];
            }
            if (at >= AHEAD) {
                return values[AHEAD];
            }
            int i = (int) at;
            float f = (float) (at - i);
            return values[i] + (values[i + 1] - values[i]) * f;
        }
    }

    static Window window() {
        return window;
    }

    static float volume(boolean isFar) {
        return gain;
    }

    static double farX() {
        return farX;
    }

    static double farY() {
        return farY;
    }

    static double farZ() {
        return farZ;
    }

    /** この音源を止めるべきか(入れ替わった古い音源・ワールドを出た)。 */
    static boolean shouldStop(WindSoundInstance instance) {
        return instance != near && instance != far;
    }

    /** 雰囲気を止めたとき・ワールドが替わったとき。 */
    public static void stop() {
        stopAll(Minecraft.getInstance());
        gain = 0.0F;
        inside = 0.0F;
        open = 0.0F;
        window = Window.SILENT;
    }

    /** 毎 tick(風を進めた後)。{@code time} は {@link com.yayuy.overgrownvegetation.client.atmosphere.Atmosphere#time}。 */
    public static void tick(Minecraft mc, ClientLevel level, LocalPlayer player, double time) {
        if (!VisualQuality.ambientWind()) {
            stop();
            return;
        }
        ticks++;
        AtmosphereTable.SoundParams p = DesignData.atmosphere().sound();
        if (model == null) {
            model = new WindSoundModel(p.calmThreshold());
        }
        if (p.volume() <= 0.0F) {
            stopAll(mc);
            gain = 0.0F;
            return;
        }

        // 過繁茂の森の中にいる割合(足元と周り 12 ブロックの 8 か所)。1 秒に 1 回見て、2 秒ほどかけて寄せる。
        if (ticks % 20 == 0 || inside == 0.0F && ticks % 5 == 0) {
            int hits = 0;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int i = 0; i < 9; i++) {
                double angle = i * Math.PI / 4.0;
                double r = i == 8 ? 0.0 : 12.0;
                pos.set(player.getX() + Math.cos(angle) * r, player.getY(), player.getZ() + Math.sin(angle) * r);
                if (ModBiomes.isOvergrownLike(level.getBiome(pos))) {
                    hits++;
                }
            }
            insideTarget = hits / 9.0F;
        }
        inside += (insideTarget - inside) * 0.025F;
        // 空の見え方: 真上に岩・屋根(不透明なブロック)が無い列の割合と、目の高さの空の光の大きい方(林冠・巨大樹の傘の下は開けている)。
        if (ticks % 10 == 0) {
            openColumns = openColumns(level, player);
        }
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        float sky = level.getBrightness(LightLayer.SKY, eye);
        float s = Mth.clamp((sky - 2.0F) / 8.0F, 0.0F, 1.0F);
        float openTarget = Math.max(openColumns, s * s * (3.0F - 2.0F * s));
        if (player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA)) {
            openTarget *= 0.2F;
        }
        open += (openTarget - open) * 0.05F;
        float storm = (float) GustField.INSTANCE.storm();
        float target = Math.min(2.0F, p.volume()) * Math.max(inside, storm * 0.7F) * open * (1.0F + p.stormVolume() * storm);
        gain += (target - gain) * 0.05F;

        // 1.5 秒先までの風を並べる(音の流れが鳴る時刻の風を読む)。
        double px = player.getX();
        double pz = player.getZ();
        Wind wind = Wind.current();
        float[] nearW = new float[AHEAD + 1];
        float[] nearLevel = new float[AHEAD + 1];
        float[] farW = new float[AHEAD + 1];
        float[] farLevel = new float[AHEAD + 1];
        double dirX = 0.0;
        double dirZ = 0.0;
        float peak = 0.0F;
        for (int k = 0; k <= AHEAD; k++) {
            model.compute(wind, px, pz, time + k);
            nearW[k] = (float) model.nearW;
            nearLevel[k] = (float) model.nearLevel;
            farW[k] = (float) model.farW;
            farLevel[k] = (float) model.farLevel;
            peak = Math.max(peak, Math.max(nearLevel[k], farLevel[k]));
            if (k == 0) {
                dirX = model.dirX;
                dirZ = model.dirZ;
            }
        }
        window = new Window(System.nanoTime(), nearW, nearLevel, farW, farLevel);
        farX = px + dirX * FAR_DISTANCE;
        farY = player.getEyeY() + FAR_RISE;
        farZ = pz + dirZ * FAR_DISTANCE;

        // 鳴らし始め・止める。バニラの音量の「環境音」かマスターが 0 なら鳴らさない。
        SoundManager sounds = mc.getSoundManager();
        boolean muted = mc.options.getSoundSourceVolume(SoundSource.AMBIENT) <= 0.0F
                || mc.options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F;
        // 風は時折吹くもの: 1.5 秒先までに突風の音が要らなければ(凪)鳴らさず、鳴っていれば 3 秒後に止める。
        boolean wanted = peak > 0.004F;
        if (gain > 0.002F && !muted && wanted) {
            quietTicks = 0;
            if (retryIn > 0) {
                retryIn--;
            } else {
                boolean started = false;
                if (near == null || !sounds.isActive(near)) {
                    near = new WindSoundInstance(false);
                    sounds.play(near);
                    started = true;
                }
                if (far == null || !sounds.isActive(far)) {
                    far = new WindSoundInstance(true);
                    sounds.play(far);
                    started = true;
                }
                if (started) {
                    // 鳴らせなかったとき(音の通り道が埋まっている・音が出ない環境)に毎 tick 試さない。
                    retryIn = 40;
                }
            }
        } else if (++quietTicks > 60) {
            stopAll(mc);
        }
        if (DEV_LOG && ticks % 100 == 0) {
            OvergrownVegetation.LOGGER.info("ForestWindSound 大きさ={} 中={} 空={} 嵐={} まわり={}/{} 遠く={}/{} 向き=({}, {}) 鳴っている={}/{} 揺れ={}",
                    String.format("%.3f", gain), String.format("%.2f", inside), String.format("%.2f", open), String.format("%.2f", storm),
                    String.format("%.2f", nearW[0]), String.format("%.3f", nearLevel[0]),
                    String.format("%.2f", farW[0]), String.format("%.3f", farLevel[0]),
                    String.format("%.2f", dirX), String.format("%.2f", dirZ),
                    near != null && sounds.isActive(near), far != null && sounds.isActive(far),
                    String.format("%.2f", WindGate.value(0.0F)));
        }
    }

    /**
     * 足元と周り 3 ブロックの 5 列のうち、目の高さから列の一番上まで不透明なブロック(岩・屋根・幹)が無い列の割合。葉・草・空気は通す。
     * 空気だけの区画は飛ばす(巨大樹の傘の下は 100 ブロック以上の空間がある)。
     */
    private static float openColumns(ClientLevel level, LocalPlayer player) {
        int count = 0;
        int fromY = Mth.floor(player.getEyeY()) + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] d : OPEN_COLUMNS) {
            int x = Mth.floor(player.getX()) + d[0];
            int z = Mth.floor(player.getZ()) + d[1];
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            boolean blocked = false;
            for (int y = fromY; y < top && !blocked; y++) {
                if (SunTrace.sectionIsAir(level, x, y, z)) {
                    y = (y | 15);
                    continue;
                }
                blocked = level.getBlockState(pos.set(x, y, z)).canOcclude();
            }
            if (!blocked) {
                count++;
            }
        }
        return count / (float) OPEN_COLUMNS.length;
    }

    private static void stopAll(Minecraft mc) {
        if (near != null) {
            mc.getSoundManager().stop(near);
            near = null;
        }
        if (far != null) {
            mc.getSoundManager().stop(far);
            far = null;
        }
        quietTicks = 0;
    }

    private ForestWindSound() {
    }
}
