package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStat;
import com.yayuy.overgrownvegetation.director.DirectorGates;
import com.yayuy.overgrownvegetation.director.ForestDirector;
import com.yayuy.overgrownvegetation.phase.MobClassTable;
import com.yayuy.overgrownvegetation.phase.MobClassTable.Lineage;
import com.yayuy.overgrownvegetation.phase.MobClassTable.Role;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;

/**
 * バランス調整(2026-11-18 夜 ユーザー決定)の分類・出るフェーズ・数値・フェーズの長さが、決定の表のとおりかを確かめる。
 * 正本は Vault「構想と進捗/バランス調整の下ごしらえ(Mob の危険度と型)(11-18)」の【決定】の節と「フェーズの仕組み(ポイント・日数)」の末尾。
 * 数値は【仮】なので、決まりを変えたらこのテストも一緒に直す。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class MobClassGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(MobClassGameTests.class);
        }
    }

    private MobClassGameTests() {
    }

    private static EntityType<?> type(String id) {
        return BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, id));
    }

    private static void check(GameTestHelper h, boolean ok, String what) {
        if (!ok) h.fail(what);
    }

    /** 表の 76 体(11-20 漂流する擬態 Mob を足した): 系統のタグと json が食い違わない・group_1〜6 の全員が表にいる・環境生物は型も危険度も無い・ほかは型 1〜2 と危険度 1〜12。 */
    @GameTest(template = "gametest_empty7", batch = "mob_class_pure", timeoutTicks = 20)
    public static void classTableMatchesTagsAndRules(GameTestHelper h) {
        MobClassTable t = DesignData.mobClass();
        check(h, t.mobs.size() == 76, "mob_class.json の数が 76 でない: " + t.mobs.size());
        Map<Lineage, TagKey<EntityType<?>>> tags = Map.of(Lineage.PARASITE, ModTags.LINEAGE_PARASITE, Lineage.KIN, ModTags.LINEAGE_KIN,
                Lineage.PLANT, ModTags.LINEAGE_PLANT, Lineage.CRITTER, ModTags.LINEAGE_CRITTER);
        for (Map.Entry<String, MobClassTable.Entry> e : t.mobs.entrySet()) {
            String id = e.getKey();
            MobClassTable.Entry c = e.getValue();
            EntityType<?> ty = type(id);
            check(h, BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, id)), "登録の無い id: " + id);
            Lineage l = c.lineage();
            check(h, l != null, "系統が読めない: " + id);
            for (Map.Entry<Lineage, TagKey<EntityType<?>>> tg : tags.entrySet()) {
                check(h, ty.is(tg.getValue()) == (tg.getKey() == l), id + " の系統タグが json(" + l + ")と違う: " + tg.getValue().location());
            }
            List<Role> roles = c.roleList();
            if (l == Lineage.CRITTER) {
                check(h, roles.isEmpty() && c.danger == 0, "環境生物に型か危険度がある: " + id);
            } else {
                check(h, roles.size() >= 1 && roles.size() <= 2 && roles.size() == c.roles.size(), "型が 1〜2 でない・読めない: " + id);
                check(h, c.danger >= 1 && c.danger <= MobClassTable.CALAMITY, "危険度が 1〜12 でない: " + id);
            }
            check(h, c.phase >= 1 && c.phase <= 10, "出るフェーズが 1〜10 でない: " + id);
        }
        for (MobGroup g : MobGroup.values()) {
            for (EntityType<?> ty : BuiltInRegistries.ENTITY_TYPE) {
                if (ty.is(g.tag())) check(h, t.get(ty) != null, g.tag().location().getPath() + " の " + BuiltInRegistries.ENTITY_TYPE.getKey(ty) + " が mob_class.json に無い");
            }
        }
        h.succeed();
    }

    /** 決定の表の分類(書き方「○○型-危険度 N-○○種」)と出るフェーズ。 */
    @GameTest(template = "gametest_empty7", batch = "mob_class_pure", timeoutTicks = 20)
    public static void decidedClassesAndPhases(GameTestHelper h) {
        Object[][] rows = {
                // id, 書き方, 出るフェーズ
                {"falcimnes", "近接型-危険度 5-眷属種", 5}, {"venamnes", "近接型-危険度 5-眷属種", 5}, {"clipeimnes", "近接型-危険度 5-眷属種", 5},
                {"acumnes", "遠距離型-危険度 5-眷属種", 5}, {"arbora", "近接型-危険度 2-眷属種", 3}, {"gregaria", "近接型-危険度 2-眷属種", 3},
                {"strobila", "遠距離型-危険度 3-眷属種", 4}, {"vesicaria", "近接型-危険度 3-眷属種", 4}, {"saltimnes", "近接型-危険度 3-眷属種", 5},
                {"pilimnes", "遠距離型-危険度 5-眷属種", 5}, {"gestatrix", "支援型-危険度 5-眷属種", 4}, {"velophyll", "遠距離型-危険度 4-眷属種", 4},
                {"seminaria", "偵察型/拡散型-危険度 1-眷属種", 3}, {"pedicula", "偵察型/拡散型-危険度 1-植物種", 3},
                {"blight_fern", "拡散型-危険度 1-植物種", 1}, {"phyllosens", "偵察型-危険度 5-植物種", 4},
                {"parasitized_zombie", "近接型-危険度 3-寄生種", 1}, {"parasitized_creeper", "拡散型-危険度 1-寄生種", 1},
                {"parasitized_enderman", "近接型-危険度 4-寄生種", 1}, {"parasitized_iron_golem", "近接型-危険度 4-寄生種", 1},
                {"parasitized_warden", "近接型-危険度 10-寄生種", 1},
                {"developed_parasitized_spider", "近接型/妨害型-危険度 4-寄生種", 3}, {"developed_parasitized_enderman", "近接型-危険度 6-寄生種", 3},
                {"developed_parasitized_baby_zombie", "妨害型-危険度 3-寄生種", 3}, {"developed_parasitized_skeleton", "遠距離型-危険度 4-寄生種", 3},
                {"rooted_parasitized_zombie", "妨害型/近接型-危険度 3-寄生種", 2}, {"rooted_parasitized_villager", "拡散型-危険度 3-寄生種", 2},
                {"rooted_parasitized_enderman", "近接型-危険度 5-寄生種", 2}, {"nursery_parasitized_cow", "支援型-危険度 1-寄生種", 2},
                {"root_guardian", "近接型-危険度 4-寄生種", 2}, {"meganeura", "環境生物", 1},
        };
        MobClassTable t = DesignData.mobClass();
        for (Object[] r : rows) {
            MobClassTable.Entry e = t.get((String) r[0]);
            check(h, e != null, "表に無い: " + r[0]);
            check(h, e.label().equals(r[1]), r[0] + " の分類が " + e.label() + "(決定は " + r[1] + ")");
            check(h, e.phase == (Integer) r[2], r[0] + " の出るフェーズが " + e.phase + "(決定は F" + r[2] + ")");
        }
        check(h, !t.natural("parasitized_iron_golem") && !t.natural("parasitized_warden"), "寄生アイアンゴーレム・ウォーデンが自然に湧く");
        check(h, t.natural("parasitized_zombie") && t.natural("falcimnes"), "ふつうの種が自然に湧かない");
        // G1 は F2 から苗床・根付き、F3 から第一進化(director.json の gates)
        var g = DesignData.director().gates;
        check(h, g.nursery == 2 && g.rooted == 2 && g.evolve == 3, "G1 の門が 苗床 2・根付き 2・進化 3 でない: " + g.nursery + "/" + g.rooted + "/" + g.evolve);
        check(h, g.watcher == 4, "監視塔(フィロセンス)の門が 4 でない: " + g.watcher);
        h.succeed();
    }

    /** 出るフェーズで司令塔の口が開く(危険度では縛らない)。ゴーレムとウォーデンはどのフェーズでも開かない。 */
    @GameTest(template = "gametest_empty7", batch = "mob_class_phase", timeoutTicks = 20)
    public static void speciesOpenAtTheirPhase(GameTestHelper h) {
        try {
            DirectorGates.setPhaseForTests(3);
            check(h, DirectorGates.speciesOpen(h.getLevel(), type("gregaria")), "F3 でグレガリアが開いていない");
            check(h, DirectorGates.speciesOpen(h.getLevel(), type("arbora")), "F3 でアルボラが開いていない");
            check(h, !DirectorGates.speciesOpen(h.getLevel(), type("strobila")), "F3 でストロビラが開いている");
            DirectorGates.setPhaseForTests(4);
            check(h, DirectorGates.speciesOpen(h.getLevel(), type("strobila")) && DirectorGates.speciesOpen(h.getLevel(), type("vesicaria")), "F4 でストロビラ・ヴェシカリアが開いていない");
            check(h, DirectorGates.speciesOpen(h.getLevel(), type("gestatrix")), "F4 でゲスタトリクスが開いていない");
            check(h, !DirectorGates.speciesOpen(h.getLevel(), type("falcimnes")), "F4 でファルキムネスが開いている");
            DirectorGates.setPhaseForTests(5);
            for (String id : List.of("falcimnes", "venamnes", "clipeimnes", "acumnes", "saltimnes", "pilimnes")) {
                check(h, DirectorGates.speciesOpen(h.getLevel(), type(id)), "F5 で " + id + " が開いていない");
            }
            DirectorGates.setPhaseForTests(10);
            check(h, !DirectorGates.speciesOpen(h.getLevel(), type("parasitized_iron_golem")), "寄生アイアンゴーレムが森から湧かせられる");
            check(h, !DirectorGates.speciesOpen(h.getLevel(), type("parasitized_warden")), "寄生ウォーデンが森から湧かせられる");
        } finally {
            DirectorGates.setPhaseForTests(null);
        }
        h.succeed();
    }

    /** 森の値段 = base × step^(危険度 − 1) × 型の倍率(複合型は高い方)。 */
    @GameTest(template = "gametest_empty7", batch = "mob_class_pure", timeoutTicks = 20)
    public static void forestPriceFromDangerAndRole(GameTestHelper h) {
        MobClassTable t = DesignData.mobClass();
        double base = t.price.base, step = t.price.step;
        check(h, near(ForestDirector.spawnCost(type("falcimnes")), base * Math.pow(step, 4) * t.price.roleFactor(Role.MELEE)), "ファルキムネスの値段");
        check(h, near(ForestDirector.spawnCost(type("pilimnes")), base * Math.pow(step, 4) * t.price.roleFactor(Role.RANGED)), "ピリムネスの値段");
        check(h, near(ForestDirector.spawnCost(type("gestatrix")), base * Math.pow(step, 4) * t.price.roleFactor(Role.SUPPORT)), "ゲスタトリクスの値段");
        check(h, near(ForestDirector.spawnCost(type("gregaria")), base * step * t.price.roleFactor(Role.MELEE)), "グレガリアの値段");
        double semi = base * Math.max(t.price.roleFactor(Role.SCOUT), t.price.roleFactor(Role.SPREAD));
        check(h, near(ForestDirector.spawnCost(type("seminaria")), semi), "セミナリア(複合型は高い方)の値段");
        check(h, ForestDirector.spawnCost(type("falcimnes")) > ForestDirector.spawnCost(type("strobila"))
                && ForestDirector.spawnCost(type("strobila")) > ForestDirector.spawnCost(type("arbora")), "危険度が上ほど高い、になっていない");
        h.succeed();
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1.0E-6D;
    }

    /** 決定の表の数値(ユーザー指定)。mobs.json と Java の既定が同じ値か(design から読んだ値で見る)。 */
    @GameTest(template = "gametest_empty7", batch = "mob_class_pure", timeoutTicks = 20)
    public static void decidedStats(GameTestHelper h) {
        Object[][] rows = {
                // id, 体力, 攻撃, 防具, 靭性(null = 見ない)
                {"falcimnes", 50.0, 25.0, null, null},
                {"venamnes", 45.0, 20.0, null, null},
                {"clipeimnes", 80.0, 14.0, 20.0, 8.0},
                {"acumnes", 40.0, 12.0, 8.0, null},
                {"strobila", 20.0, null, null, null},
                {"saltimnes", 60.0, 12.0, 6.0, null},
                {"pilimnes", null, 16.0, null, null},
                {"velophyll", 14.0, 10.0, null, null},
                {"seminaria", 10.0, null, null, null},
                {"gestatrix", 38.0, null, 8.0, null},
                {"vesicaria", 8.0, null, null, null},
        };
        for (Object[] r : rows) {
            MobStat s = DesignData.mobs().forId((String) r[0]);
            check(h, r[1] == null || eq(s.maxHealth, (Double) r[1]), r[0] + " の体力 " + s.maxHealth);
            check(h, r[2] == null || eq(s.attackDamage, (Double) r[2]), r[0] + " の攻撃 " + s.attackDamage);
            check(h, r[3] == null || eq(s.armor, (Double) r[3]), r[0] + " の防具 " + s.armor);
            check(h, r[4] == null || eq(s.armorToughness, (Double) r[4]), r[0] + " の靭性 " + s.armorToughness);
            // Java の既定(MobStats.DEFAULTS)も同じ値に揃っているか(ゲスタトリクスは既定が無い)
            MobStat d = com.yayuy.overgrownvegetation.data.MobStats.DEFAULTS.get((String) r[0]);
            if (d != null) {
                check(h, r[1] == null || d.maxHealth == null || eq(d.maxHealth, (Double) r[1]), r[0] + " の体力の Java 既定 " + d.maxHealth);
                check(h, r[2] == null || d.attackDamage == null || eq(d.attackDamage, (Double) r[2]), r[0] + " の攻撃の Java 既定 " + d.attackDamage);
            }
        }
        // 技(json の調整値)
        var a = DesignData.actions();
        check(h, a.num("pilimnes", "fan", "fanMax", 0) == 22.0D, "ピリムネスの扇の端が 22 でない");
        check(h, a.num("vesicaria", "burst", "chainRadius", -1) == 0.0D, "ヴェシカリアの連鎖が残っている");
        check(h, a.num("vesicaria", "burst", "damage", 0) == 10.0D, "ヴェシカリアの破裂が 10 でない");
        check(h, a.num("vesicaria", "burst", "chargeStartRange", 0) > 12.0D, "ヴェシカリアの見つける範囲が上がっていない");
        check(h, a.num("strobila", "cone", "damage", 0) == 23.0D && a.num("strobila", "cone", "min", 0) == 8.0D && a.num("strobila", "cone", "max", 0) == 46.0D,
                "ストロビラの球果が 直撃 23・射程 8〜46 でない");
        check(h, a.num("velophyll", "bomb", "damage", 0) == 10.0D, "ヴェロフィルの直撃が 10 でない");
        check(h, a.num("rooted_parasitized_enderman", "reach", "min", 0) == 4.0D && a.num("rooted_parasitized_enderman", "reach", "max", 0) == 48.0D
                && a.num("rooted_parasitized_enderman", "reach", "callRadius", 0) == 48.0D, "根付いたエンダーマンの引き込みが 4〜48・呼ぶ半径 48 でない");
        check(h, a.num("developed_parasitized_enderman", "blink", "min", 0) == 10.0D && a.num("developed_parasitized_enderman", "blink", "max", 0) == 20.0D,
                "1 段目のエンダーマンの転移が 10〜20 でない");
        // 技の固定ダメージは攻撃と同じ比(ファルキ × 25/16・ヴェナム × 2)
        check(h, Math.abs(a.def("falcimnes", "rift").damage() - 14.0D * 25.0D / 16.0D) < 0.1D, "ファルキムネスの地割れが攻撃と同じ比で上がっていない");
        check(h, a.def("venamnes", "pounce").damage() == 24.0D, "ヴェナムネスの飛びかかりが 24 でない");
        check(h, eq(DesignData.mobs().forId("venamnes").movementSpeed, 0.34D), "ヴェナムネスの速さが 0.34 でない");
        double huntGround = 2.16D * Math.pow(0.34D * a.def("venamnes", "hunt").extra().get("speedMult"), 2);
        check(h, Math.abs(huntGround - 0.35D) < 0.01D, "ヴェナムネスの狩りの疾走が地面 0.35 を保っていない: " + huntGround);
        var gs = DesignData.spawns().gestatrix;
        check(h, gs.restMax < 320 && gs.birthWhenHurt, "ゲスタトリクスの産む速さ・攻撃されたら産むが入っていない");
        h.succeed();
    }

    private static boolean eq(Double a, double b) {
        return a != null && Math.abs(a - b) < 1.0E-6D;
    }

    /** ストロビラの球果が相手に直に当たると、直撃の傷(設計値 23)を付ける(11-18 夜。前は傷なし)。 */
    @GameTest(template = "gametest_empty7", batch = "mob_class_world", timeoutTicks = 60)
    public static void strobilaConeHitDealsDirectDamage(GameTestHelper h) {
        var level = h.getLevel();
        com.yayuy.overgrownvegetation.entity.StrobilaEntity owner = h.spawn(com.yayuy.overgrownvegetation.registry.ModCrawlers.STROBILA.get(), new net.minecraft.world.phys.Vec3(0.5, 2.0, 0.5));
        owner.setNoAi(true);
        net.minecraft.world.entity.animal.Cow cow = h.spawnWithNoFreeWill(EntityType.COW, new net.minecraft.core.BlockPos(4, 2, 4));
        cow.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(100.0D);
        cow.setHealth(100.0F);
        float want = 100.0F - com.yayuy.overgrownvegetation.entity.StrobilaCone.directDamage();
        h.runAfterDelay(2L, () -> {
            com.yayuy.overgrownvegetation.entity.StrobilaCone cone = new com.yayuy.overgrownvegetation.entity.StrobilaCone(level, owner);
            cone.setPos(cow.getX(), cow.getY() + 3.0D, cow.getZ());
            cone.setDeltaMovement(0.0D, -0.6D, 0.0D);
            level.addFreshEntity(cone);
        });
        h.runAfterDelay(12L, () -> {
            check(h, Math.abs(cow.getHealth() - want) < 0.01F, "球果の直撃で体力 " + want + " のはずが " + cow.getHealth());
            h.succeed();
        });
    }

    /** ゲスタトリクスは休んでいる間に傷を受けると、休みを切り上げてすぐ前触れへ(11-18 夜 ユーザー「攻撃されたらクールダウンを無視して産む」)。 */
    @GameTest(template = "gametest_empty7", batch = "mob_class_world", timeoutTicks = 40)
    public static void gestatrixHurtInRestStartsBirth(GameTestHelper h) {
        var level = h.getLevel();
        com.yayuy.overgrownvegetation.entity.GestatrixEntity g = h.spawn(com.yayuy.overgrownvegetation.registry.ModCrawlers.GESTATRIX.get(), new net.minecraft.world.phys.Vec3(3.5, 2.0, 3.5));
        h.runAfterDelay(2L, () -> {
            g.forceStateForTests(com.yayuy.overgrownvegetation.entity.GestatrixEntity.REST, 1000);
            g.hurt(level.damageSources().generic(), 1.0F);
            check(h, g.state() == com.yayuy.overgrownvegetation.entity.GestatrixEntity.WINDUP, "休み中に傷を受けても前触れへ移らない: 状態 " + g.state());
            h.succeed();
        });
    }

    /** フェーズの長さ(11-18 夜 ユーザー決定): 閾値は後ろほど大きいとは限らない(F6 は短い)・段の区切りは 0 < 定着 < 最盛 < 1。 */
    @GameTest(template = "gametest_empty7", batch = "mob_class_pure", timeoutTicks = 20)
    public static void phaseLengthsFollowRoles(GameTestHelper h) {
        var p = DesignData.phases();
        long[] want = {0, 0, 52_000L, 5_300_000L, 40_000_000L, 140_000_000L, 700_000_000L, 380_000_000L}; // 11-22 C-1 で閾値を上げた(ユーザー決定)
        for (int n = 2; n <= 7; n++) check(h, p.pointsToAdvance(n) == want[n], "F" + n + " の閾値 " + p.pointsToAdvance(n) + "(案 " + want[n] + ")");
        check(h, p.pointsToAdvance(7) < p.pointsToAdvance(6), "F6(3〜6 日)が F5→F6 より短くなっていない");
        for (int n = 1; n <= 7; n++) {
            double e = p.stageFrom(n, com.yayuy.overgrownvegetation.phase.PhaseStage.ESTABLISHED);
            double k = p.stageFrom(n, com.yayuy.overgrownvegetation.phase.PhaseStage.PEAK);
            check(h, e > 0.0D && e < k && k < 1.0D, "F" + n + " の段の区切りがおかしい: " + e + " / " + k);
            check(h, e < 1.0D / 3.0D + 1.0E-9D, "F" + n + " の定着の区切りが日数の 1/3 より後ろ(点は加速するので手前のはず): " + e);
        }
        h.succeed();
    }
}
