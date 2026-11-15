package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.HoardTable;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseStage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 森の気分の移り変わり(2026-11-14、司令塔 第 3 段 区切り 3「貯めて一気に使う」= ② 貯めた襲撃)。見張り({@link BaseWatch})が 20 秒ごとに呼ぶ。
 *
 * <ul>
 *   <li><b>貯め始める</b>: 進化が開いていて(F3〜)、今の段が定着か最盛(導入では起こさない。11-14 ユーザー)、大きな襲撃の前触れでなく、
 *   前に使い終えてから {@code cooldownDays} たち、予算が上限の {@code minBudgetFraction} 以上のとき、1 日に {@code chancePerDay} の割合で。</li>
 *   <li><b>貯める間</b>({@code minDays}〜{@code maxDays} 日): 予算の上限が {@code capFactor} 倍。司令塔は道を選ばず(見に来ても何もしない)、
 *   苗床のスポナー・送り込み・危ない所へ送るのも止める(偵察は続ける)。G1 は襲わず、プレイヤーから {@code holdMin〜holdMax} の所を遠巻きにうろつく
 *   ({@link G1HoldOffGoal}。殴られたら殴り返す)。</li>
 *   <li><b>使う</b>(11-16 置き換え): 認識した拠点があり送り込みが開いていれば(F5〜)、拠点ごとに「まとめた 1 回」の送り込み({@link BaseRaids#sendWave} の hoard。
 *   上限 40 のまま進化体と G5 を 1.5 倍)。11-14 の「遠巻きの G1 を一斉に放つ」はこれに置き換わった(気分が戻れば遠巻きの G1 も放たれる)。
 *   認識した拠点が無いときだけ、前の形: プレイヤーと認識した拠点のまわりの段階 0 を、予算が尽きるか {@code burstMax} 体まで一斉に進化させる(進化の前の震えが何体も同時に
 *   起こる = 見える合図。新しい演出は無し、H2)。認識した拠点があり送り込みが開いていれば(F5〜)、続けて送り込みを 1 回。</li>
 *   <li>大きな襲撃の前触れに入ったら、貯めるのをやめる(貯めた予算は使わずに残す = 大きな襲撃の分になる)。</li>
 * </ul>
 */
public final class ForestMoods {

    private ForestMoods() {
    }

    /** 貯め始めてよいか(純粋な計算)。 */
    public static boolean canStartHoard(boolean evolveOpen, PhaseStage stage, boolean tdOmen, double day, double cooldownUntil,
                                        double budget, double cap, HoardTable t) {
        return evolveOpen && stage != PhaseStage.INTRO && !tdOmen && day >= cooldownUntil && budget + 1.0E-9D >= cap * t.minBudgetFraction;
    }

    static void tick(ServerLevel level, double day, int sampleTicks) {
        ForestDirector d = ForestDirector.get(level);
        d.syncFlag();
        // 大きな襲撃の前触れ・本番の間は、貯めない(LargeRaids が受け持つ)
        if (d.mood() == ForestDirector.Mood.LARGE_OMEN || d.mood() == ForestDirector.Mood.LARGE) return;
        HoardTable t = DesignData.director().hoard;
        RandomSource random = level.getRandom();
        boolean omen = DirectorGates.largeRaidOmen(level);
        if (d.mood() == ForestDirector.Mood.HOARD) {
            if (omen) {
                d.setMood(ForestDirector.Mood.NORMAL, day, day); // 大きな襲撃の前触れ: 使わずにやめる(貯めた分は残る)
                d.note("大きな襲撃の前触れに入ったので、貯めるのをやめた(貯めた予算は残す)");
            } else if (day >= d.moodUntil()) {
                spend(level, day, random, null);
            }
            return;
        }
        double perSample = t.chancePerDay * sampleTicks / 24000.0D;
        if (canStartHoard(DirectorGates.open(level, DirectorGates.Gate.EVOLVE), DirectorGates.stage(level), omen, day, d.hoardCooldownUntil(),
                d.available(level), ForestDirector.cap(level), t) && random.nextDouble() < perSample) {
            startHoard(level, day, t.minDays + random.nextDouble() * (t.maxDays - t.minDays));
        }
    }

    /** 使わずにふだんに戻す(確かめ用・テスト用)。 */
    public static void stopHoard(ServerLevel level, double day) {
        ForestDirector.get(level).setMood(ForestDirector.Mood.NORMAL, day, day);
    }

    /** 貯め始める。 */
    public static void startHoard(ServerLevel level, double day, double days) {
        ForestDirector d = ForestDirector.get(level);
        d.setMood(ForestDirector.Mood.HOARD, day, day + days);
        d.note(String.format(Locale.ROOT, "森が貯め始めた(%.2f 日。予算 %.1f / 上限 %.1f)", days, d.available(level), ForestDirector.cap(level)));
    }

    /**
     * 使う: まわりの段階 0 を一斉に進化させ、認識した拠点があれば送り込む。終わったらふだんに戻る。
     *
     * @param around 段階 0 を探す真ん中(テスト用。null ならプレイヤーと認識した拠点)
     * @return 進化させた数
     */
    public static int spend(ServerLevel level, double day, RandomSource random, @javax.annotation.Nullable List<Vec3> around) {
        ForestDirector d = ForestDirector.get(level);
        HoardTable t = DesignData.director().hoard;
        // 11-16 置き換え(ユーザー): 認識した拠点があり送り込みが開いていれば、貯めた分を「まとめた 1 回」の送り込みで使う
        // (送り込みは貯める間止まっていた = 1〜2 回飛ばした分。上限 40 のまま進化体と G5 を 1.5 倍)。拠点が無いとき(F3・F4 など)は前の形
        if (around == null && DirectorGates.open(level, DirectorGates.Gate.RAID)) {
            BaseMemory memory = BaseMemory.get(level.getServer());
            int sent = 0, waves = 0;
            for (BaseMemory.Base b : List.copyOf(memory.bases())) {
                if (b.stage() != BaseMemory.Stage.RECOGNIZED) continue;
                sent += HoardRaids.begin(level, b, day, random); // まとめた 1 回目 + 継ぎ足し(同時 40・合計 80。11-16 ユーザー)
                memory.waveSent(b, day, random);
                waves++;
            }
            if (waves > 0) {
                d.setMood(ForestDirector.Mood.NORMAL, day, day);
                d.setHoardCooldownUntil(day + t.cooldownDays);
                d.note(String.format(Locale.ROOT, "森が貯めた分を使った: まとめた送り込み %d 回・計 %d 体(残りの予算 %.1f)", waves, sent, d.available(level)));
                return 0;
            }
        }
        List<Vec3> centers = around != null ? around : centers(level);
        Set<AbstractParasitizedMonster> found = new LinkedHashSet<>();
        double r = t.burstSearchBlocks;
        for (Vec3 c : centers) {
            for (AbstractParasitizedMonster m : level.getEntitiesOfClass(AbstractParasitizedMonster.class,
                    new AABB(c.x - r, level.getMinBuildHeight(), c.z - r, c.x + r, level.getMaxBuildHeight(), c.z + r))) {
                if (BaseWatch.canScout(m) && G1Fate.developedOf(m) != null && level.isPositionEntityTicking(m.blockPosition())) found.add(m);
            }
        }
        List<AbstractParasitizedMonster> list = new ArrayList<>(found);
        Collections.shuffle(list, new java.util.Random(random.nextLong()));
        double price = ForestDirector.stagePrice(DesignData.director().costEvolve, DirectorGates.strength(level, DirectorGates.Gate.EVOLVE));
        int evolved = 0;
        boolean evolveOpen = DirectorGates.open(level, DirectorGates.Gate.EVOLVE);
        for (AbstractParasitizedMonster m : list) {
            if (!evolveOpen || evolved >= t.burstMax || !d.tryPay(level, price)) break;
            m.fate().beginEvolve();
            m.fate().markPaid(price);
            // 認識した拠点のそばなら、進化した後に拠点へ向かう(弱い方角から)
            BaseMemory.Base near = ForestDirector.nearestRecognized(level, m.getX(), m.getZ());
            if (near != null) {
                m.march().beginFlank(near, DesignData.director().base.flankShare * DirectorGates.strength(level, DirectorGates.Gate.RAID), random);
            }
            evolved++;
        }
        int waves = 0;
        if (DirectorGates.open(level, DirectorGates.Gate.RAID)) {
            BaseMemory memory = BaseMemory.get(level.getServer());
            for (BaseMemory.Base b : List.copyOf(memory.bases())) {
                if (b.stage() != BaseMemory.Stage.RECOGNIZED) continue;
                BaseRaids.sendWave(level, b, day, random);
                memory.waveSent(b, day, random, DirectorGates.strength(level, DirectorGates.Gate.RAID));
                waves++;
            }
        }
        d.setMood(ForestDirector.Mood.NORMAL, day, day);
        d.setHoardCooldownUntil(day + t.cooldownDays);
        d.note(String.format(Locale.ROOT, "森が一気に使った: 一斉に進化 %d・送り込み %d(残りの予算 %.1f)", evolved, waves, d.available(level)));
        return evolved;
    }

    /** 段階 0 を探す真ん中: サバイバルのプレイヤーと、認識した拠点。 */
    private static List<Vec3> centers(ServerLevel level) {
        List<Vec3> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && !p.isCreative()) out.add(p.position());
        }
        for (BaseMemory.Base b : BaseMemory.get(level.getServer()).bases()) {
            if (b.stage() == BaseMemory.Stage.RECOGNIZED) out.add(new Vec3(b.centerX(), 64.0D, b.centerZ()));
        }
        return out;
    }
}
