package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 巣の核({@code entity/NestCoreEntity})が配る永続強化(2026-09-20)。
 *
 * <h2>決まり(ユーザー指定 2026-09-19)</h2>
 * <ul>
 *     <li>効果は<b>世界全体</b>。核のある巣の近くだけ、ではない。</li>
 *     <li><b>複数の巣で重複する</b>。上限は設けない。</li>
 *     <li>どの能力を上げる核かは<b>プレイヤーに見せない</b>。抽選は
 *     体力 +2 が 70% / 攻撃力 +4 が 20% / 防御力 +2 が 10%。</li>
 *     <li><b>湧いた瞬間に焼き付く</b>。核を壊しても、それまでに湧いた個体の強化は戻らない。</li>
 * </ul>
 *
 * <p>「湧いた瞬間に焼き付く」ので、強化は永続の属性修飾子として 1 回だけ付ける。
 * 核が生きているかどうかは {@link Cores}(次元ごとの SavedData)が覚える。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NestBuffs {

    /** 核が上げる能力。 */
    public enum Stat {
        HEALTH,
        ATTACK,
        ARMOR
    }

    private static final ResourceLocation HEALTH_ID = OvergrownVegetation.id("nest_core_health");
    private static final ResourceLocation ATTACK_ID = OvergrownVegetation.id("nest_core_attack");
    private static final ResourceLocation ARMOR_ID = OvergrownVegetation.id("nest_core_armor");

    // --- 保存 ---

    /** 生きている核の一覧(核の UUID → その核が上げる能力)。 */
    public static final class Cores extends SavedData {

        private static final String NAME = "overgrown_vegetation_nest_cores";

        public static final SavedData.Factory<Cores> FACTORY =
                new SavedData.Factory<>(Cores::new, Cores::load, null);

        private final Map<UUID, Stat> cores = new LinkedHashMap<>();

        private Cores() {
        }

        private static Cores load(CompoundTag tag, HolderLookup.Provider registries) {
            Cores data = new Cores();
            ListTag list = tag.getList("Cores", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                try {
                    data.cores.put(entry.getUUID("Id"), Stat.valueOf(entry.getString("Stat")));
                } catch (IllegalArgumentException ignored) {
                    // 壊れた項目は捨てる(列挙が変わったときなど)
                }
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            cores.forEach((id, stat) -> {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("Id", id);
                entry.putString("Stat", stat.name());
                list.add(entry);
            });
            tag.put("Cores", list);
            return tag;
        }

        public Map<UUID, Stat> all() {
            return cores;
        }
    }

    private static Cores data(ServerLevel level) {
        DimensionDataStorage storage = level.getServer().overworld().getDataStorage();
        return storage.computeIfAbsent(Cores.FACTORY, Cores.NAME);
    }

    // --- 出入り ---

    /** 核が湧いた / 読み込まれた。既に入っていれば何もしない。 */
    public static void add(ServerLevel level, UUID id, Stat stat) {
        Cores cores = data(level);
        if (cores.all().putIfAbsent(id, stat) == null) {
            cores.setDirty();
        }
    }

    /** 核が倒された。 */
    public static void remove(ServerLevel level, UUID id) {
        Cores cores = data(level);
        if (cores.all().remove(id) != null) {
            cores.setDirty();
        }
    }

    /** 核が上げる能力を 1 つ引く。生成時に 1 回だけ回す。 */
    public static Stat roll(RandomSource random) {
        NestTable t = DesignData.nest();
        double total = t.coreHealthChance() + t.coreAttackChance() + t.coreArmorChance();
        if (total <= 0.0D) {
            return Stat.HEALTH;
        }
        double r = random.nextDouble() * total;
        if ((r -= t.coreHealthChance()) < 0.0D) {
            return Stat.HEALTH;
        }
        if ((r -= t.coreAttackChance()) < 0.0D) {
            return Stat.ATTACK;
        }
        return Stat.ARMOR;
    }

    /** 今かかっている強化の合計(体力 / 攻撃力 / 防御力)。 */
    public static double[] totals(ServerLevel level) {
        NestTable t = DesignData.nest();
        double[] out = new double[3];
        for (Stat stat : data(level).all().values()) {
            switch (stat) {
                case HEALTH -> out[0] += t.coreHealthBonus();
                case ATTACK -> out[1] += t.coreAttackBonus();
                case ARMOR -> out[2] += t.coreArmorBonus();
            }
        }
        return out;
    }

    // --- 適用 ---

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        if (MobGroup.of(mob.getType()) == null) {
            return;                                   // 過繫茂 Mob だけが強化を受ける
        }
        double[] totals = totals(level);
        if (totals[0] <= 0.0D && totals[1] <= 0.0D && totals[2] <= 0.0D) {
            return;
        }
        boolean healed = addOnce(mob, Attributes.MAX_HEALTH, HEALTH_ID, totals[0]);
        addOnce(mob, Attributes.ATTACK_DAMAGE, ATTACK_ID, totals[1]);
        addOnce(mob, Attributes.ARMOR, ARMOR_ID, totals[2]);
        if (healed) {
            mob.setHealth(mob.getMaxHealth());        // 増えたぶんを満たしておく
        }
    }

    private static boolean addOnce(Mob mob, net.minecraft.core.Holder<Attribute> attribute,
                                   ResourceLocation id, double amount) {
        if (amount <= 0.0D) {
            return false;
        }
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance == null || instance.getModifier(id) != null) {
            return false;
        }
        instance.addPermanentModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
        return true;
    }

    private NestBuffs() {
    }
}
