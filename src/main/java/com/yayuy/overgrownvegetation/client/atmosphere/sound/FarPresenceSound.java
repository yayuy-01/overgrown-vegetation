package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.atmosphere.ForestPresence;
import com.yayuy.overgrownvegetation.client.atmosphere.SporeAir;
import com.yayuy.overgrownvegetation.data.AtmosphereScary;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * <b>遠くの音だけの存在</b>(「美しいが怖い」A。ユーザー決定 11-03・11-16: 汚染地帯とその下の洞窟で、プレイヤーが警戒状態(走っていない・
 * しゃがみ・暗い所)のときだけ、距離 20〜40 から音だけが聞こえる。<b>正体は何もいない</b>(確かめに行っても何もない)。<b>その人にだけ聞こえる</b>
 * (クライアントだけ・通信なし)。汚染が濃いほど起きやすい。汚れていない世界では起きない)。
 *
 * <p>理由(設定): 森の知性(司令塔)が見ていることの表れ。鳥・獣の声は無い世界なので、音は「何かが動いた跡」だけ:
 * 落ち葉の中を這う音・枝の折れる音・足音が 2〜4 歩して止まる(地上)、小石が転がり落ちる(洞窟)。水滴が止まる音は蠢く壁の前触れなので使わない。
 *
 * <h2>作り</h2>
 * 1 秒に 1 回、条件(汚染地帯かその下・警戒状態)を見て、くじ(平均 {@code everySeconds} 秒に 1 回、胞子の濃い所ほど {@code denseEverySeconds} へ)が
 * 当たったら、見ていない向き(左右と後ろ)の {@code minDistance}〜{@code maxDistance} ブロック先から 1 回だけ鳴らす。続けては鳴らさない
 * (前の音から 60 秒は空ける)。音の素材は {@code assets/overgrown_vegetation/sounds/scary/far_*.ogg}(効果音の素材を重ねて加工した物。
 * sounds.json には載せず、ファイルをそのまま流す)。ファイルが無ければ何も鳴らさない。距離の分は大きさで弱める(向きは位置から付く)。
 *
 * <p><b>既定では鳴らさない</b>({@code scary.farSound.enabled} = false): 見本の音をユーザーが聞いて選ぶまで。確かめは {@code /ov fx farsound}。
 * 数は design の節 {@code scary.farSound}(すべて仮決め)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class FarPresenceSound {

    /** 地上の音(這う・枝・足音)と洞窟の音(小石)。名前は sounds/scary/ の中のファイル。 */
    private static final String[] SURFACE = {"far_crawl", "far_branch", "far_steps"};
    private static final String[] CAVE = {"far_pebbles"};
    /** 前の音からこれだけは空ける(tick)。 */
    private static final long MIN_GAP = 1200L;
    private static final RandomSource RANDOM = RandomSource.create();
    private static long lastAt = Long.MIN_VALUE / 2;
    private static int played;
    private static String lastWhat = "";
    private static String lastState = "";

    private FarPresenceSound() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null || mc.isPaused() || level.getGameTime() % 20L != 0L) {
            return;
        }
        AtmosphereScary.FarSound p = DesignData.atmosphere().scary().farSound();
        if (!p.enabled()) {
            // 保留中(0.2.0 の後に詰める)は何も調べない。確かめ用の force だけが鳴らす。
            lastState = "切";
            return;
        }
        float dense = density(level, player);
        boolean wary = wary(level, player, p);
        lastState = String.format("汚染 %.2f・警戒 %s", dense, wary ? "している" : "していない");
        if (!p.enabled() || dense <= 0.0F || !wary || level.getGameTime() - lastAt < MIN_GAP) {
            return;
        }
        float every = Mth.lerp(dense, p.everySeconds(), p.denseEverySeconds());
        if (RANDOM.nextFloat() < 1.0F / Math.max(1.0F, every)) {
            play(level, player, p, null);
        }
    }

    /**
     * その所の汚染の度合い(0 = 汚れていない・起きない 〜 1 = 濃い)。胞子の空気の度合い({@link SporeAir#levelAt}。地下でも真上の升の値)と、
     * 過繁茂の森の中にいる度合いの大きい方。どちらも 0 でも、過繁茂のバイオームの中(その下の洞窟)なら小さな値にする。
     */
    private static float density(ClientLevel level, LocalPlayer player) {
        float d = Math.max(SporeAir.levelAt(player.getX(), player.getZ()), ForestPresence.overgrown());
        if (d < 0.1F && ModBiomes.isOvergrownLike(level.getBiome(player.blockPosition()))) {
            d = 0.1F;
        }
        return d < 0.02F ? 0.0F : Mth.clamp(d, 0.0F, 1.0F);
    }

    /** 警戒状態: 走っていない、かつ(しゃがんでいる か 暗い所にいる)。 */
    private static boolean wary(ClientLevel level, LocalPlayer player, AtmosphereScary.FarSound p) {
        if (player.isSprinting() || player.isPassenger() || player.isSpectator()) {
            return false;
        }
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        // その所の明るさ(ブロックの光と、昼夜を掛けた空の光の大きい方)。ワールドの値は書き換えない。
        int block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, eye);
        float night = com.yayuy.overgrownvegetation.client.atmosphere.SunLight.night(level, 1.0F);
        float sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eye) * (1.0F - 0.75F * night);
        return player.isCrouching() || Math.max(block, sky) <= p.darkLight();
    }

    /** 1 回鳴らす。what = ファイル名(null なら場所に合わせてくじ)。 */
    private static String play(ClientLevel level, LocalPlayer player, AtmosphereScary.FarSound p, String what) {
        boolean cave = ForestPresence.underground() > 0.5F;
        String[] pool = cave ? CAVE : SURFACE;
        String name = what != null ? what : pool[RANDOM.nextInt(pool.length)];
        ResourceLocation file = OvergrownVegetation.id("sounds/scary/" + name + ".ogg");
        if (mc().getResourceManager().getResource(file).isEmpty()) {
            lastWhat = name + "(ファイルが無い)";
            return lastWhat;
        }
        // 見ていない向き(左右 70° より外・後ろ)から。
        double side = (70.0 + RANDOM.nextDouble() * 110.0) * (RANDOM.nextBoolean() ? 1.0 : -1.0);
        double angle = Math.toRadians(player.getYRot() + 90.0 + side);
        double dist = Mth.lerp(RANDOM.nextDouble(), p.minDistance(), Math.max(p.minDistance(), p.maxDistance()));
        double x = player.getX() + Math.cos(angle) * dist;
        double z = player.getZ() + Math.sin(angle) * dist;
        double y = player.getEyeY() + (cave ? (RANDOM.nextDouble() - 0.5) * 6.0 : -1.0);
        // 距離の分: 近い端で 1、遠い端で 0.55。
        float far = (float) ((dist - p.minDistance()) / Math.max(1.0, p.maxDistance() - p.minDistance()));
        mc().getSoundManager().play(new Instance(file, p.volume() * Mth.lerp(far, 1.0F, 0.55F), x, y, z));
        lastAt = level.getGameTime();
        played++;
        lastWhat = String.format("%s 距離 %.0f 向き %+.0f°", name, dist, side);
        return lastWhat;
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** 確かめ用({@code /ov fx farsound}・devbridge): 条件を見ずに 1 回鳴らす。what = ファイル名か空。 */
    public static String force(String what) {
        Minecraft mc = mc();
        if (mc.level == null || mc.player == null) {
            return "ワールドの中で";
        }
        return play(mc.level, mc.player, DesignData.atmosphere().scary().farSound(), what == null || what.isEmpty() ? null : what) + " / " + describe();
    }

    /** 確かめ用の 1 行。 */
    public static String describe() {
        return String.format("遠くの音 %s(これまで %d 回・最後 %s・%s)", DesignData.atmosphere().scary().farSound().enabled() ? "入" : "切(既定)",
                played, lastWhat.isEmpty() ? "なし" : lastWhat, lastState);
    }

    /** ファイルをそのまま流す 1 回きりの音(sounds.json に載せない。{@link PhaseCueSound} と同じ道)。 */
    private static final class Instance extends AbstractSoundInstance {
        private final ResourceLocation file;

        Instance(ResourceLocation file, float volume, double x, double y, double z) {
            super(SoundEvent.createVariableRangeEvent(OvergrownVegetation.id("far_presence")), SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.file = file;
            this.looping = false;
            this.delay = 0;
            this.volume = volume;
            this.relative = false;
            this.x = x;
            this.y = y;
            this.z = z;
            // 大きさはこちらで決める(距離では弱めない)。位置から向きだけが付く。
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager manager) {
            ResourceLocation id = OvergrownVegetation.id("far_presence");
            this.sound = new Sound(id, ConstantFloat.of(1.0F), ConstantFloat.of(1.0F), 1, Sound.Type.FILE, true, false, 16);
            return new WeighedSoundEvents(id, null);
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
            return CompletableFuture.supplyAsync(() -> {
                Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(this.file);
                try {
                    return new JOrbisAudioStream(resource.orElseThrow().open());
                } catch (IOException | RuntimeException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            });
        }
    }
}
