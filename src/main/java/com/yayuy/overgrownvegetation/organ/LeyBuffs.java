package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 地脈を吸う根の働き(2026-11-17 ユーザー決定): 根 1 本につき、植物側の Mob 全部(ワールド全体)のステータスを 1 つ上げる。
 * ユーザーの言葉: 「A,B,C の 3 本が生えたら A が体力の上昇、B が攻撃力の上昇、C が防御力の上昇みたいな感じ」。
 *
 * <p>割り当て(11-17 深夜ユーザー「上がる順は設けず、器官が生えた時にランダムで決まるようにしてほしい」): 根が芽吹いたときに
 * 種から 1 つ決まり、枯れるまで変わらない({@link #statIndex})。同じステータスが重なってもよい【仮】。上げ幅は organs.json の {@code roles.ley*}、小さい版は {@code small.strength} 倍。
 * 根が増えた・枯れた・湧いたときに掛け直す。修飾は保存する(11-18 見直し ②3: 一時の修飾だと読み込みのときに体力が素の最大で
 * 頭打ちになり、根の分だけ削られていた個体が満タンに戻っていた。保存すれば読むときの最大に根の分が入っている)。
 * 読み込まれたときにも今の値へ掛け直すので、外れている間に根が枯れても残らない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class LeyBuffs {
    private LeyBuffs() {
    }

    public enum Stat {
        HEALTH("体力", Attributes.MAX_HEALTH, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, true),
        ATTACK("攻撃力", Attributes.ATTACK_DAMAGE, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, true),
        ARMOR("防御力", Attributes.ARMOR, AttributeModifier.Operation.ADD_VALUE, false),
        SPEED("移動速度", Attributes.MOVEMENT_SPEED, AttributeModifier.Operation.ADD_MULTIPLIED_BASE, true),
        KNOCKBACK("ノックバック耐性", Attributes.KNOCKBACK_RESISTANCE, AttributeModifier.Operation.ADD_VALUE, false);

        public final String label;
        final Holder<Attribute> attribute;
        final AttributeModifier.Operation op;
        /** 設計値が % か。 */
        final boolean percent;
        final ResourceLocation id;

        Stat(String label, Holder<Attribute> attribute, AttributeModifier.Operation op, boolean percent) {
            this.label = label;
            this.attribute = attribute;
            this.op = op;
            this.percent = percent;
            this.id = ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "ley_root_" + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** 今の上げ幅({@link Stat} の順。null = 数え直す)。 */
    private static double[] totals;
    /**
     * 読み込まれている Mob へ最後に掛けた上げ幅(11-18 見直し ②2)。前は {@link #totals} と比べていたので、数え直しの印が立ってから
     * {@link #changed} までの間に植物側が 1 体湧くと、{@link #onJoin} が新しい値で埋めて「変わっていない」となり、今いる Mob に掛け直さなかった。
     */
    private static double[] applied;
    /** 根(器官の id)→ 上げるステータス。 */
    private static final Int2IntOpenHashMap ASSIGNED = new Int2IntOpenHashMap();

    /** 根が増えた・減った(OrganWorld#invalidateCaches から)。読み込まれている植物側の Mob(全部の次元)へ掛け直す。 */
    static void changed(ServerLevel level) {
        totals = null;
        double[] now = totals(level);
        if (applied != null && java.util.Arrays.equals(applied, now)) return;
        applied = now.clone();
        for (ServerLevel l : level.getServer().getAllLevels()) {
            for (Entity e : l.getAllEntities()) {
                if (e instanceof LivingEntity le && PlantSideRules.isPlantSide(le)) apply(le, now);
            }
        }
    }

    /** サーバーを止めたとき(次のワールドで必ず掛け直す)。 */
    static void forgetApplied() {
        applied = null;
    }

    /** 今の上げ幅。 */
    public static double[] totals(ServerLevel level) {
        if (totals != null) return totals;
        OrganTable t = DesignData.organs();
        double[] sum = new double[Stat.values().length];
        int[] count = new int[sum.length];
        ASSIGNED.clear();
        if (t.enabled) {
            List<ForestOrgans.Organ> roots = new ArrayList<>();
            for (ForestOrgans.Organ o : ForestOrgans.get(level).organs()) {
                if (o.kind == OrganKind.LEY && o.active()) roots.add(o);
            }
            roots.sort(Comparator.comparingInt(o -> o.id));
            for (ForestOrgans.Organ o : roots) {
                int best = statIndex(o);
                count[best]++;
                sum[best] += t.leyStat[best] * t.strength(o.small);
                ASSIGNED.put(o.id, best);
            }
        }
        totals = sum;
        return sum;
    }

    /** 根の種から決まるステータス(芽吹いたときに決まり、変わらない)。 */
    static int statIndex(ForestOrgans.Organ o) {
        return Math.floorMod(new java.util.Random(o.seed * 0x9E3779B97F4A7C15L).nextInt(), Stat.values().length);
    }

    /** その根が上げているステータス(生きていなければ null)。 */
    public static Stat statOf(ServerLevel level, ForestOrgans.Organ o) {
        totals(level);
        return ASSIGNED.containsKey(o.id) ? Stat.values()[ASSIGNED.get(o.id)] : null;
    }

    static void reset() {
        totals = null;
        ASSIGNED.clear();
    }

    /** 1 体に掛け直す。 */
    static void apply(LivingEntity e, double[] t) {
        for (Stat s : Stat.values()) {
            AttributeInstance inst = e.getAttribute(s.attribute);
            if (inst == null) continue;
            double v = t[s.ordinal()];
            if (v <= 0.0D) {
                inst.removeModifier(s.id);
            } else {
                double amount = s.percent ? v / 100.0D : v;
                AttributeModifier cur = inst.getModifier(s.id);
                if (cur != null && cur.amount() == amount && cur.operation() == s.op) continue;
                inst.addOrReplacePermanentModifier(new AttributeModifier(s.id, amount, s.op));
            }
        }
        if (e.getHealth() > e.getMaxHealth()) e.setHealth(e.getMaxHealth());
    }

    /** 一度世界に入った印(保存される)。読み直した個体を「湧いたばかり」と見ない。 */
    private static final String SEEN = "ov_ley_seen";

    private static boolean hasAny(LivingEntity e) {
        for (Stat s : Stat.values()) {
            AttributeInstance inst = e.getAttribute(s.attribute);
            if (inst != null && inst.getModifier(s.id) != null) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof LivingEntity le)) return;
        if (!PlantSideRules.isPlantSide(le)) return;
        double[] t = totals(level.getServer().overworld());
        // 湧いたばかり(保存から読んだ個体ではない)。読んだ個体は修飾も保存されているので、体力は根の分を含めて元のまま
        boolean fresh = le.tickCount == 0 && !le.getPersistentData().contains(SEEN) && le.getHealth() >= le.getMaxHealth();
        le.getPersistentData().putBoolean(SEEN, true);
        // 読み込まれた個体は、保存した修飾が今の値と違えば(外れている間に根が変わった)直す。どれも無ければ何もしない
        boolean any = false;
        for (double v : t) if (v > 0.0D) any = true;
        if (!any && !hasAny(le)) return;
        apply(le, t);
        if (fresh) le.setHealth(le.getMaxHealth()); // 湧いたばかりは満タンで
    }
}
