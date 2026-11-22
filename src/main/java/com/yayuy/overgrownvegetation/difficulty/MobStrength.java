package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.MobClassTable;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MOD の設定の「Mob の強さ」(2026-11-19 ユーザー決定: 体力・攻撃力・防御力・強靭の 4 項目を全体の % と Mob ごとの詳細で)を
 * 実際の Mob に掛ける。
 *
 * <p><b>対象</b> = {@code design/mob_class.json} にある Mob のうち環境生物でない物(寄生種・眷属種・植物種)。
 * 詳細の画面の一覧も同じ表から作る({@link #roster})。
 *
 * <ul>
 *     <li><b>体力・防御力・強靭</b>: 属性(最大体力・防具・防具の強靭さ)に {@code ADD_MULTIPLIED_TOTAL} の修飾を 1 つずつ。
 *     <b>永続の修飾</b>にする(一時の修飾だと、保存から読み込むとき修飾が付く前に体力が元の最大値で切られる)。もとが 0 の防具・強靭は
 *     何 % でも 0。バニラの上限(防具 30・強靭 20・最大体力 1024)で頭打ちになる。</li>
 *     <li><b>攻撃力</b>: 属性ではなく、生き物が受けるダメージに掛ける(定数や design の値で殴る Mob・矢・爆発の間接攻撃も入るように。
 *     前の「敵の強さ」{@code EnemyStrengthDamage} と同じ作法)。属性には掛けない(二重にしない)。</li>
 * </ul>
 *
 * <p>今いる Mob にも効かせる【仮: 効かせる。11-19】: ワールドに入るたび({@link EntityJoinLevelEvent})に今の値へ置き直し、
 * 設定のページで変えたときは読み込まれている Mob を全部置き直す({@link #reapplyAll})。体力はいつも割合を保つ(ふつうに湧いた Mob は満タンのまま、進化で出た Mob は減っていた割合のまま)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MobStrength {

    private static final ResourceLocation HEALTH_ID = OvergrownVegetation.id("difficulty_health");
    private static final ResourceLocation DEFENSE_ID = OvergrownVegetation.id("difficulty_defense");
    private static final ResourceLocation TOUGHNESS_ID = OvergrownVegetation.id("difficulty_toughness");

    private MobStrength() {
    }

    /** この種が Mob の強さの対象か(mob_class.json にあり、環境生物でない)。 */
    public static boolean isTarget(EntityType<?> type) {
        MobClassTable.Entry e = DesignData.mobClass().get(type);
        return e != null && e.lineage() != null && e.lineage() != MobClassTable.Lineage.CRITTER;
    }

    /** 詳細の画面に並べる Mob の id({@code namespace:path}、mob_class.json の並び。登録の無い id は除く)。 */
    public static List<String> roster() {
        List<String> out = new ArrayList<>();
        Map<String, MobClassTable.Entry> mobs = DesignData.mobClass().mobs;
        if (mobs == null) return out;
        mobs.forEach((path, e) -> {
            if (e == null || e.lineage() == null || e.lineage() == MobClassTable.Lineage.CRITTER) return;
            ResourceLocation id = OvergrownVegetation.id(path);
            if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) out.add(id.toString());
        });
        return out;
    }

    public static String idOf(Entity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    // ------------------------------------------------------------------ 体力・防御力・強靭

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof Mob mob && isTarget(mob.getType())) {
            apply(level.getServer(), mob, !event.loadedFromDisk());
        }
    }

    /** 読み込まれている対象の Mob を全部、今の値へ置き直す(設定を変えたとき)。体力は割合を保つ。 */
    public static int reapplyAll(MinecraftServer server) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getAllEntities()) {
                if (e instanceof Mob mob && mob.isAlive() && isTarget(mob.getType())) {
                    apply(server, mob, false);
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * この Mob の修飾を今の値に合わせる。
     *
     * @param fresh 新しく湧いた(保存から読んだのではない)。今は体力の扱いは変わらない(どちらも割合を保つ)
     */
    public static void apply(MinecraftServer server, Mob mob, boolean fresh) {
        String id = idOf(mob);
        float ratio = mob.getMaxHealth() > 0.0F ? mob.getHealth() / mob.getMaxHealth() : 1.0F;
        boolean healthChanged = put(mob, Attributes.MAX_HEALTH, HEALTH_ID,
                WorldDifficultyData.mobStat(server, id, DifficultySetting.MOB_HEALTH));
        put(mob, Attributes.ARMOR, DEFENSE_ID, WorldDifficultyData.mobStat(server, id, DifficultySetting.MOB_DEFENSE));
        put(mob, Attributes.ARMOR_TOUGHNESS, TOUGHNESS_ID, WorldDifficultyData.mobStat(server, id, DifficultySetting.MOB_TOUGHNESS));
        // 新しく湧いた Mob も割合を保つ(ふつうに湧いた Mob は割合 1 = 満タン。進化・入れ替えで出た Mob は、ワールドに入る前に
        // AbstractParasitizedMonster#copyConditionTo が減っていた割合を写しているので、満タンに戻さない)
        if (healthChanged && mob.isAlive()) {
            mob.setHealth(Math.max(0.5F, Math.min(mob.getMaxHealth(), ratio * mob.getMaxHealth())));
        }
    }

    /** 修飾を倍率 {@code multiplier} に合わせる。変えたら true。100% なら修飾を外す。 */
    private static boolean put(Mob mob, Holder<Attribute> attribute, ResourceLocation modifierId, double multiplier) {
        AttributeInstance inst = mob.getAttribute(attribute);
        if (inst == null) return false;
        double want = multiplier - 1.0D;
        AttributeModifier now = inst.getModifier(modifierId);
        if (Math.abs(want) < 1.0E-9D) {
            return now != null && inst.removeModifier(modifierId);
        }
        if (now != null && Math.abs(now.amount() - want) < 1.0E-9D) return false;
        inst.addOrReplacePermanentModifier(new AttributeModifier(modifierId, want, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        return true;
    }

    /** テスト用: 今付いている修飾の倍率(無ければ 1)。 */
    public static double appliedMultiplier(Mob mob, DifficultySetting stat) {
        Holder<Attribute> attr = switch (stat) {
            case MOB_HEALTH -> Attributes.MAX_HEALTH;
            case MOB_DEFENSE -> Attributes.ARMOR;
            case MOB_TOUGHNESS -> Attributes.ARMOR_TOUGHNESS;
            default -> null;
        };
        ResourceLocation id = stat == DifficultySetting.MOB_HEALTH ? HEALTH_ID
                : stat == DifficultySetting.MOB_DEFENSE ? DEFENSE_ID : TOUGHNESS_ID;
        AttributeInstance inst = attr == null ? null : mob.getAttribute(attr);
        AttributeModifier m = inst == null ? null : inst.getModifier(id);
        return m == null ? 1.0D : 1.0D + m.amount();
    }

    // ------------------------------------------------------------------ 攻撃力

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        Mob attacker = attackerOf(event.getSource().getEntity());
        if (attacker == null) return;
        double m = WorldDifficultyData.mobStat(level.getServer(), idOf(attacker), DifficultySetting.MOB_ATTACK);
        if (m != 1.0D) event.setAmount((float) (event.getAmount() * m));
    }

    @Nullable
    private static Mob attackerOf(@Nullable Entity source) {
        return source instanceof Mob mob && isTarget(mob.getType()) ? mob : null;
    }
}
