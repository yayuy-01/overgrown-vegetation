package com.yayuy.overgrownvegetation.corpse;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedFlyer;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * 胞子感染で死んだガスト・ブレイズを、<b>死体を作らずその場で</b>寄生体に入れ替える(2026-10-09)。
 *
 * <p>ユーザー指定(10-09): 「ガストやブレイズはそれらの死体を使った生き物。死体は作らず死んだ瞬間寄生された見た目になり、
 * 15 秒間は何もせず死んだ場所で漂うだけになる。15 秒後目を覚ますかのようにして活動を開始する」。
 * 入れ替わった寄生体は {@link AbstractParasitizedFlyer#startDormant} で眠った状態から始まる。
 *
 * <h2>元の Mob の消し方</h2>
 * {@code LivingDeathEvent} の中で元の Mob を消すと、{@code LivingEntity#die} の残り(ドロップ・経験値・倒した記録)が
 * 「もう消えている」として丸ごと飛ばされる。だから死ぬ処理はそのまま最後まで通し、元の Mob はすぐ透明にして、
 * <b>次にその Mob の tick が回ってきたところで</b>消す({@link #PENDING})。死んでから 20 tick 目のバニラの消え方
 * (煙の粒が出る)より前なので、煙も出ない。ドロップと経験値は元の Mob のぶんがそのまま落ちる(牛が死体になっても牛肉を落とすのと同じ。仮)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NetherReanimation {

    /** 元の種 → 寄生体。クラスは厳密一致(SpeciesCorpses と同じ考え方)。 */
    private static final Map<Class<? extends LivingEntity>, Supplier<? extends EntityType<? extends AbstractParasitizedFlyer>>> MAP = Map.of(
            Ghast.class, ModEntities.PARASITIZED_GHAST,
            Blaze.class, ModEntities.PARASITIZED_BLAZE);

    /** 入れ替わった元の Mob。次の tick に消す。 */
    private static final Set<LivingEntity> PENDING = Collections.newSetFromMap(new WeakHashMap<>());

    /** {@code entity} の種の寄生体(子どもは対象外)。無ければ null。汚染したスポナーの対応表にも使う({@code corruption/SpawnerCorruption})。 */
    @org.jetbrains.annotations.Nullable
    public static EntityType<?> parasitizedTypeFor(LivingEntity entity) {
        Supplier<? extends EntityType<? extends AbstractParasitizedFlyer>> supplier = MAP.get(entity.getClass());
        return supplier == null || entity.isBaby() ? null : supplier.get();
    }

    /** 入れ替わる種か。 */
    public static boolean handles(LivingEntity entity) {
        return MAP.containsKey(entity.getClass()) && !entity.isBaby();
    }

    /**
     * 胞子感染で死んだ {@code dead} を寄生体に入れ替える。入れ替わらない種・湧かせてはいけない場所(ピースフル・同盟軍の基地の壁の中)なら
     * 何もせず {@code false}(呼び出し側は汚染シダを生やす)。
     */
    public static boolean begin(LivingEntity dead) {
        Supplier<? extends EntityType<? extends AbstractParasitizedFlyer>> supplier = MAP.get(dead.getClass());
        if (supplier == null || dead.isBaby() || !(dead.level() instanceof ServerLevel level)) {
            return false;
        }
        EntityType<? extends AbstractParasitizedFlyer> type = supplier.get();
        if (com.yayuy.overgrownvegetation.difficulty.Peaceful.blocksSpawn(level, type)
                || com.yayuy.overgrownvegetation.base.build.BaseRefuge.blocksSpawn(level, type, dead.blockPosition())) {
            return false;
        }
        AbstractParasitizedFlyer flyer = type.create(level);
        if (flyer == null) {
            return false;
        }
        flyer.moveTo(dead.getX(), dead.getY(), dead.getZ(), dead.getYRot(), dead.getXRot());
        flyer.yBodyRot = dead.yBodyRot;
        flyer.yBodyRotO = dead.yBodyRot;
        flyer.yHeadRot = dead.yHeadRot;
        flyer.yHeadRotO = dead.yHeadRot;
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(flyer, level,
                level.getCurrentDifficultyAt(dead.blockPosition()), MobSpawnType.CONVERSION, null);
        flyer.startDormant();
        if (dead.hasCustomName()) {
            flyer.setCustomName(dead.getCustomName());
            flyer.setCustomNameVisible(dead.isCustomNameVisible());
        }
        if (dead instanceof Mob mob && mob.isPersistenceRequired()) {
            flyer.setPersistenceRequired();
        }
        level.addFreshEntity(flyer);
        // 元の Mob: 死ぬ処理は最後まで通す(ドロップ・経験値)。見えなくして、次の tick に消す。
        dead.setInvisible(true);
        PENDING.add(dead);
        return true;
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        if (entity instanceof LivingEntity living && !entity.level().isClientSide() && PENDING.remove(living)) {
            living.discard();
        }
    }

    private NetherReanimation() {
    }
}
