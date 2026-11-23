package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * コードの見直し 第 2 回(11-18)の器官の直し: ②1 形が変わっても配列の外を読まない・②2 根のバフの掛け直し漏れ・
 * ②3 読み込み直しで全快しない・②4 守りを UUID で数える。器官の記録と根のバフは世界に 1 つなので、テストごとに別の batch。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class OrganReview2GameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        // 1.20.1: 受け手のメソッドの名前はクラスごとに変える(Forge の EventBus は「クラスの単純名_メソッド名_イベントの単純名」で包みを作って使い回すので、同じパッケージの同じ名前の入れ子のクラスがぶつかり、前の受け手が 2 回呼ばれる)
        @SubscribeEvent
        public static void onOrganReview2GameTests(RegisterGameTestsEvent event) {
            event.register(OrganReview2GameTests.class);
        }

        private Registration() {
        }
    }

    private OrganReview2GameTests() {
    }

    private static EntityType<?> gregaria() {
        return BuiltInRegistries.ENTITY_TYPE.get(OvergrownVegetation.id("gregaria"));
    }

    /** ②1: 保存の数が設計図より長くても頭打ちになる・形の指紋が違えば置き直す・古い保存は数が合えば続きから・指紋は種で決まる。 */
    @GameTest(template = "gametest_empty7", batch = "review2_organ_fit", timeoutTicks = 200)
    public static void savedCountsFitBlueprint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        double budget = ForestDirector.get(level).available(level);
        ForestOrgans.Organ o = data.plant(level, OrganKind.SAC, true, 0, 4242, 1, 9_200_000, 0);
        try {
            OrganBlueprint bp = OrganWorld.blueprintNow(o);
            int n = bp.entries.length;
            helper.assertTrue(n > 0, "設計図が空");
            helper.assertTrue(OrganBlueprint.create(o.kind, o.small, o.variant, o.seed, o.rot).shapeHash == bp.shapeHash, "同じ種の設計図の指紋が違う(起動のたびに置き直してしまう)");
            helper.assertTrue(o.shapeHash == bp.shapeHash && o.total == n, "設計図を得ても指紋・数が入らない");
            // 形が縮んだ後の保存(同じ指紋のまま数だけ長い)= 頭打ち
            o.placed = n + 500;
            o.withered = n + 900;
            OrganWorld.fit(o, bp);
            helper.assertTrue(o.placed == n && o.withered == n, "頭打ちにならない: placed " + o.placed + "・withered " + o.withered + "(長さ " + n + ")");
            // 役割の処理(嚢の生え直しはくじで placed までを読む)も置く処理も、設計図を受け取る口で頭打ちされる
            o.placed = n + 77;
            OrganBlueprint again = OrganWorld.blueprint(o);
            helper.assertTrue(again != null && o.placed <= n, "設計図を渡す口で頭打ちされない: " + o.placed);
            o.state = ForestOrgans.State.ALIVE;
            // 形を作り替えた(指紋が違う): 生きていれば最初から置き直す
            o.placed = Math.min(10, n);
            o.withered = 0;
            o.shapeHash = bp.shapeHash ^ 0x5A5A;
            OrganWorld.fit(o, bp);
            helper.assertTrue(o.placed == 0 && o.shapeHash == bp.shapeHash && o.refit, "形が変わっても置き直さない: placed " + o.placed);
            // 枯れていれば全部を枯らし直す
            o.state = ForestOrgans.State.WITHERED;
            o.placed = 3;
            o.withered = 3;
            o.shapeHash = bp.shapeHash ^ 0x77;
            OrganWorld.fit(o, bp);
            helper.assertTrue(o.placed == n && o.withered == 0, "枯れた器官の形が変わっても枯らし直さない: placed " + o.placed + "・withered " + o.withered);
            // 11-18 より前の保存(指紋 0): 数が合えば続きから、違えば置き直す
            o.state = ForestOrgans.State.ALIVE;
            o.shapeHash = 0;
            o.total = n;
            o.placed = Math.min(5, n);
            OrganWorld.fit(o, bp);
            helper.assertTrue(o.placed == Math.min(5, n), "古い保存で数が合っているのに置き直した");
            o.shapeHash = 0;
            o.total = n + 1;
            o.placed = Math.min(5, n);
            OrganWorld.fit(o, bp);
            helper.assertTrue(o.placed == 0, "古い保存で数が違うのに続きから置いた");
            // 保存の往復で指紋・守りの UUID が残る
            UUID g = UUID.randomUUID();
            o.guards.add(g);
            ForestOrgans.Organ r = ForestOrgans.Organ.load(o.save());
            helper.assertTrue(r != null && r.shapeHash == o.shapeHash && r.guards.contains(g), "保存の往復で指紋か守りが消えた");
            helper.succeed();
        } finally {
            data.remove(o.id);
            OrganWorld.invalidateCaches();
            ForestDirector.get(level).setBudget(level, budget);
        }
    }

    /** ②4: 守りは UUID で数える。読み込みの外の守り(見つからない)は数えたまま、死んだ守りは外れる、古い保存はまわりの守りを引き取る。 */
    @GameTest(template = "gametest_empty7", batch = "review2_organ_guards", timeoutTicks = 100)
    public static void guardsCountedByUuid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        double budget = ForestDirector.get(level).available(level);
        ForestOrgans.Organ o = data.plant(level, OrganKind.SAC, true, 0, 99, 0, 9_300_000, 0);
        List<Mob> made = new ArrayList<>();
        try {
            BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
            // 遠くへ連れ出された守り(この世界に見つからない)は数えたまま
            o.guards.add(UUID.randomUUID());
            helper.assertTrue(OrganRoles.trackedGuards(level, o, at) == 1, "見つからない守りを数えから外した(連れ出すと増える)");
            // 死んだ守りは外れる
            Mob m = (Mob) gregaria().create(level);
            m.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
            m.setNoAi(true);
            m.addTag(OrganWorld.GUARD_TAG);
            level.addFreshEntity(m);
            made.add(m);
            o.guards.add(m.getUUID());
            helper.assertTrue(OrganRoles.trackedGuards(level, o, at) == 2, "生きている守りを数えない");
            m.discard();
            helper.assertTrue(!o.guards.contains(m.getUUID()), "消えた守りが数えから外れない");
            // 古い保存(UUID が空): まわりの守りの印の Mob を引き取る
            o.guards.clear();
            Mob old = (Mob) gregaria().create(level);
            old.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 1.5D);
            old.setNoAi(true);
            old.addTag(OrganWorld.GUARD_TAG);
            level.addFreshEntity(old);
            made.add(old);
            helper.assertTrue(OrganRoles.trackedGuards(level, o, at) >= 1 && o.guards.contains(old.getUUID()), "古い保存のまわりの守りを引き取らない");
            helper.succeed();
        } finally {
            for (Mob m : made) m.discard();
            data.remove(o.id);
            OrganWorld.invalidateCaches();
            ForestDirector.get(level).setBudget(level, budget);
        }
    }

    /**
     * ②2: 数え直しの印が立った後に植物側が 1 体湧いても、前からいる Mob に新しい値が掛かる。
     * ②3: 根の分だけ削られた個体を保存して読んでも、体力が素の最大で頭打ちにならない(満タンに戻らない)。
     */
    @GameTest(template = "gametest_empty7", batch = "review2_ley_buffs", timeoutTicks = 100)
    public static void leyBuffsReachEveryoneAndSurviveReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForestOrgans data = ForestOrgans.get(level);
        double budget = ForestDirector.get(level).available(level);
        List<Integer> planted = new ArrayList<>();
        List<Mob> made = new ArrayList<>();
        try {
            // ②3(保存の往復だけで見る。世界の根に頼らない)
            double[] t = new double[LeyBuffs.Stat.values().length];
            t[LeyBuffs.Stat.HEALTH.ordinal()] = 50.0D;
            Mob a = (Mob) gregaria().create(level);
            double base = a.getAttributeBaseValue(Attributes.MAX_HEALTH);
            LeyBuffs.apply(a, t);
            helper.assertTrue(Math.abs(a.getMaxHealth() - base * 1.5D) < 1.0E-3D, "根の体力が掛からない: " + a.getMaxHealth());
            float hurt = (float) (base * 1.25D);
            a.setHealth(hurt);
            CompoundTag tag = a.saveWithoutId(new CompoundTag());
            Mob b = (Mob) gregaria().create(level);
            b.load(tag);
            helper.assertTrue(Math.abs(b.getHealth() - hurt) < 1.0E-3D, "読み直すと体力が変わった: " + b.getHealth() + "(保存 " + hurt + ")");
            // ②2: 生きている根を足し、数え直しの印の後に 1 体湧いてから掛け直す
            BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
            Mob old = (Mob) gregaria().create(level);
            old.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
            old.setNoAi(true);
            level.addFreshEntity(old);
            made.add(old);
            OrganWorld.invalidateCaches();
            LeyBuffs.changed(level.getServer().overworld());
            double[] before = LeyBuffs.totals(level).clone();
            ForestOrgans.Organ root = data.plant(level, OrganKind.LEY, false, 0, 31337, 0, 9_400_000, 0);
            planted.add(root.id);
            root.state = ForestOrgans.State.ALIVE;
            LeyBuffs.Stat s = LeyBuffs.Stat.values()[LeyBuffs.statIndex(root)];
            OrganWorld.invalidateCaches(); // 数え直しの印(次の tick に changed)
            Mob fresh = (Mob) gregaria().create(level);
            fresh.moveTo(at.getX() + 1.5D, at.getY(), at.getZ() + 0.5D);
            fresh.setNoAi(true);
            level.addFreshEntity(fresh); // ここで新しい値が数えられる
            made.add(fresh);
            LeyBuffs.changed(level.getServer().overworld());
            double[] after = LeyBuffs.totals(level);
            helper.assertTrue(after[s.ordinal()] > before[s.ordinal()], "根を足しても上げ幅が増えない");
            var inst = old.getAttribute(s.attribute);
            boolean has = inst == null || com.yayuy.overgrownvegetation.port.entity.OvModifiers.has(inst, s.id)
                    && Math.abs(com.yayuy.overgrownvegetation.port.entity.OvModifiers.get(inst, s.id).getAmount() - (s.percent ? after[s.ordinal()] / 100.0D : after[s.ordinal()])) < 1.0E-9D;
            helper.assertTrue(has, "前からいる Mob に新しい根の " + s.label + " が掛からない");
            helper.succeed();
        } finally {
            for (Mob m : made) m.discard();
            for (int id : planted) data.remove(id);
            OrganWorld.invalidateCaches();
            LeyBuffs.changed(level.getServer().overworld());
            ForestDirector.get(level).setBudget(level, budget);
        }
    }
}
