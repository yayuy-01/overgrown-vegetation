package com.yayuy.overgrownvegetation.client.sound;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.CanopyWind;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 汚染地域の<b>風の音</b>の係(2026-10-15 夜。ユーザー「突風が吹いた際に風の音が鳴るように。現実の音を参考に、しっかりリアリティのある環境音に」)。
 *
 * <p>音源は 2 つ({@link WindSoundModel}):
 * <ul>
 *     <li><b>まわり</b>: 聞く人のまわりの葉擦れ(ステレオ・真ん中)。凪ではかすかに「ぱち・さら」、突風が来ると「ざぁーっ」。</li>
 *     <li><b>遠く</b>: 林を渡ってくる突風の音(モノラル・向きつき)。聞く人から 10 ブロック・梢の高さに置き、毎 tick 向きを変える
 *     —— 風上の梢が鳴り出して近づき、頭の上を通って風下へ遠ざかるのが聞こえる。</li>
 * </ul>
 * 音の中身は {@link WindSynth} がその場で作る({@link WindAudioStream}。ファイルは無い)。ここは毎 tick、
 * <ol>
 *     <li>1.5 秒先までの風を見積もって並べ({@link #window()}。音のスレッドが読む)、</li>
 *     <li>全体の大きさを決める: 設定 {@code canopyWindSoundVolume} × 汚染地域の中にいる割合(足元と周り 12 ブロックの 8 か所のバイオーム)
 *     × 空の見え方(真上に岩・屋根が無い列の割合か、空の光。林冠・巨大樹の傘の下は開けている、洞窟・建物の中では消える)× 水の中なら 2 割、</li>
 *     <li>鳴らし始め・止める(突風の音が要るときだけ鳴らし、凪・汚染地域の外で 3 秒静かなら止めて、音の通り道を返す)。</li>
 * </ol>
 * 音の種類は「環境音」(バニラの設定の音量の「環境音」で一緒に変わる)。数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CanopyWindSound {

    /** 先の風を何 tick 先まで並べるか(1.5 秒。流す側に積まれた音が鳴るまでの遅れより十分長く)。 */
    private static final int AHEAD = 30;
    /** 遠くの音源を置く距離(ブロック)と高さ(目から)。 */
    private static final double FAR_DISTANCE = 10.0;
    private static final double FAR_RISE = 6.0;

    private static final WindSoundModel MODEL = new WindSoundModel();

    private static volatile Window window = Window.SILENT;
    private static WindSoundInstance near;
    private static WindSoundInstance far;
    private static ClientLevel soundLevel;
    /** なめらかに寄せた、全体の大きさ・汚染地域の中にいる割合・空の見え方。 */
    private static float gain;
    private static float inside;
    private static float open;
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

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != soundLevel) {
            stopAll(mc);
            soundLevel = level;
            gain = 0.0F;
            inside = 0.0F;
            open = 0.0F;
        }
        if (level == null || player == null) {
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        ticks++;
        double volumeSetting = OvergrownVegetationConfig.COMMON.canopyWindSoundVolume.get();
        if (volumeSetting <= 0.0 || !level.dimensionType().hasSkyLight()) {
            stopAll(mc);
            gain = 0.0F;
            return;
        }

        // 汚染地域の中にいる割合(足元と周り 12 ブロックの 8 か所)。1 秒に 1 回見て、2 秒ほどかけて寄せる。
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
        // 空の見え方: 真上に岩・屋根(不透明なブロック)が無い列の割合と、目の高さの空の光の大きい方。
        // 最初は空の光だけで見ていて、巨大樹の傘の下(空の光 2 以下)で音が消えた —— 傘の葉は風で鳴る側なので、葉の下は開けているとみなす。
        // 洞窟・建物の中は列も空の光も 0。
        if (ticks % 10 == 0) {
            openColumns = openColumns(level, player);
        }
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        float sky = level.getBrightness(LightLayer.SKY, eye);
        float openTarget = Math.max(openColumns, smooth(Mth.clamp((sky - 2.0F) / 8.0F, 0.0F, 1.0F)));
        if (player.isEyeInFluid(FluidTags.WATER) || player.isEyeInFluid(FluidTags.LAVA)) {
            openTarget *= 0.2F;
        }
        open += (openTarget - open) * 0.05F;
        // 胞子の嵐(2026-10-23 胞子の場 H): 雷雨の間は風の音を強める(汚染地域の外でも 7 割は聞こえる)
        float storm = com.yayuy.overgrownvegetation.client.ClientStorm.sporeStorm();
        float target = (float) Math.min(2.0, volumeSetting) * Math.max(inside, storm * 0.7F) * open * (1.0F + 1.5F * storm);
        gain += (target - gain) * 0.05F;

        // 1.5 秒先までの風を並べる(音の流れが鳴る時刻の風を読む)。
        double px = player.getX();
        double pz = player.getZ();
        double now = CanopyWind.time(0.0F);
        float[] nearW = new float[AHEAD + 1];
        float[] nearLevel = new float[AHEAD + 1];
        float[] farW = new float[AHEAD + 1];
        float[] farLevel = new float[AHEAD + 1];
        double dirX = 0.0;
        double dirZ = 0.0;
        float peak = 0.0F;
        for (int k = 0; k <= AHEAD; k++) {
            MODEL.compute(px, pz, now + k);
            nearW[k] = (float) MODEL.nearW;
            nearLevel[k] = (float) MODEL.nearLevel;
            farW[k] = (float) MODEL.farW;
            farLevel[k] = (float) MODEL.farLevel;
            peak = Math.max(peak, Math.max(nearLevel[k], farLevel[k]));
            if (k == 0) {
                dirX = MODEL.dirX;
                dirZ = MODEL.dirZ;
            }
        }
        window = new Window(System.nanoTime(), nearW, nearLevel, farW, farLevel);
        farX = px + dirX * FAR_DISTANCE;
        farY = player.getEyeY() + FAR_RISE;
        farZ = pz + dirZ * FAR_DISTANCE;

        // 鳴らし始め・止める。バニラの音量の「環境音」かマスターが 0 なら鳴らさない(鳴らしてもすぐ止められ、作り直しを繰り返すだけ)。
        SoundManager sounds = mc.getSoundManager();
        boolean muted = mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.AMBIENT) <= 0.0F
                || mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER) <= 0.0F;
        // 風は時折吹くもの: 1.5 秒先までに突風の音が要らなければ(凪)鳴らさず、鳴っていれば 3 秒後に止める(音の通り道を返す)。
        // 突風は生まれてから 3 秒かけて強まり、音は 1.5 秒先を見て鳴らし始めるので、鳴り出しは無音から立ち上がる。
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
            OvergrownVegetation.LOGGER.info("CanopyWindSound 大きさ={} 中={} 空={} まわり={}/{} 遠く={}/{} 向き=({}, {}) 鳴っている={}/{} 揺れ={}",
                    String.format("%.3f", gain), String.format("%.2f", inside), String.format("%.2f", open),
                    String.format("%.2f", nearW[0]), String.format("%.3f", nearLevel[0]),
                    String.format("%.2f", farW[0]), String.format("%.3f", farLevel[0]),
                    String.format("%.2f", dirX), String.format("%.2f", dirZ),
                    near != null && sounds.isActive(near), far != null && sounds.isActive(far),
                    String.format("%.2f", com.yayuy.overgrownvegetation.client.LeafSway.gate()));
        }
    }

    private static float insideTarget;
    /** 真上が開けている列の割合(10 tick ごと)。 */
    private static float openColumns;
    private static final int[][] OPEN_COLUMNS = {{0, 0}, {3, 0}, {-3, 0}, {0, 3}, {0, -3}};

    /**
     * 足元と周り 3 ブロックの 5 列のうち、目の高さから列の一番上まで不透明なブロック(岩・屋根・幹)が無い列の割合。葉・草・空気は通す。
     * 空気だけの区画は飛ばす(巨大樹の傘の下は 100 ブロック以上の空間がある)。
     */
    private static float openColumns(ClientLevel level, LocalPlayer player) {
        int open = 0;
        int fromY = Mth.floor(player.getEyeY()) + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] d : OPEN_COLUMNS) {
            int x = Mth.floor(player.getX()) + d[0];
            int z = Mth.floor(player.getZ()) + d[1];
            int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
            boolean blocked = false;
            for (int y = fromY; y < top && !blocked; y++) {
                if (com.yayuy.overgrownvegetation.client.CanopyLight.sectionIsAir(level, x, y, z)) {
                    y = (y | 15);
                    continue;
                }
                blocked = level.getBlockState(pos.set(x, y, z)).canOcclude();
            }
            if (!blocked) {
                open++;
            }
        }
        return open / (float) OPEN_COLUMNS.length;
    }

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、5 秒ごとに音の状態をログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

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

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    private CanopyWindSound() {
    }
}
