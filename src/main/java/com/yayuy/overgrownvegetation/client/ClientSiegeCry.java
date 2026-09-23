package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.network.SiegeBossCryPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 攻城戦ボスの<b>悲鳴</b>の再生。{@link SiegeBossCryPayload} を受けて鳴らす。
 *
 * <h2>なぜクライアントで鳴らすのか</h2>
 * <p>ボスはふつう 1000 ブロック先の未ロードチャンクに居るので、サーバー側の
 * {@code level.playSound} は誰にも届かない。そこで「鳴らせ」という合図だけを配り、
 * 実際の再生はここで行う。近ければボスの方角から、遠ければ方角のない地鳴りとして鳴らす。
 *
 * <h2>長く「叫ぶ」ための作り(2026-09-13)</h2>
 * <p>1 発鳴らすだけでは短すぎたので、<b>ずらして重ねる</b>ようにした。1 回の悲鳴は
 * {@link #layers} の表どおりに複数の音を tick 単位で遅らせて鳴らし、後ろほど低く・小さくして
 * 尾を引かせる。被弾の悲鳴で 3 秒ほど、断末魔は <b>6 秒以上</b>うねり続ける。
 *
 * <p>もう一つの軸が<b>ピッチ</b>({@code siegeCryPitch}、既定 0.7)。Minecraft はピッチを再生速度で
 * 作るので、<b>下げるほど低く、そして長くなる</b>。巨体の声にしたいならここを下げるのが一番効く
 * (エンジン側が 0.5 未満を受け付けないので、掛け合わせた結果もそこで止めてある)。
 *
 * <p>音量は {@code siegeCryVolume}(既定 2.0)でまとめて調整できる。
 * いまはバニラの音を重ねて作っている。専用の ogg を用意したくなったら
 * {@code assets/overgrown_vegetation/sounds/} に置いて {@code sounds.json} と
 * {@link com.yayuy.overgrownvegetation.registry.ModSounds} に足し、{@link #layers} を差し替えるだけでよい。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientSiegeCry {

    /** これより近ければ、ボスの方角から鳴らす(それより遠いと方角のない音)。 */
    private static final double POSITIONAL_DISTANCE = 256.0D;
    /** Minecraft のサウンドエンジンが受け付けるピッチの下限。これ以上は下げても伸びない。 */
    private static final float MIN_PITCH = 0.5F;

    /** 重ねる音 1 層: 音・ピッチ倍率・音量・鳴らし始めるまでの遅れ(tick)。 */
    private record Layer(SoundEvent sound, float pitch, float volume, int delay) {
    }

    /** まだ鳴らしていない層。{@link #onClientTick} が時間になったものから鳴らす。 */
    private record Pending(long playAt, SoundEvent sound, float pitch, float volume, boolean positional, Vec3 at) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static long ticks;

    public static void onPayload(SiegeBossCryPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        double distance = minecraft.player.position().distanceTo(payload.at());
        boolean positional = distance <= POSITIONAL_DISTANCE;
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        // 音量はまとめて siegeCryVolume 倍(既定 2.0)。1 を超えると位置付きの音は減衰距離も伸びる。
        float gain = cfg.siegeCryVolume.get().floatValue();
        float basePitch = cfg.siegeCryPitch.get().floatValue();
        for (Layer layer : layers(payload)) {
            PENDING.add(new Pending(ticks + layer.delay(), layer.sound(),
                    Math.max(MIN_PITCH, layer.pitch() * basePitch), layer.volume() * gain,
                    positional, payload.at()));
        }
        flush(minecraft);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            PENDING.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        ticks++;
        flush(minecraft);
    }

    /** 時間になった層を鳴らす。 */
    private static void flush(Minecraft minecraft) {
        if (PENDING.isEmpty() || minecraft.level == null) {
            return;
        }
        PENDING.removeIf(pending -> {
            if (pending.playAt() > ticks) {
                return false;
            }
            if (pending.positional()) {
                minecraft.getSoundManager().play(new SimpleSoundInstance(pending.sound(), SoundSource.HOSTILE,
                        pending.volume(), pending.pitch(), minecraft.level.random,
                        pending.at().x, pending.at().y, pending.at().z));
            } else {
                // 方角なし = 耳元で鳴る。ピッチをさらに下げて「遠くの地鳴り」にする。
                // 以前は音量も 0.55 倍に落としていたが、遠距離の砲撃戦では聞こえなさすぎたのでやめた。
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(pending.sound(),
                        Math.max(MIN_PITCH, pending.pitch() * 0.85F), pending.volume() * 0.9F));
            }
            return true;
        });
    }

    /**
     * 悲鳴の中身。強く削られたときほど低く重い音になる({@code severity})。断末魔は別の組み合わせ。
     * ピッチは {@code siegeCryPitch} に掛ける<b>倍率</b>で、{@code delay} は鳴らし始めるまでの tick。
     */
    private static Layer[] layers(SiegeBossCryPayload payload) {
        float severity = Mth.clamp(payload.severity(), 0.0F, 1.0F);
        if (payload.death()) {
            // 断末魔。落下(約 4.5 秒)と着地の余韻を通して途切れないよう 6 秒以上うねらせる。
            return new Layer[]{
                    new Layer(SoundEvents.ENDER_DRAGON_DEATH, 1.00F, 1.00F, 0),
                    new Layer(SoundEvents.WARDEN_DEATH, 1.05F, 0.90F, 0),
                    new Layer(SoundEvents.ENDER_DRAGON_GROWL, 0.95F, 0.85F, 18),
                    new Layer(SoundEvents.RAVAGER_ROAR, 0.85F, 0.80F, 40),
                    new Layer(SoundEvents.WARDEN_DEATH, 0.80F, 0.75F, 64),
                    new Layer(SoundEvents.ENDER_DRAGON_GROWL, 0.72F, 0.60F, 90),
                    new Layer(SoundEvents.ENDER_DRAGON_DEATH, 0.70F, 0.50F, 120),
            };
        }
        // 削られた量が大きいほど低く(= 長く)。植物の巨体が軋むように、どれも低め。
        float depth = 1.0F - 0.20F * severity;
        return new Layer[]{
                new Layer(SoundEvents.ENDER_DRAGON_HURT, depth, 1.00F, 0),
                new Layer(SoundEvents.RAVAGER_ROAR, depth * 0.80F, 0.75F, 0),
                new Layer(SoundEvents.ENDER_DRAGON_HURT, depth * 0.90F, 0.80F, 9),
                new Layer(SoundEvents.RAVAGER_ROAR, depth * 0.72F, 0.60F, 20),
                new Layer(SoundEvents.ENDER_DRAGON_GROWL, depth * 0.80F, 0.50F, 32),
        };
    }

    private ClientSiegeCry() {
    }
}
