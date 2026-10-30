package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.MobSizeVariance;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 体格(属性 SCALE。mobs.json の {@code scale} と個体差)を持つ Mob が、湧いた直後・読み込み直した直後の
 * <b>最初の tick に沈まない</b>ことのテスト(2026-10-01)。
 *
 * <p>作った直後の当たり判定は素の大きさで、SCALE が掛かるのは最初の tick の終わり({@code LivingEntity#tick} の appliedScale)。
 * そこで箱が育つと、バニラの {@code Entity#fudgePositionAfterSizeChange} が箱の中心を保つように足元を
 * (育った高さ − 素の高さ) / 2 下げる(足場があればその上で止まるので、効くのは空中・水中)。
 * {@link MobSizeVariance} が世界に入るとき(まだ firstTick なので押し下げが起きない)に測り直して防ぐ。
 *
 * <p>対象は登録から拾う: 体格込みの箱が素の箱より大きくなり、育った箱が 4 × 4 以内(超えるとバニラは押し下げない)、
 * noPhysics でない種。空中に NoAI・NoGravity で置いて(AI と重力で動かないように)、置いた直後と 3 tick 後の足元の高さを比べる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ScaledBodyGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ScaledBodyGameTests.class);
        }

        private Registration() {
        }
    }

    /** 置く足元(相対)。床の上面は y=2 なので 1 浮かせ、育っても床に届かない高さ。 */
    private static final Vec3 FEET = new Vec3(3.5D, 3.0D, 3.5D);
    /** 読み込み直しのテストで掛ける個体差(保存された SCALE が登録の基礎値と違う個体 = 個体差のある種・mobs.json を変えた後の古い個体)。 */
    private static final double VARIANCE = 1.1D;
    private static final double EPS = 1.0E-3D;

    /** バニラの押し下げが効きうる種(体格 × {@code individual} で箱が育ち、育った箱が 4 × 4 以内、noPhysics でない)。 */
    private static List<EntityType<?>> sinkCandidates(ServerLevel level, double individual) {
        List<EntityType<?>> out = new ArrayList<>();
        for (var holder : ModEntities.ENTITY_TYPES.getEntries()) {
            EntityType<?> type = holder.get();
            Entity probe = type.create(level);
            if (!(probe instanceof Mob mob) || mob.noPhysics) {
                continue;
            }
            EntityDimensions grown = mob.getDimensions(mob.getPose()).scale((float) individual);
            EntityDimensions base = type.getDimensions();
            boolean grows = grown.width() > base.width() + EPS || grown.height() > base.height() + EPS;
            if (grows && grown.width() <= 4.0F && grown.height() <= 4.0F) {
                out.add(type);
            }
        }
        return out;
    }

    private static String id(EntityType<?> type) {
        return EntityType.getKey(type).getPath();
    }

    /** 湧かせた直後(世界に入ってまだ tick していない)の足元の高さを覚え、3 tick 後に沈んでいないか見る。 */
    private static void expectNoSink(GameTestHelper helper, Map<Mob, Double> placed, String what) {
        helper.runAfterDelay(3L, () -> {
            List<String> sunk = new ArrayList<>();
            for (Map.Entry<Mob, Double> e : placed.entrySet()) {
                Mob mob = e.getKey();
                double drop = e.getValue() - mob.getY();
                if (Math.abs(drop) > EPS) {
                    sunk.add(id(mob.getType()) + String.format(" %.3f", drop));
                }
            }
            if (!sunk.isEmpty()) {
                helper.fail(what + "の最初の tick に沈んだ(下がった量): " + String.join(", ", sunk));
                return;
            }
            helper.succeed();
        });
    }

    /** 湧かせた個体(スポーンエッグ・/summon・湧きの関門と同じく addFreshEntity で世界に入る)が沈まない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void scaledBodyDoesNotSinkOnSpawn(GameTestHelper helper) {
        List<EntityType<?>> types = sinkCandidates(helper.getLevel(), 1.0D);
        if (types.isEmpty()) {
            helper.fail("体格で箱が育つ種が見つからない(拾い方が壊れている)");
            return;
        }
        Map<Mob, Double> placed = new LinkedHashMap<>();
        for (EntityType<?> type : types) {
            Mob mob = (Mob) helper.spawn(type, FEET);
            mob.setNoAi(true);
            mob.setNoGravity(true);
            mob.setDeltaMovement(Vec3.ZERO);
            placed.put(mob, mob.getY());
        }
        expectNoSink(helper, placed, "湧いた直後");
    }

    /**
     * チャンクの読み込み相当(保存した NBT から作り直して世界に足す)でも沈まない。保存された SCALE が
     * 登録の基礎値と違う個体(個体差・mobs.json を変えた後の古い個体)では、コンストラクタで体格を掛けておくだけでは足りない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void scaledBodyDoesNotSinkOnReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<EntityType<?>> types = sinkCandidates(level, VARIANCE);
        if (types.isEmpty()) {
            helper.fail("体格で箱が育つ種が見つからない(拾い方が壊れている)");
            return;
        }
        Vec3 at = helper.absoluteVec(FEET);
        Map<Mob, Double> placed = new LinkedHashMap<>();
        for (EntityType<?> type : types) {
            Mob original = (Mob) type.create(level);
            original.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
            original.setNoAi(true);
            original.setNoGravity(true);
            AttributeInstance scale = original.getAttribute(Attributes.SCALE);
            float individual = (float) (scale.getBaseValue() * VARIANCE);
            scale.setBaseValue(individual);
            original.setData(ModAttachments.SIZE_VARIANCE, individual);
            CompoundTag saved = new CompoundTag();
            original.save(saved);
            Mob loaded = (Mob) EntityType.create(saved, level).orElseThrow();
            level.addFreshEntity(loaded);
            placed.put(loaded, loaded.getY());
        }
        expectNoSink(helper, placed, "読み込み直した直後");
    }
}
