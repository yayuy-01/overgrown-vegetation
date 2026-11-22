package com.yayuy.overgrownvegetation.adaptation;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.AdaptationTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;

/**
 * <b>グループ3(適応種)だけの特性</b> —— 同じ武器種で倒され続けると、その武器種への耐性が付く(2026-09-23)。
 *
 * <ul>
 *     <li><b>種ごと</b>に記録する(G3 全体で共有しない)。カマキリを剣で倒しても、別の種の剣耐性は上がらない。</li>
 *     <li>1 キルで 2%、全武器種の合計は最大 30%。上限を超えたら全欄を比例して縮める
 *     ({@link AdaptationData} の「B 方式」)。</li>
 *     <li><b>プレイヤー以外に倒されても数える</b>(ゴーレム・砲台・他の Mob・落下や溶岩も)。</li>
 *     <li>武器種の判定は {@link WeaponClassifier}。判定できない mod の武器はアイテム id がそのまま 1 つの欄になる。</li>
 *     <li>耐性をプレイヤーに見せる手段は、今は用意していない(ユーザー指定)。</li>
 * </ul>
 *
 * <p>軽減は {@link LivingDamageEvent.Pre}(防具・耐性エフェクトの後、衝撃吸収の前)で掛ける。
 * 「被ダメージが 30% 減る」をそのまま実現するため。防具の前(素のダメージ)で掛けると、防具の軽減量が
 * ダメージ量で変わるぶん実際の減り方が 30% からずれる。
 *
 * <p>グループ3の所属は {@code #overgrown_vegetation:group_3}。(2026-09-23 時点ではファルキムネスだけ。)
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class Adaptation {

    /** 適応する種か。 */
    public static boolean isAdaptive(EntityType<?> type) {
        return MobGroup.GROUP_3.contains(type);
    }

    /**
     * 耐性の合計の上限。<b>記憶線(極相の器官、未実装)はここに上乗せする</b> —— 読むときにも掛けるので、
     * 器官が生えたり壊れたりしても台帳は書き直さなくてよい。
     */
    public static double cap(MinecraftServer server) {
        if (server != null && !com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.on(server, com.yayuy.overgrownvegetation.difficulty.DifficultySetting.ADAPTATION)) return 0.0D; // MOD の設定「G3 の適応」(11-19)。記録は消さない
        return DesignData.adaptation().cap();
    }

    /** {@code type} が {@code source} から受ける耐性(0〜1)。数えないダメージなら 0。 */
    public static double resistance(MinecraftServer server, EntityType<?> type, DamageSource source) {
        String key = WeaponClassifier.classify(source, DesignData.adaptation());
        if (key == null) {
            return 0.0D;
        }
        return AdaptationData.get(server).resistance(speciesId(type), key, cap(server));
    }

    /** {@code type} の 1 体が {@code source} で倒された。数えないダメージなら何もしない。 */
    public static void recordKill(MinecraftServer server, EntityType<?> type, DamageSource source) {
        AdaptationTable table = DesignData.adaptation();
        String key = WeaponClassifier.classify(source, table);
        if (key == null) {
            return;
        }
        AdaptationData.get(server).record(speciesId(type), key, table.perKill(), cap(server), table.forgetBelow());
    }

    public static String speciesId(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    @SubscribeEvent
    public static void onDamagePre(LivingDamageEvent.Pre event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level) || !isAdaptive(victim.getType())) {
            return;
        }
        double r = resistance(level.getServer(), victim.getType(), event.getSource());
        if (r > 0.0D) {
            event.setNewDamage((float) (event.getNewDamage() * (1.0D - Math.min(1.0D, r))));
        }
    }

    /** 最後に聞く —— トーテムなどで死亡が取り消されたら数えない。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (event.isCanceled() || !(victim.level() instanceof ServerLevel level) || !isAdaptive(victim.getType())) {
            return;
        }
        recordKill(level.getServer(), victim.getType(), event.getSource());
    }

    private Adaptation() {
    }
}
