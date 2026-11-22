package com.yayuy.overgrownvegetation.client.atmosphere.sound;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.client.atmosphere.GiantLifeTrees;
import com.yayuy.overgrownvegetation.client.atmosphere.GiantTrees;
import com.yayuy.overgrownvegetation.client.atmosphere.GustField;
import com.yayuy.overgrownvegetation.data.AtmosphereGiantLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>突風で巨大樹の幹が軋む低い音</b>の係(2026-11-06 ユーザー採用の改善案 ⑤ の 3)。合成は {@link TrunkCreakSynth}。
 *
 * <ul>
 *     <li>鳴る条件: 近い巨大樹(幹の軸から水平 {@code giantLife.creak.range} ブロック以内、最大 4 本)の幹の所の突風の強さ
 *     ({@link GustField#strength})が {@code threshold} を<b>下から越えたとき</b>に 1 回(突風 1 つに 1 回。鳴りっぱなしにしない、memory synth-sound-no-crackle)。
 *     同じ木は {@code cooldown} 秒は鳴らない。</li>
 *     <li>位置: 幹の軸の、プレイヤーの目の高さ(根元〜幹の先に収める)。木は 120〜240 の高さなので、幹の真ん中に置くと根元にいても 60 ブロック以上離れて
 *     聞こえなくなる。目の高さに置けば、距離 ≒ 幹までの水平距離。</li>
 *     <li>距離の減り: 線形に {@code range} ブロックで 0(Minecraft の LINEAR)。大きさは {@code volume} × (0.6 + 0.4 × 越えた強さ)。</li>
 *     <li><b>{@code enabled} は既定 false</b>(ユーザーが見本を聞いて了承するまで繋がない。10-31 の軋み 44 の作り直しの続き)。</li>
 * </ul>
 * 音の種類は「環境音」。値はすべて仮決め(節 {@code giantLife.creak})。
 */
public final class GiantTreeCreakSound {

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");
    private static final int MAX_TREES = 4;
    private static final List<ForestInsectSound.Voice> CALLS = new ArrayList<>();
    private static final LongOpenHashSet ABOVE = new LongOpenHashSet();
    private static final Long2LongOpenHashMap NEXT = new Long2LongOpenHashMap();
    /** 越えてから鳴らすまでの間の最大の強さと、越えた tick。 */
    private static final it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap PEAK = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
    private static final Long2LongOpenHashMap PENDING_SINCE = new Long2LongOpenHashMap();
    private static long ticks;
    private static int seeds = 1;
    private static int creaks;

    private GiantTreeCreakSound() {
    }

    public static void stop() {
        for (ForestInsectSound.Voice v : CALLS) {
            v.removed = true;
            if (v.sound != null) {
                Minecraft.getInstance().getSoundManager().stop(v.sound);
            }
        }
        CALLS.clear();
        ABOVE.clear();
        NEXT.clear();
        PEAK.clear();
        PENDING_SINCE.clear();
    }

    /** 毎 tick({@link com.yayuy.overgrownvegetation.client.atmosphere.Atmosphere} から)。time = Atmosphere.time。 */
    public static void tick(Minecraft mc, ClientLevel level, LocalPlayer player, double time) {
        AtmosphereGiantLife.CreakParams p = DesignData.atmosphere().giantLife().creak();
        if (!p.enabled() || !VisualQuality.ambientWind()) { // 風が起こす音なので、風の音と同じ入り切り
            if (!CALLS.isEmpty() || !ABOVE.isEmpty()) {
                stop();
            }
            return;
        }
        ticks++;
        for (int i = CALLS.size() - 1; i >= 0; i--) {
            ForestInsectSound.Voice v = CALLS.get(i);
            boolean started = v.sound != null && v.sound.started;
            if (v.call.done() || started && !mc.getSoundManager().isActive(v.sound) || ++v.retireTicks > (started ? 400 : 200)) {
                v.removed = true;
                if (v.sound != null) {
                    mc.getSoundManager().stop(v.sound);
                }
                CALLS.remove(i);
            }
        }
        // 音が 0 なら作らない(Claude 用のテストクライアントは音 0 なので、確かめのときだけ無音で流す)。
        boolean audible = DEV_LOG || mc.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0.0F && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0.0F;
        List<GiantTrees.Info> trees = GiantLifeTrees.nearest(player.getX(), player.getZ(), p.range(), MAX_TREES);
        LongOpenHashSet seen = new LongOpenHashSet();
        for (GiantTrees.Info t : trees) {
            long key = GiantLifeTrees.key(t);
            seen.add(key);
            double s = GustField.INSTANCE.strength(t.axisX(), t.axisZ(), time); // 鳴らす直前に山の強さへ置き換える
            boolean above = s >= p.threshold();
            boolean rising = above && !ABOVE.contains(key);
            if (above) {
                ABOVE.add(key);
            } else if (s < p.threshold() * 0.8) {
                ABOVE.remove(key); // 少し下がるまで「越えた」のまま(境目でばたつかない)
            }
            // 越えたら、突風の山(強さが下がり始める)か 1 秒後まで待って、その間の最大の強さで鳴らす(幹がしなりきってから軋む)。
            if (rising && ticks >= NEXT.getOrDefault(key, 0L) && audible && !PEAK.containsKey(key)) {
                PEAK.put(key, s);
                PENDING_SINCE.put(key, ticks);
                continue;
            }
            if (!PEAK.containsKey(key)) {
                continue;
            }
            double peak = PEAK.get(key);
            if (s > peak && ticks - PENDING_SINCE.get(key) < 20) {
                PEAK.put(key, s);
                continue;
            }
            PEAK.remove(key);
            PENDING_SINCE.remove(key);
            s = peak;
            NEXT.put(key, ticks + Math.round(p.cooldown() * 20.0));
            double push = Math.min(1.0, (s - p.threshold()) / Math.max(0.05, GustField.INSTANCE.maxStrength() - p.threshold()));
            double y = Math.max(t.baseY(), Math.min(t.topY(), player.getEyeY()));
            double horizontal = Math.sqrt(GiantLifeTrees.distSqr(t, player.getX(), player.getZ()));
            ForestInsectSound.Voice v = new ForestInsectSound.Voice(null, new TrunkCreakSynth(0x7C2E_0000 + seeds++ * 104729, push), "giant_creak",
                    p.range(), t.axisX(), y, t.axisZ());
            v.volume = p.volume() * (float) (0.6 + 0.4 * push);
            v.sound = new InsectSoundInstance(v);
            CALLS.add(v);
            mc.getSoundManager().play(v.sound);
            creaks++;
            if (DEV_LOG) {
                OvergrownVegetation.LOGGER.info("GiantTreeCreak 軋み #{} 木 {} {} 突風の強さ {}(しきい {})押し {} 水平距離 {} 大きさ {} 線形の減り後の目安 {}", creaks,
                        String.format("%.0f", t.axisX()), String.format("%.0f", t.axisZ()), String.format("%.2f", s), String.format("%.2f", p.threshold()),
                        String.format("%.2f", push), String.format("%.1f", horizontal), String.format("%.2f", v.volume),
                        String.format("%.2f", v.volume * Math.max(0.0, 1.0 - horizontal / p.range())));
            }
        }
        ABOVE.retainAll(seen);
        PEAK.keySet().retainAll(seen);
        PENDING_SINCE.keySet().retainAll(seen);
        if (DEV_LOG && ticks % 100 == 0 && !trees.isEmpty()) {
            GiantTrees.Info t = trees.get(0);
            OvergrownVegetation.LOGGER.info("GiantTreeCreak 近い木 {} 本 いちばん近い木の突風 {} 水平距離 {} 鳴った回数 {}", trees.size(),
                    String.format("%.2f", GustField.INSTANCE.strength(t.axisX(), t.axisZ(), time)),
                    String.format("%.1f", Math.sqrt(GiantLifeTrees.distSqr(t, player.getX(), player.getZ()))), creaks);
        }
    }
}
