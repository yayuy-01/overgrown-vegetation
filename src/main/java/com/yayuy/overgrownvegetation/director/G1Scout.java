package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * 段階 0 の 1 体が受けた「偵察の使い」(2026-11-14、司令塔 第 2 段 区切り 2。D-11・D-12)。司令塔(見張り {@link BaseWatch})が、
 * 覚えた拠点のまわりにいる段階 0 を選んで渡す。歩くのは {@link G1ScoutGoal}。
 *
 * <ol>
 *   <li><b>行く</b>: 拠点の真ん中から {@code scoutWatchMin}〜{@code scoutWatchMax} ブロックの所(偵察の来た側)まで歩く。</li>
 *   <li><b>眺める</b>: 立ち止まり、{@code scoutWatchMinTicks}〜{@code scoutWatchMaxTicks} の間、近くのプレイヤー(いなければ拠点の真ん中)を見る。
 *   眺め終えたら、拠点は「偵察済み」になる(認識までの日数を数え始める。11-14 ユーザー決定 Q5 = 1)。</li>
 *   <li><b>去る</b>: 拠点から離れる向きへ、{@code scoutLeaveBlocks} ブロック離れるまで(か {@code scoutLeaveTicks} まで)歩いて、使いは終わり。</li>
 * </ol>
 * 偵察の間はプレイヤーを狙わない。殴られたら殴り返す(根付きの歩きと同じ)。<b>偵察を倒すと、逆に認識が進む</b>
 * (11-14 ユーザー決定「倒すと逆に認識が進むようにしたい」= 森に見つかったことになる。{@link BaseMemory#onScoutKilled})。
 */
public final class G1Scout {

    public enum Phase {
        NONE, GO, WATCH, LEAVE
    }

    private final AbstractParasitizedMonster mob;
    private Phase phase = Phase.NONE;
    private int baseId;
    private double baseX;
    private double baseZ;
    /** 眺める場所(行く間の目あて)。 */
    private double watchX;
    private double watchZ;
    /** その段に入ってからの tick と、眺める長さ。 */
    private int ticks;
    private int watchTicks;

    G1Scout(AbstractParasitizedMonster mob) {
        this.mob = mob;
    }

    public Phase phase() {
        return phase;
    }

    public boolean active() {
        return phase != Phase.NONE;
    }

    public int baseId() {
        return baseId;
    }

    public Vec3 baseCenter() {
        return new Vec3(baseX, mob.getY(), baseZ);
    }

    public Vec3 watchPoint() {
        return new Vec3(watchX, mob.getY(), watchZ);
    }

    /** 偵察に出す。眺める場所は、拠点の真ん中から見て偵察のいる側に決める。 */
    public void begin(BaseMemory.Base base) {
        BaseMemoryTable t = DesignData.director().base;
        phase = Phase.GO;
        baseId = base.id();
        baseX = base.centerX();
        baseZ = base.centerZ();
        double dx = mob.getX() - baseX, dz = mob.getZ() - baseZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-6D) {
            dx = 1.0D;
            dz = 0.0D;
            len = 1.0D;
        }
        double dist = t.scoutWatchMin + mob.getRandom().nextDouble() * (t.scoutWatchMax - t.scoutWatchMin);
        // 弱い所を突く: 守りの薄い方角から眺める(回り込む割合 × 偵察の段の強さ。第 3 段)
        if (base.weakSector() >= 0 && mob.level() instanceof ServerLevel sl
                && mob.getRandom().nextDouble() < t.flankShare * DirectorGates.strength(sl, DirectorGates.Gate.SCOUT)) {
            double a = BaseDefense.angleOf(base.weakSector(), t.weakSectors);
            dx = Math.cos(a);
            dz = Math.sin(a);
            len = 1.0D;
        }
        watchX = baseX + dx / len * dist;
        watchZ = baseZ + dz / len * dist;
        ticks = 0;
        watchTicks = t.scoutWatchMinTicks + mob.getRandom().nextInt(t.scoutWatchMaxTicks - t.scoutWatchMinTicks + 1);
        mob.setTarget(null);
    }

    /** 毎 tick(サーバー。{@link G1Fate#tick} から)。 */
    void tick(ServerLevel level) {
        if (phase == Phase.NONE) return;
        BaseMemoryTable t = DesignData.director().base;
        if (G1Fate.retaliating(mob)) return; // 殴り返している間は止める(時間も数えない)
        ticks++;
        switch (phase) {
            case GO -> {
                double dx = mob.getX() - watchX, dz = mob.getZ() - watchZ;
                double bx = mob.getX() - baseX, bz = mob.getZ() - baseZ;
                boolean arrived = dx * dx + dz * dz <= 16.0D || bx * bx + bz * bz <= (t.scoutWatchMax + 2.0D) * (t.scoutWatchMax + 2.0D);
                if (arrived && mob.onGround()) {
                    phase = Phase.WATCH;
                    ticks = 0;
                    mob.getNavigation().stop();
                } else if (ticks >= t.scoutGoTimeoutTicks) {
                    // 着けなかった(崖・水・塀)。眺めずに帰る = 拠点の段は進まない
                    phase = Phase.LEAVE;
                    ticks = 0;
                }
            }
            case WATCH -> {
                if (ticks >= watchTicks) {
                    BaseMemory.get(level.getServer()).onScouted(baseId, BaseMemory.today(level.getServer()), mob.getRandom());
                    phase = Phase.LEAVE;
                    ticks = 0;
                }
            }
            case LEAVE -> {
                double bx = mob.getX() - baseX, bz = mob.getZ() - baseZ;
                if (ticks >= t.scoutLeaveTicks || bx * bx + bz * bz >= (double) t.scoutLeaveBlocks * t.scoutLeaveBlocks) {
                    phase = Phase.NONE;
                    ticks = 0;
                    mob.getNavigation().stop();
                }
            }
            default -> {
            }
        }
    }

    /** 倒された(呼ぶのは死の知らせ)。プレイヤーに倒されたら、拠点の認識が進む。 */
    void onKilledByPlayer(ServerLevel level) {
        if (phase == Phase.NONE) return;
        BaseMemory.get(level.getServer()).onScoutKilled(baseId, BaseMemory.today(level.getServer()), mob.getRandom());
        phase = Phase.NONE;
    }

    /** やめる(司令塔が道を決めたときなど)。 */
    void cancel() {
        phase = Phase.NONE;
        ticks = 0;
    }

    // ---------------------------------------------------------------- 保存

    void save(CompoundTag f) {
        if (phase == Phase.NONE) return;
        CompoundTag s = new CompoundTag();
        s.putString("Phase", phase.name());
        s.putInt("Base", baseId);
        s.putDouble("BX", baseX);
        s.putDouble("BZ", baseZ);
        s.putDouble("WX", watchX);
        s.putDouble("WZ", watchZ);
        s.putInt("Ticks", ticks);
        s.putInt("Watch", watchTicks);
        f.put("Scout", s);
    }

    void load(CompoundTag f) {
        phase = Phase.NONE;
        if (!f.contains("Scout")) return;
        CompoundTag s = f.getCompound("Scout");
        try {
            phase = Phase.valueOf(s.getString("Phase"));
        } catch (IllegalArgumentException e) {
            phase = Phase.NONE;
        }
        baseId = s.getInt("Base");
        baseX = s.getDouble("BX");
        baseZ = s.getDouble("BZ");
        watchX = s.getDouble("WX");
        watchZ = s.getDouble("WZ");
        ticks = s.getInt("Ticks");
        watchTicks = s.getInt("Watch");
    }

    // ---------------------------------------------------------------- テスト用

    public void setTicksForTests(int t) {
        ticks = t;
    }
}
