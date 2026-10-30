package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.skyboss.SkyBossBody;
import com.yayuy.overgrownvegetation.skyboss.SkyBossBrain;
import com.yayuy.overgrownvegetation.skyboss.SkyBossCombat;
import com.yayuy.overgrownvegetation.skyboss.SkyBossData;
import com.yayuy.overgrownvegetation.skyboss.SkyBossDischarge;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFeeding;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFlight;
import com.yayuy.overgrownvegetation.skyboss.SkyBossHang;
import com.yayuy.overgrownvegetation.skyboss.SkyBossParts;
import com.yayuy.overgrownvegetation.skyboss.SkyBossPose;
import com.yayuy.overgrownvegetation.skyboss.SkyBossRecoil;
import com.yayuy.overgrownvegetation.skyboss.SkyBossShapes;
import com.yayuy.overgrownvegetation.skyboss.SkyBossTestHooks;
import com.yayuy.overgrownvegetation.skyboss.SkyBossTuning;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/**
 * 空の巨大生物の当たり判定(2026-10-18 夕方)。形の元が読めること、線分と形の当たりの計算、本物の矢が受け手に当たって体力が減ること、体力 0 で倒れること。
 * ボスはワールドに 1 体なので、ワールドを使うのは 1 本だけ(同じバッチは並列に走る)。
 * 10-18 夜から弱点(避雷針・蕾)を壊す・生え直す・避雷針の爆発の反動(ワールドのボスは使わず、自分の姿勢と壊れ具合で)。
 * 10-19 から雷雲に寄る・背を上へ転がる・留まる・出ていく(素の頭と寄り道を流す)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SkyBossGameTests {

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "sky_boss";
    /**
     * 胴の真ん中より後ろの、腕の無い節(扇・触手から離れている)。真横(脇腹の真ん中の高さ)から撃つので、脇腹の蕾の無い所。
     * 10-19 に脇腹へ小さな蕾を足したら b16 の脇腹(z 56)に蕾が来た → 蕾と蕾の間(z 56 と 88 の間)の b17 へ。
     */
    private static final String PROBE_BONE = "b17";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SkyBossGameTests.class);
        }

        private Registration() {
        }
    }

    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void shapesLoadFromModel(GameTestHelper helper) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null) {
            // 公開のソースだけでビルドしたとき(素材が無い)は当たり判定なしで動く作りなので、ここでは数えない
            OvergrownVegetation.LOGGER.warn("当たり判定の形 {} が無いので SkyBossGameTests を飛ばす(モデルの道具の SkyGlaucusHitShapes export で作る)", SkyBossShapes.RESOURCE);
            helper.succeed();
            return;
        }
        helper.assertTrue(s.bodyCount == 36, "体の骨が 36 本(根の body・頭・b01〜b18・尾 16)でない: " + s.bodyCount);
        helper.assertTrue(s.armCount == 8, "腕が 8 本でない: " + s.armCount);
        helper.assertTrue(s.tentCount == 8, "触手が 8 本でない: " + s.tentCount);
        // 10-19 に小さな蕾を左右 17 個ずつ足した(52 → 86。ユーザー「100 以内なら幾つでも」)。弱点の枠 SkyBossParts.MAX_BUDS に収まること
        helper.assertTrue(s.budCount == 86, "蕾が 86 個でない: " + s.budCount);
        helper.assertTrue(s.budCount <= com.yayuy.overgrownvegetation.skyboss.SkyBossParts.MAX_BUDS, "蕾が弱点の枠を越えた");
        for (String a : com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock.NAMES) {
            helper.assertTrue(s.fanAnims.containsKey(a), "扇のアニメ " + a + " が無い");
        }
        // 版 4(10-19 の技): 体と触手の技のアニメ
        for (String a : new String[]{"spit_body", "stretch_body", "breach_body"}) {
            helper.assertTrue(s.bodyAnims.containsKey(a), "体の動きのアニメ " + a + " が無い(形を書き出し直す)");
        }
        for (String a : new String[]{"tentacle_lash", "tentacle_grab"}) {
            helper.assertTrue(s.tentAnims.containsKey(a), "触手の技のアニメ " + a + " が無い");
            helper.assertTrue(s.tentAnims.get(a).pts[0].length == s.tentCount, "触手の技のアニメ " + a + " の触手の数が違う");
        }
        helper.succeed();
    }

    /** まっすぐ飛ぶボスの形: 節の横から撃った線分は表面(半径の所)で当たり、ずっと上を通る線分は当たらず、頭の前から避雷針の高さを通る線分は避雷針に当たる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void raysHitBodySurfaceRodAndMissAbove(GameTestHelper helper) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null) {
            helper.succeed();
            return;
        }
        SkyBossFlight f = new SkyBossFlight();
        f.x = 1000.0;
        f.y = 900.0;
        f.z = -500.0;
        f.yaw = 30.0F;
        f.straightTrail(SkyBossTuning.TRAIL_KEEP);
        SkyBossPose pose = new SkyBossPose();
        pose.compute(f, f.x, f.y, f.z, 0.0F, 0.0, true);
        int ci0 = clearSliceIndex(s, pose, 1.0);
        helper.assertTrue(ci0 >= 0, "後ろの胴に、真横から撃って表面に当たる輪切りが無い: " + CLEAR_WHY);
        Slice sl = slice(pose, ci0);
        SkyBossPose.Hit hit = new SkyBossPose.Hit();
        double reach = sl.rx + 40.0;
        boolean side = pose.raycast(sl.cx + sl.ex[0] * reach, sl.cy + sl.ex[1] * reach, sl.cz + sl.ex[2] * reach,
                sl.cx - sl.ex[0] * reach, sl.cy - sl.ex[1] * reach, sl.cz - sl.ex[2] * reach, hit);
        helper.assertTrue(side, "節の横から撃って当たらない");
        double d = Math.sqrt(sq(hit.x - sl.cx) + sq(hit.y - sl.cy) + sq(hit.z - sl.cz));
        helper.assertTrue(Math.abs(d - sl.rx) < 1.0, "当たった所が表面でない: 中心から " + d + "(半径 " + sl.rx + ")");
        helper.assertTrue(hit.part == SkyBossPose.PART_MOSS, "胴が苔でない: " + hit.part);
        boolean above = pose.raycast(sl.cx - 3000.0, sl.cy + 2000.0, sl.cz, sl.cx + 3000.0, sl.cy + 2000.0, sl.cz, hit);
        helper.assertTrue(!above, "ずっと上を通る線分が当たった: (" + hit.x + ", " + hit.y + ", " + hit.z + ")");
        // 避雷針の真ん中の高さで、頭のずっと前から体の向きに沿って撃つ
        int mid = s.rodPts.length / 2;
        int ci = pose.capCount - (s.rodPts.length - 1) + mid - 1;
        double rx = (pose.cA[ci * 3] + pose.cB[ci * 3]) * 0.5;
        double ry = (pose.cA[ci * 3 + 1] + pose.cB[ci * 3 + 1]) * 0.5;
        double rz = (pose.cA[ci * 3 + 2] + pose.cB[ci * 3 + 2]) * 0.5;
        double[] fw = f.forward();
        boolean rod = pose.raycast(rx + fw[0] * 1500.0, ry + fw[1] * 1500.0, rz + fw[2] * 1500.0, rx, ry, rz, hit);
        helper.assertTrue(rod && hit.part == SkyBossPose.PART_ROD,
                "頭の前から避雷針の高さを撃って避雷針に当たらない: " + (rod ? "部位 " + hit.part : "当たり無し"));
        SkyBossPose.Contact c = new SkyBossPose.Contact();
        helper.assertTrue(pose.contact(sl.cx, sl.cy, sl.cz, 0.6, c), "胴の中の点がめり込んでいない");
        helper.assertTrue(!pose.contact(sl.cx, sl.cy + 2000.0, sl.cz, 0.6, c), "ずっと上の点がめり込んでいる");
        helper.succeed();
    }

    /** 本物の矢を胴の横へ撃つ: 受け手に当たって体力が減り(苔なので 3 割)、矢は吸われて消える。そのあと体力 0 で倒れる(消える)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void arrowHurtsBossThroughProxyAndBossDies(GameTestHelper helper) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null) {
            helper.succeed();
            return;
        }
        ServerLevel level = helper.getLevel();
        SkyBossData data = SkyBossData.get(level);
        Vec3 start = helper.absoluteVec(new Vec3(1.5, 3.5, 3.5));
        // 胴(b16)の横の表面が矢の 2.5 ブロック先に来るように置く(2 回: 置いて測ってずらす)
        double hx = start.x + 1200.0;
        double hy = start.y;
        double hz = start.z + 1150.0;
        SkyBossPose pose = new SkyBossPose();
        Slice sl = null;
        for (int i = 0; i < 2; i++) {
            data.placeForTest(level, hx, hy, hz, 0.0F);
            SkyBossFlight f = data.flight();
            pose.compute(f, f.x, f.y, f.z, f.bank, f.tick, true);
            Slice pr = slice(s, pose, PROBE_BONE);
            // 矢の側(−x)の向き。撃つ所は、その側の脇腹に蕾の無い輪切り
            double sign = pr.ex[0] > 0.0 ? -1.0 : 1.0;
            int ci = clearSliceIndex(s, pose, sign);
            sl = ci >= 0 ? slice(pose, ci) : pr;
            double gap = sl.rx + 2.5;
            double sx = sl.cx + sl.ex[0] * sign * gap;
            double sy = sl.cy + sl.ex[1] * sign * gap;
            double sz = sl.cz + sl.ex[2] * sign * gap;
            hx += start.x - sx;
            hy += start.y - sy;
            hz += start.z - sz;
        }
        SkyBossTestHooks.forcePose = true;
        Arrow arrow = new Arrow(EntityType.ARROW, level);
        arrow.setPos(start.x, start.y, start.z);
        arrow.setDeltaMovement(1.5, 0.0, 0.0);
        level.addFreshEntity(arrow);
        float before = data.health();
        final Slice probe = sl;
        helper.runAfterDelay(8L, () -> {
            try {
                float after = data.health();
                helper.assertTrue(after < before, "矢が当たって体力が減らない(" + before + " → " + after + "、表面まで "
                        + String.format("%.1f", probe.rx) + "、矢 " + arrow.position() + " 消えた " + arrow.isRemoved() + ")");
                helper.assertTrue(after > before - 10.0F, "減り方が多すぎる: " + (before - after));
                helper.assertTrue(arrow.isRemoved(), "矢が吸われずに残っている: " + arrow.position());
                // 弱点への本当のダメージ(受け手 → hurt → 弱点の体力): 蕾 0 に体力を越える分を当てると壊れ、ボスの体力も弱点の倍率で減る(10-18 夜)
                float hpBefore = data.health();
                float dmg = SkyBossTuning.BUD_HEALTH / SkyBossTuning.WEAK_DAMAGE + 1.0F;
                data.hurt(level, level.damageSources().generic(), dmg, SkyBossPose.PART_BUD, 0, start.x, start.y, start.z);
                helper.assertTrue(!data.parts().budSolid(0), "蕾に体力を越えるダメージを当てても壊れない");
                helper.assertTrue(Math.abs(hpBefore - data.health() - dmg * SkyBossTuning.WEAK_DAMAGE) < 0.05F,
                        "弱点のダメージがボスの体力に倍率どおり入らない: " + (hpBefore - data.health()));
                data.hurt(level, level.damageSources().generic(), SkyBossTuning.MAX_HEALTH * 10.0F, SkyBossPose.PART_ROD, -1,
                        start.x, start.y, start.z);
                helper.assertTrue(!data.isActive(), "体力 0 で倒れない: " + data.health());
                helper.succeed();
            } finally {
                SkyBossTestHooks.forcePose = false;
                if (data.isActive()) {
                    data.remove(level);
                }
            }
        });
    }

    // ------------------------------------------------------------------ 弱点(10-18 夜)。ワールドのボスは使わない(自分の姿勢と壊れ具合で)
    /** 外から蕾に当たる線分は、蕾が壊れると蕾に当たらなくなる(奥の苔へ通る)。戦っていない間が続くと生え直し、育ちきると元どおり。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void budBreaksLetsRaysThroughAndRegrows(GameTestHelper helper) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null) {
            helper.succeed();
            return;
        }
        SkyBossFlight f = straightFlight();
        SkyBossParts parts = new SkyBossParts();
        SkyBossPose pose = new SkyBossPose();
        pose.parts = parts;
        pose.compute(f, f.x, f.y, f.z, 0.0F, 0.0, true);
        SkyBossPose.Hit hit = new SkyBossPose.Hit();
        // 外から蕾の中心へ撃って、その蕾に当たる蕾を探す(扇・触手の陰になる蕾は飛ばす)
        int found = -1;
        double[] ray = null;
        for (int b = 0; b < pose.sphCount && found < 0; b++) {
            double cx = pose.spC[b * 3];
            double cy = pose.spC[b * 3 + 1];
            double cz = pose.spC[b * 3 + 2];
            double[] n = outward(pose, cx, cy, cz);
            double len = pose.spR[b] * 3.0 + 30.0;
            double[] r = {cx + n[0] * len, cy + n[1] * len, cz + n[2] * len, cx, cy, cz};
            if (pose.raycast(r[0], r[1], r[2], r[3], r[4], r[5], hit) && hit.part == SkyBossPose.PART_BUD && hit.index == b) {
                found = b;
                ray = r;
            }
        }
        helper.assertTrue(found >= 0, "外から当たる蕾が無い");
        helper.assertTrue(!parts.damage(SkyBossPose.PART_BUD, found, SkyBossTuning.BUD_HEALTH * 0.5F, 0L), "体力の半分で壊れた");
        helper.assertTrue(parts.damage(SkyBossPose.PART_BUD, found, SkyBossTuning.BUD_HEALTH * 0.6F, 0L), "体力を越えても壊れない");
        helper.assertTrue(!parts.damage(SkyBossPose.PART_BUD, found, 1.0F, 1L), "壊れた蕾がもう一度壊れた");
        pose.compute(f, f.x, f.y, f.z, 0.0F, 0.0, true);
        boolean again = pose.raycast(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5], hit);
        helper.assertTrue(!(again && hit.part == SkyBossPose.PART_BUD && hit.index == found), "壊れた蕾にまだ当たる");
        helper.assertTrue(parts.budScale(found, 10.0) == 0.0F, "壊れた蕾が見えている");
        // 生え直し: 戦いが終わって BUD_REGROW_DELAY たつまでは始まらない・戦っている間も始まらない
        long quiet = 500L;
        helper.assertTrue(!parts.tick(quiet + SkyBossTuning.BUD_REGROW_DELAY - 1, false, quiet, 7L), "生え直しが早すぎる");
        helper.assertTrue(!parts.tick(quiet + SkyBossTuning.BUD_REGROW_DELAY + 10, true, quiet, 7L), "戦っている間に生え直し始めた");
        long now = quiet + SkyBossTuning.BUD_REGROW_DELAY + 20;
        helper.assertTrue(parts.tick(now, false, quiet, 7L), "生え直し始めない");
        long r = parts.budRegrowAt[found];
        helper.assertTrue(r >= now && r <= now + SkyBossTuning.BUD_REGROW_STAGGER, "生え直しのずらしが範囲の外: " + (r - now));
        float mid = parts.budScale(found, r + SkyBossTuning.BUD_REGROW_TICKS * 0.5);
        helper.assertTrue(mid > 0.0F && mid < 1.0F, "育つ途中の大きさが違う: " + mid);
        helper.assertTrue(!parts.budSolid(found), "育つ途中で当たり判定に戻った");
        helper.assertTrue(parts.tick(r + SkyBossTuning.BUD_REGROW_TICKS, false, quiet, 7L) && parts.budSolid(found)
                && parts.budHealth[found] == SkyBossTuning.BUD_HEALTH, "育ちきっても元どおりにならない");
        helper.succeed();
    }

    /**
     * 避雷針を壊すと、爆発の反動で胴の真ん中(b06)が押し込まれ(腹が上なので世界の上へ)、収まると元の形。避雷針は形から外れ、
     * 戦っていない間が続くと生え直して元どおり。壊れ具合は保存から戻る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void rodBreakRecoilsBodyAndRegrows(GameTestHelper helper) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null) {
            helper.succeed();
            return;
        }
        SkyBossFlight f = straightFlight();
        SkyBossParts parts = new SkyBossParts();
        SkyBossPose plain = new SkyBossPose();
        SkyBossPose pose = new SkyBossPose();
        pose.parts = parts;
        helper.assertTrue(!parts.damage(SkyBossPose.PART_ROD, -1, SkyBossTuning.ROD_HEALTH * 0.5F, 100L), "体力の半分で壊れた");
        helper.assertTrue(parts.damage(SkyBossPose.PART_ROD, -1, SkyBossTuning.ROD_HEALTH * 0.6F, 100L) && !parts.rodSolid(), "体力を越えても壊れない");
        // 押し込みがいちばん深い時刻(爆発 + 0.22 元の秒)
        double peak = parts.rodShortStart() + (SkyBossTuning.ROD_BOOM + 0.22) * SkyBossTuning.ANIM_SLOW * 20.0;
        plain.compute(f, f.x, f.y, f.z, 0.0F, peak, true);
        pose.compute(f, f.x, f.y, f.z, 0.0F, peak, true);
        Slice a = slice(s, plain, "b06");
        Slice b = slice(s, pose, "b06");
        double d = Math.sqrt(sq(a.cx - b.cx) + sq(a.cy - b.cy) + sq(a.cz - b.cz));
        helper.assertTrue(d > 40.0 && d < 130.0, "反動の押し込みの深さが違う: " + String.format("%.1f", d) + " ブロック");
        helper.assertTrue(b.cy > a.cy + 20.0, "押し込みの向きが違う(腹が上なら避雷針から離れる世界の上へ): y " + a.cy + " → " + b.cy);
        // 反動の前(補間の間)と、収まった後は元の形
        for (double t : new double[]{parts.rodShortStart() - 1.0, parts.rodShortStart() + (SkyBossRecoil.END + 0.1) * SkyBossTuning.ANIM_SLOW * 20.0}) {
            plain.compute(f, f.x, f.y, f.z, 0.0F, t, true);
            pose.compute(f, f.x, f.y, f.z, 0.0F, t, true);
            Slice p0 = slice(s, plain, "b06");
            Slice p1 = slice(s, pose, "b06");
            double e = Math.sqrt(sq(p0.cx - p1.cx) + sq(p0.cy - p1.cy) + sq(p0.cz - p1.cz));
            helper.assertTrue(e < 0.01, "反動の外なのに形が違う(t " + t + "): " + e);
        }
        // 避雷針は形から外れる: 頭の前から避雷針の高さを撃っても避雷針に当たらない
        int mid = s.rodPts.length / 2;
        int ci = plain.rodFirstCapsule() + mid - 1;
        double rx = (plain.cA[ci * 3] + plain.cB[ci * 3]) * 0.5;
        double ry = (plain.cA[ci * 3 + 1] + plain.cB[ci * 3 + 1]) * 0.5;
        double rz = (plain.cA[ci * 3 + 2] + plain.cB[ci * 3 + 2]) * 0.5;
        double[] fw = f.forward();
        SkyBossPose.Hit hit = new SkyBossPose.Hit();
        boolean before = plain.raycast(rx + fw[0] * 1500.0, ry + fw[1] * 1500.0, rz + fw[2] * 1500.0, rx, ry, rz, hit) && hit.part == SkyBossPose.PART_ROD;
        boolean after = pose.raycast(rx + fw[0] * 1500.0, ry + fw[1] * 1500.0, rz + fw[2] * 1500.0, rx, ry, rz, hit) && hit.part == SkyBossPose.PART_ROD;
        helper.assertTrue(before && !after, "壊れた避雷針が形から外れない(前 " + before + "・後 " + after + ")");
        // 保存から戻る
        SkyBossParts copy = new SkyBossParts();
        copy.load(parts.save());
        helper.assertTrue(copy.rodBrokenAt == parts.rodBrokenAt && copy.rodHealth == parts.rodHealth && !copy.rodSolid(), "保存から壊れ具合が戻らない");
        // 生え直し
        long quiet = 1000L;
        helper.assertTrue(!parts.tick(quiet + SkyBossTuning.ROD_REGROW_DELAY - 1, false, quiet, 1L), "生え直しが早すぎる");
        helper.assertTrue(parts.tick(quiet + SkyBossTuning.ROD_REGROW_DELAY, false, quiet, 1L)
                && parts.rodRegrowAt == quiet + SkyBossTuning.ROD_REGROW_DELAY && !parts.rodSolid(), "生え直し始めない");
        long done = parts.rodRegrowAt + SkyBossTuning.ROD_TRANSITION + SkyBossTuning.ROD_REGROW_TICKS;
        helper.assertTrue(!parts.tick(done - 1, true, quiet, 1L) && !parts.rodSolid(), "生え直しの途中で元どおりになった");
        helper.assertTrue(parts.tick(done, true, quiet, 1L) && parts.rodSolid() && parts.rodHealth == SkyBossTuning.ROD_HEALTH, "生え直しが終わっても元どおりにならない");
        helper.succeed();
    }

    /** 避雷針の層のアニメの長さと、モデルの道具の時間の倍率が、コードの値({@link SkyBossTuning})と合う。アニメが無ければ飛ばす。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void rodAnimationLengthsMatchTuning(GameTestHelper helper) {
        java.io.InputStream in = SkyBossGameTests.class.getResourceAsStream("/assets/overgrown_vegetation/animations/sky_glaucus.animation.json");
        if (in == null) {
            helper.succeed();
            return;
        }
        com.google.gson.JsonObject anims;
        try (java.io.Reader r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            anims = com.google.gson.JsonParser.parseReader(r).getAsJsonObject().getAsJsonObject("animations");
        } catch (Exception e) {
            helper.fail("アニメが読めない: " + e);
            return;
        }
        for (String n : new String[]{"rod_short", "rod_regrow", "undulate"}) {
            helper.assertTrue(anims.has(n), "アニメ " + n + " が無い");
        }
        double regrow = anims.getAsJsonObject("rod_regrow").get("animation_length").getAsDouble();
        helper.assertTrue(Math.abs(regrow * 20.0 - SkyBossTuning.ROD_REGROW_TICKS) < 1.0,
                "rod_regrow の長さ " + regrow + " 秒が ROD_REGROW_TICKS " + SkyBossTuning.ROD_REGROW_TICKS + " と違う");
        // うねりは元 24 秒(SkyGlaucusAnims)× 時間の倍率
        double und = anims.getAsJsonObject("undulate").get("animation_length").getAsDouble();
        helper.assertTrue(Math.abs(und - 24.0 * SkyBossTuning.ANIM_SLOW) < 0.01, "時間の倍率が道具と違う: うねり " + und + " 秒");
        // 10-19: 放電のアニメ(避雷針の溜めと放電・扇)がある・長さが道具の値と同じ
        for (String n : new String[]{"rod_charge", "rod_discharge", "discharge_fans"}) {
            helper.assertTrue(anims.has(n), "アニメ " + n + " が無い");
        }
        double ch = anims.getAsJsonObject("rod_charge").get("animation_length").getAsDouble();
        double dc = anims.getAsJsonObject("rod_discharge").get("animation_length").getAsDouble();
        helper.assertTrue(Math.abs(ch - SkyBossTuning.ROD_CHARGE_SECONDS) < 0.01 && Math.abs(dc - SkyBossTuning.ROD_DISCHARGE_SECONDS) < 0.01,
                "rod_charge " + ch + " 秒・rod_discharge " + dc + " 秒が道具の値と違う");
        helper.succeed();
    }

    // ------------------------------------------------------------------ 雷雲に寄る・背を上にする(10-19)。ワールドのボスは使わない
    /** 転がりは頭から尾へ遅れて伝わり、背が上になると避雷針が上を向く(腹が上では下)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void rollTravelsHeadToTailAndFlipsRod(GameTestHelper helper) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null) {
            helper.succeed();
            return;
        }
        SkyBossFlight f = straightFlight();
        SkyBossPose pose = new SkyBossPose();
        pose.compute(f, f.x, f.y, f.z, 0.0F, 0.0, true);
        int first = pose.rodFirstCapsule();
        int tip = (pose.capCount - 1) * 3;
        double belly = pose.cB[tip + 1] - pose.cA[first * 3 + 1];
        helper.assertTrue(belly < -50.0, "腹が上なのに避雷針が下を向いていない: 先 − 根元 " + belly);
        f.rollFrom = 180.0F;
        f.rollTo = 0.0F;
        f.rollStart = 0L;
        // 頭は転がり終え、尾はまだ途中
        double mid = SkyBossTuning.ROLL_TICKS;
        helper.assertTrue(Math.abs(f.rollAt(mid)) < 1.0E-6, "頭が転がり終えていない: " + f.rollAt(mid));
        helper.assertTrue(f.rolling(mid), "尾まで転がり終える前なのに転がっていないことになっている");
        pose.compute(f, f.x, f.y, f.z, 0.0F, mid, true);
        float headTwist = Math.abs(pose.bends[0][2]) + Math.abs(pose.bends[1][2]);
        float tailTwist = 0.0F;
        for (int k = SkyBossPose.BEND_COUNT - 16; k < SkyBossPose.BEND_COUNT; k++) {
            tailTwist += Math.abs(pose.bends[k][2]);
        }
        helper.assertTrue(tailTwist > 0.05F, "尾が遅れてねじれていない(転がりが伝わっていない): " + tailTwist + "(頭 " + headTwist + ")");
        // 尾まで転がり終えたら、避雷針は上を向き、ねじれは無い
        double end = SkyBossTuning.ROLL_TICKS + SkyBossTuning.ROLL_LAG_TICKS + 1.0;
        helper.assertTrue(!f.rolling(end) && f.backUp(end), "転がり終えても背が上になっていない");
        pose.compute(f, f.x, f.y, f.z, 0.0F, end, true);
        double back = pose.cB[tip + 1] - pose.cA[first * 3 + 1];
        helper.assertTrue(back > 50.0, "背が上なのに避雷針が上を向いていない: 先 − 根元 " + back);
        float twist = 0.0F;
        for (float[] b : pose.bends) {
            twist += Math.abs(b[2]);
        }
        helper.assertTrue(twist < 1.0E-3F, "転がり終えたのにねじれが残る: " + twist);
        helper.succeed();
    }

    /**
     * 雲を 1 つ置いて頭・寄り道を素で流す: 気づいて(頭を先に向けて)寄り、腕の付いた所が雲の真ん中の近くで止まり、背を上へ転がってホバリング、
     * 雷が落ちる時刻が来て、4 分で腹を上へ戻って出ていき、同じ雲には戻らない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void feedingApproachesHoversAndLeaves(GameTestHelper helper) {
        SkyBossFlight f = new SkyBossFlight();
        f.x = 0.0;
        f.y = SkyBossTuning.CRUISE_Y;
        f.z = 0.0;
        f.yaw = 0.0F;
        f.speed = (float) SkyBossTuning.AIRSPEED;
        f.straightTrail(SkyBossTuning.TRAIL_KEEP);
        SkyBossBrain brain = new SkyBossBrain();
        brain.reset();
        SkyBossFeeding feeding = new SkyBossFeeding();
        SkyBossFeeding.Orders orders = new SkyBossFeeding.Orders();
        // 頭の前(+z)から右へずれた所に 3.5 km、胴の半径 1400 の雲
        SkyBossFeeding.Storm storm = new SkyBossFeeding.Storm(42L, -1800.0, 3000.0, 1400.0F, 1.0F);
        java.util.List<SkyBossFeeding.Storm> storms = java.util.List.of(storm);
        long index = 0;
        long approachAt = -1;
        long feedAt = -1;
        long leaveAt = -1;
        long cruiseAt = -1;
        double armAtFeed = -1.0;
        float maxLook = 0.0F;
        boolean struck = false;
        boolean backUp = false;
        double minSpeed = Double.MAX_VALUE;
        double[][] arm = {new double[3]};
        for (long t = 0; t < 30000; t++) {
            if (f.tick >= f.segmentEnd) {
                int before = feeding.mode;
                feeding.segment(f, f.tick, storms, orders);
                brain.next(f, index++, 7L, orders);
                if (orders.eager && f.fanOverride < 0) {
                    f.fanOverride = Math.abs(f.bankTarget) > SkyBossTuning.TURN_ANIM_BANK ? -1 : SkyBossFlight.FAN_EAGER;
                }
                if (feeding.mode != before) {
                    if (feeding.mode == SkyBossFeeding.APPROACH && approachAt < 0) {
                        approachAt = f.tick;
                    } else if (feeding.mode == SkyBossFeeding.FEED && feedAt < 0) {
                        feedAt = f.tick;
                        f.trail.pointsBehind(f.x, f.y, f.z, new double[]{SkyBossBody.ARM_CENTER}, arm);
                        armAtFeed = Math.hypot(arm[0][0] - storm.x(), arm[0][2] - storm.z());
                    } else if (feeding.mode == SkyBossFeeding.LEAVE && leaveAt < 0) {
                        leaveAt = f.tick;
                    } else if (feeding.mode == SkyBossFeeding.CRUISE && leaveAt >= 0 && cruiseAt < 0) {
                        cruiseAt = f.tick;
                    }
                }
            }
            f.step();
            maxLook = Math.max(maxLook, Math.abs(f.look));
            if (feeding.mode == SkyBossFeeding.FEED) {
                minSpeed = Math.min(minSpeed, f.speed);
                backUp |= f.backUp(f.tick);
                if (feeding.strikeDue(f, f.tick)) {
                    struck = true;
                    feeding.struck(f.tick, 40, 0.5);
                }
            }
        }
        helper.assertTrue(approachAt == 0, "雲に気づかない(5 km の中): " + approachAt);
        helper.assertTrue(maxLook > 10.0F, "寄る前に頭を向けていない: " + maxLook);
        helper.assertTrue(feedAt > 0, "雲に着いて留まらない");
        helper.assertTrue(armAtFeed < storm.radius() * 0.7, "腕の付いた所が雲の真ん中から遠い所で留まった: " + Math.round(armAtFeed));
        helper.assertTrue(minSpeed < 0.01, "留まる間に止まらない: " + minSpeed);
        helper.assertTrue(backUp, "留まる間に背が上にならない");
        helper.assertTrue(struck, "背が上で留まる間に雷が落ちる時刻が来ない");
        helper.assertTrue(leaveAt > 0 && Math.abs(leaveAt - feedAt - SkyBossTuning.FEED_TICKS) <= SkyBossTuning.SEGMENT_TICKS,
                "留まる長さが違う: " + (leaveAt - feedAt));
        helper.assertTrue(cruiseAt > 0 && Math.abs(Math.IEEEremainder(f.rollAt(f.tick) - 180.0, 360.0)) < 1.0, "出ていった後に腹が上へ戻っていない: " + f.rollAt(f.tick));
        helper.assertTrue(feeding.mode == SkyBossFeeding.CRUISE, "出ていった後に同じ雲へまた寄った: " + SkyBossFeeding.name(feeding.mode));
        helper.assertTrue(f.speed > SkyBossTuning.AIRSPEED * 0.9, "渡りの速さに戻らない: " + f.speed);
        helper.succeed();
    }

    /** 横・後ろにある雲にも、まわりを回り続けずに寄って留まる(10-19 のテストクライアントで、長い旋回では雲のまわりを回り続けた)。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void feedingReachesStormsAtAnyBearing(GameTestHelper helper) {
        double[][] places = {{-2600.0, 300.0}, {1500.0, -2600.0}, {2900.0, 1600.0}};
        for (double[] at : places) {
            SkyBossFeeding.Storm storm = new SkyBossFeeding.Storm(7L, at[0], at[1], 1380.0F, 1.0F);
            double[] r = runToFeed(storm, 16000);
            helper.assertTrue(r[0] >= 0, "雲 (" + at[0] + ", " + at[1] + ") に 16000 tick で着かない(いちばん近づいたのは腕の付いた所で " + Math.round(r[2]) + ")");
            helper.assertTrue(r[1] < storm.radius() * 0.7, "雲 (" + at[0] + ", " + at[1] + ") の真ん中から遠い所で留まった: " + Math.round(r[1]));
        }
        helper.succeed();
    }

    /** 頭・寄り道を素で流して、留まり始めた tick と、そのときの腕の付いた所と雲の真ん中の距離、いちばん近づいた距離を返す(着かなければ tick は -1)。 */
    private static double[] runToFeed(SkyBossFeeding.Storm storm, int maxTicks) {
        SkyBossFlight f = new SkyBossFlight();
        f.y = SkyBossTuning.CRUISE_Y;
        f.speed = (float) SkyBossTuning.AIRSPEED;
        f.straightTrail(SkyBossTuning.TRAIL_KEEP);
        SkyBossBrain brain = new SkyBossBrain();
        brain.reset();
        SkyBossFeeding feeding = new SkyBossFeeding();
        SkyBossFeeding.Orders orders = new SkyBossFeeding.Orders();
        java.util.List<SkyBossFeeding.Storm> storms = java.util.List.of(storm);
        double[][] arm = {new double[3]};
        double closest = Double.MAX_VALUE;
        long index = 0;
        for (int t = 0; t < maxTicks; t++) {
            if (f.tick >= f.segmentEnd) {
                feeding.segment(f, f.tick, storms, orders);
                brain.next(f, index++, 7L, orders);
                if (feeding.mode == SkyBossFeeding.FEED) {
                    f.trail.pointsBehind(f.x, f.y, f.z, new double[]{SkyBossBody.ARM_CENTER}, arm);
                    return new double[]{f.tick, Math.hypot(arm[0][0] - storm.x(), arm[0][2] - storm.z()), closest};
                }
            }
            f.step();
            if (t % 40 == 0) {
                f.trail.pointsBehind(f.x, f.y, f.z, new double[]{SkyBossBody.ARM_CENTER}, arm);
                closest = Math.min(closest, Math.hypot(arm[0][0] - storm.x(), arm[0][2] - storm.z()));
            }
        }
        return new double[]{-1, -1, closest};
    }

    /** まっすぐ飛ぶボス(ワールドとは別)。 */
    /**
     * 背の垂れ草(10-19 決定「垂らす」): 腹が上なら今の形のまま、背が上なら左右とも外(体の脇)へ寝て垂れ、先が体の輪の中へ入らない。
     * 旋回の傾きでは真下へ振れ、転がりを素で流しても角度は跳ばない(追いつき)。骨の回転は板の向き・蝶番の位置と食い違わない。
     * 体の骨の行列から出す真下の向きは、腹が上で +y・背が上で −y(描画が使う向き)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void hangPlatesDrapeWithGravity(GameTestHelper helper) {
        // 道具の作りどおりの板(輪の半幅 25・背の側の半分の高さ 15 の胴): 背骨の近く(左)・背の脇(右)・向きを 50° 振った板(左)
        double ra = 25.0;
        double rb = 15.0;
        double[] xf = {0.17, -0.56, 0.4};
        double[] yaw = {0.1, -0.3, Math.toRadians(50.0)};
        float[] rot = new float[3];
        float[] pos = new float[3];
        double[] dir = new double[3];
        for (int i = 0; i < xf.length; i++) {
            double px = ra * xf[i];
            double py = SkyBossBody.Y0 + rb * Math.sqrt(1.0 - xf[i] * xf[i]) * 0.9 - 1.2;
            SkyBossHang.Plate p = new SkyBossHang.Plate(px, py, yaw[i]);
            String name = "板 " + i;
            // 腹が上・傾き無し(骨の空間の真下 +y): 今までの形のまま
            double a = SkyBossHang.target(p, 0.0, 1.0, 0.0, 0.0);
            SkyBossHang.pose(p, a, rot, pos);
            float still = Math.abs(rot[0]) + Math.abs(rot[1]) + Math.abs(rot[2]) + Math.abs(pos[0]) + Math.abs(pos[1]) + Math.abs(pos[2]);
            helper.assertTrue(Math.abs(a) < 1.0E-9 && still < 1.0E-5F, name + ": 腹が上なのに形が変わる(角度 " + a + ")");
            // 骨の回転(GeckoLib の Z·Y·X)で回した +y が板の向き、蝶番(付け根から +y へ depth)は動かない
            for (double out : new double[]{0.7, -0.9, p.max()}) {
                SkyBossHang.pose(p, out, rot, pos);
                SkyBossHang.direction(p, out, dir);
                Vector3f yv = new Matrix3f().rotateZ(rot[2]).rotateY(rot[1]).rotateX(rot[0]).transform(new Vector3f(0.0F, 1.0F, 0.0F));
                double err = Math.abs(yv.x - dir[0]) + Math.abs(yv.y - dir[1]) + Math.abs(yv.z - dir[2]);
                helper.assertTrue(err < 1.0E-4, name + ": 骨の回転と板の向きが食い違う " + err);
                Vector3f h = new Matrix3f().rotateZ(rot[2]).rotateY(rot[1]).rotateX(rot[0]).transform(new Vector3f(0.0F, (float) p.depth, 0.0F));
                double slip = Math.abs(-pos[0] + h.x) + Math.abs(pos[1] + h.y - p.depth) + Math.abs(pos[2] + h.z);
                helper.assertTrue(slip < 1.0E-3, name + ": 蝶番がずれる " + slip);
            }
            // 背が上(真下 −y): 外へ寝る。脇の板は下へ垂れ、先(長さ 5・18)は体の輪の外
            double b = SkyBossHang.target(p, 0.0, -1.0, 0.0, p.min());
            helper.assertTrue(Math.abs(b - p.max()) < 1.0E-9, name + ": 背が上なのに外へ寝ない(" + Math.toDegrees(b) + "°)");
            SkyBossHang.direction(p, b, dir);
            helper.assertTrue(dir[0] * Math.signum(px) > 0.5, name + ": 背が上で脇の向きへ倒れていない " + dir[0]);
            if (Math.abs(xf[i]) > 0.5) {
                helper.assertTrue(dir[1] < -0.2, name + ": 背の脇の板が垂れていない " + dir[1]);
            }
            for (double len : new double[]{5.0, 18.0}) {
                double tx = px + dir[0] * len;
                double ty = py + p.depth + dir[1] * len - SkyBossBody.Y0;
                double e = sq(tx / ra) + sq(ty / rb);
                helper.assertTrue(e > 1.0, name + ": 寝た板の先が体の中 " + e + "(長さ " + len + ")");
            }
            // 腹が上で 60° 傾く: 振れる面に映した真下へ
            for (double s : new double[]{1.0, -1.0}) {
                double gx = s * Math.sin(Math.toRadians(60.0));
                double gy = Math.cos(Math.toRadians(60.0));
                SkyBossHang.direction(p, SkyBossHang.target(p, gx, gy, 0.0, 0.0), dir);
                double gq = gx * p.qx;
                double dq = dir[0] * p.qx + dir[2] * p.qz;
                double proj = (dq * gq + dir[1] * gy) / Math.sqrt(gq * gq + gy * gy) / Math.sqrt(dq * dq + dir[1] * dir[1]);
                helper.assertTrue(proj > Math.cos(Math.toRadians(1.0)), name + ": 傾いても真下へ垂れない(" + s + ")" + proj);
            }
            // 腹 → 背 → 腹を素で流す(左回り・右回り。出ていくときは来た向きへ戻る): 角度は跳ばず、体に入らず、
            // 背が上で少し待つと外へ寝て、腹が上へ戻ると真下へ垂れる
            for (double s : new double[]{1.0, -1.0}) {
                double[] phi = {0.0};
                double[] vel = {0.0};
                double dt = 1.0 / 60.0;
                int roll = (int) (SkyBossTuning.ROLL_TICKS / 20.0 / dt);
                int hold = (int) (8.0 / dt);
                for (int k = 0; k <= 2 * (roll + hold); k++) {
                    int kk = k <= roll + hold ? k : k - (roll + hold);
                    double u = Math.min(1.0, kk / (double) roll);
                    double sm = u * u * (3.0 - 2.0 * u);
                    double th = Math.PI * (k <= roll + hold ? sm : 1.0 - sm);
                    double before = phi[0];
                    SkyBossHang.follow(p, phi, vel, 0, SkyBossHang.target(p, s * Math.sin(th), Math.cos(th), 0.0, phi[0]), dt);
                    helper.assertTrue(Math.abs(phi[0] - before) < Math.toRadians(5.0), name + ": 転がる間に角度が跳ぶ " + Math.toDegrees(phi[0] - before) + "°");
                    helper.assertTrue(phi[0] >= p.min() - 1.0E-9 && phi[0] <= p.max() + 1.0E-9, name + ": 体の中へ倒れた");
                    if (k == roll + hold) {
                        helper.assertTrue(Math.abs(phi[0] - p.max()) < Math.toRadians(2.0), name + ": 転がり終えても外へ寝ていない(" + s + ")" + Math.toDegrees(phi[0]));
                    }
                }
                helper.assertTrue(Math.abs(phi[0]) < Math.toRadians(2.0), name + ": 腹が上へ戻っても真下へ垂れない(" + s + ")" + Math.toDegrees(phi[0]));
            }
        }
        // 体の骨の行列から出す真下(描画が使う): 腹が上で +y、背が上で −y
        SkyBossShapes s = SkyBossShapes.get();
        if (s != null) {
            SkyBossFlight f = straightFlight();
            SkyBossPose pose = new SkyBossPose();
            pose.compute(f, f.x, f.y, f.z, 0.0F, 0.0, true);
            double[] down = new double[3];
            int bi = s.bodyIndex(PROBE_BONE);
            SkyBossHang.localDown(pose.bone[bi], down);
            helper.assertTrue(down[1] > 0.9, "腹が上なのに骨の空間の真下が +y でない " + down[1]);
            f.rollFrom = 180.0F;
            f.rollTo = 0.0F;
            f.rollStart = -10000L;
            pose.compute(f, f.x, f.y, f.z, 0.0F, 0.0, false);
            pose.bodyBones(0.0);
            SkyBossHang.localDown(pose.bone[bi], down);
            helper.assertTrue(down[1] < -0.9, "背が上なのに骨の空間の真下が −y でない " + down[1]);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 放電(10-19)。ワールドのボスは使わない
    /**
     * 腹が上の渡りから: 雷 8 本で溜まりきり、戦いの中でだけ始め、背を上へ転がり(尾まで転がり終えてから雷)、30 秒で終わって腹を上へ戻る。
     * 扇は放電の間 discharge_fans。終わると溜めは 0、すぐには始めない。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dischargeChargesRollsAndEnds(GameTestHelper helper) {
        SkyBossFlight f = straightFlight();
        SkyBossDischarge dc = new SkyBossDischarge();
        for (int i = 0; i < SkyBossTuning.CHARGE_STRIKES; i++) {
            helper.assertTrue(f.charge < 1.0F, "雷 " + i + " 本で溜まりきった");
            helper.assertTrue(dc.struck(f, 100L + i * 300L), "雷を溜めに数えない");
        }
        helper.assertTrue(!dc.struck(f, 100L + (SkyBossTuning.CHARGE_STRIKES - 1) * 300L), "同じ雷を 2 回数えた");
        helper.assertTrue(f.charge >= 1.0F, "雷 " + SkyBossTuning.CHARGE_STRIKES + " 本で溜まりきらない: " + f.charge);
        helper.assertTrue(!dc.tick(f, f.tick, false, false, true) && dc.mode == SkyBossDischarge.IDLE, "戦っていないのに始めた");
        helper.assertTrue(!dc.tick(f, f.tick, true, false, false), "避雷針が壊れているのに始めた");
        long t0 = f.tick;
        helper.assertTrue(dc.tick(f, t0, true, false, true) && dc.mode == SkyBossDischarge.PREP, "戦いの中で溜まりきっても始めない");
        helper.assertTrue(dc.blocksFeeding() && !dc.holdsFeeding(), "雷雲の外の放電なのに雷雲に寄る");
        helper.assertTrue(Math.abs(Math.IEEEremainder(f.rollTo, 360.0)) < 1.0E-3, "背を上へ転がらない: " + f.rollTo);
        helper.assertTrue(f.dischargeAt == t0 + SkyBossTuning.ROLL_TICKS + SkyBossTuning.ROLL_LAG_TICKS, "尾まで転がり終える前に雷が始まる");
        helper.assertTrue(f.chargingUp(t0 + 10) && !f.discharging(t0 + 10), "予告の間なのに雷が飛ぶ・予告になっていない");
        // 進める
        while (f.tick < f.dischargeAt + 60) {
            f.step();
            dc.tick(f, f.tick, true, false, true);
        }
        helper.assertTrue(dc.mode == SkyBossDischarge.ACTIVE && f.discharging(f.tick), "放電にならない: " + SkyBossDischarge.name(dc.mode));
        helper.assertTrue(f.backUp(f.tick - SkyBossTuning.ROLL_LAG_TICKS) && f.backUp(f.tick), "放電なのに背が上でない");
        helper.assertTrue(f.fanMode == SkyBossFlight.FAN_DISCHARGE, "放電の間の扇が discharge_fans でない: " + f.fanMode);
        long end = f.dischargeEnd;
        helper.assertTrue(end - f.dischargeAt == SkyBossTuning.DISCHARGE_TICKS, "放電の長さが指定の 30 秒でない");
        while (f.tick < end + 2) {
            f.step();
            dc.tick(f, f.tick, true, false, true);
        }
        helper.assertTrue(!f.discharging(f.tick) && f.charge == 0.0F && dc.mode == SkyBossDischarge.RECOVER, "30 秒で終わらない・溜めが残る");
        helper.assertTrue(f.rolling(f.tick) && Math.abs(Math.IEEEremainder(f.rollTo - 180.0, 360.0)) < 1.0E-3, "終わっても腹を上へ戻らない: " + f.rollTo);
        f.charge = 1.0F;
        helper.assertTrue(!dc.canStart(f, f.tick, true), "終わってすぐまた始められる");
        while (dc.mode != SkyBossDischarge.IDLE && f.tick < end + 2000) {
            f.step();
            dc.tick(f, f.tick, true, false, true);
        }
        helper.assertTrue(dc.mode == SkyBossDischarge.IDLE && !f.backUp(f.tick), "戻り終えても渡りに戻らない・背が上のまま");
        if (SkyBossShapes.get() != null) {
            double len = com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock.length(com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock.DISCHARGE);
            helper.assertTrue(Math.abs(len - 72.0) < 1.0, "形のファイルの discharge_fans の長さ " + len + " tick が 3.6 秒でない(書き出し直していない?)");
        }
        helper.succeed();
    }

    /** ショート: 避雷針が壊れたら放電の途中でも止まり、溜めは 0、隙の間は溜まっても始めない。体が沈むのを待ってから腹を上へ戻る。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void shortStopsDischargeAndStuns(GameTestHelper helper) {
        SkyBossFlight f = straightFlight();
        SkyBossDischarge dc = new SkyBossDischarge();
        f.charge = 1.0F;
        dc.tick(f, f.tick, true, false, true);
        while (f.tick < f.dischargeAt + 100) {
            f.step();
            dc.tick(f, f.tick, true, false, true);
        }
        helper.assertTrue(f.discharging(f.tick), "放電にならない");
        long now = f.tick;
        dc.shorted(f, now);
        helper.assertTrue(!f.discharging(now) && !f.discharging(now + 1) && f.charge == 0.0F, "ショートしても雷が止まらない・溜めが残る");
        helper.assertTrue(f.stunUntil == now + SkyBossTuning.STUN_TICKS && dc.mode == SkyBossDischarge.RECOVER, "隙にならない");
        // 体が沈んで跳ね返るのを待つ間は背が上のまま
        for (int i = 0; i < SkyBossTuning.SHORT_ROLL_DELAY - 2; i++) {
            f.step();
            dc.tick(f, f.tick, true, false, true);
        }
        helper.assertTrue(!f.rolling(f.tick) && f.backUp(f.tick), "体が沈んでいる間に転がり始めた");
        for (int i = 0; i < 4; i++) {
            f.step();
            dc.tick(f, f.tick, true, false, true);
        }
        helper.assertTrue(f.rolling(f.tick), "ショートの後、腹を上へ戻らない");
        while (dc.mode != SkyBossDischarge.IDLE && f.tick < now + 2000) {
            f.step();
            dc.tick(f, f.tick, true, false, true);
        }
        f.charge = 1.0F;
        long cool = Math.max(dc.cooldownUntil, f.stunUntil);
        helper.assertTrue(!dc.canStart(f, f.tick, true) && f.tick < cool, "隙の間に始められる");
        helper.assertTrue(dc.canStart(f, cool, true), "隙が終わっても始められない");
        // 予告の途中のショートでも止まる
        SkyBossFlight g = straightFlight();
        SkyBossDischarge dg = new SkyBossDischarge();
        g.charge = 1.0F;
        dg.tick(g, g.tick, true, false, true);
        for (int i = 0; i < 100; i++) {
            g.step();
            dg.tick(g, g.tick, true, false, true);
        }
        dg.shorted(g, g.tick);
        for (int i = 0; i < SkyBossTuning.ROLL_TICKS + SkyBossTuning.ROLL_LAG_TICKS; i++) {
            g.step();
            dg.tick(g, g.tick, true, false, true);
            helper.assertTrue(!g.discharging(g.tick) && !g.chargingUp(g.tick), "予告の途中でショートしたのに雷が始まる・予告が続く");
        }
        // 背を上へ転がっている最中のショートでも、戻り終えたら腹が上(背が上のまま渡りに残らない)
        while (dg.mode != SkyBossDischarge.IDLE && g.tick < 100000L) {
            g.step();
            dg.tick(g, g.tick, false, false, false);
        }
        helper.assertTrue(dg.mode == SkyBossDischarge.IDLE && !g.backUp(g.tick) && !g.rolling(g.tick), "予告の途中でショートした後、背が上のまま残った: " + g.rollAt(g.tick));
        helper.succeed();
    }

    /** 雷雲の中(もう背が上)なら転がらずに予告の後すぐ、寄り道を留まらせ雷を止め、終わっても背が上のまま。背を上への行き先は決まった向き。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dischargeInStormKeepsBackUp(GameTestHelper helper) {
        SkyBossFlight f = straightFlight();
        f.rollFrom = 180.0F;
        f.rollTo = 0.0F;
        f.rollStart = f.tick - 10000L;
        f.charge = 1.0F;
        SkyBossDischarge dc = new SkyBossDischarge();
        long t0 = f.tick;
        helper.assertTrue(dc.tick(f, t0, true, true, true), "雷雲の中で始めない");
        helper.assertTrue(!f.rolling(t0 + 1) && f.dischargeAt == t0 + SkyBossTuning.DISCHARGE_TELEGRAPH, "背が上なのに転がる・予告の長さが違う");
        helper.assertTrue(dc.holdsFeeding() && !dc.blocksFeeding(), "雷雲の中の放電なのに寄り道を留まらせない");
        while (dc.mode != SkyBossDischarge.IDLE && f.tick < t0 + 2000) {
            f.step();
            dc.tick(f, f.tick, true, true, true);
        }
        helper.assertTrue(dc.mode == SkyBossDischarge.IDLE && f.backUp(f.tick) && !f.rolling(f.tick), "雷雲の中の放電の後に転がった");
        // 背を上への行き先: 傾いていた側へ、もう背が上ならそのまま(前は今 ± 180 で腹が上へ戻った)
        helper.assertTrue(SkyBossFlight.backUpRoll(180.0, 5.0F) == 360.0F && SkyBossFlight.backUpRoll(180.0, -5.0F) == 0.0F, "腹が上からの行き先が違う");
        helper.assertTrue(SkyBossFlight.backUpRoll(0.0, 5.0F) == 0.0F && SkyBossFlight.backUpRoll(360.0, -5.0F) == 360.0F, "背が上なのに転がる行き先");
        helper.assertTrue(SkyBossFlight.backUpRoll(-180.0, 5.0F) == 0.0F, "腹が上(−180)からの行き先が違う");
        // 範囲の中の相手へ雷が飛ぶ間: 近いほど短い
        helper.assertTrue(SkyBossCombat.arcInterval(0.0, 0.5) < SkyBossCombat.arcInterval(SkyBossTuning.DISCHARGE_RADIUS, 0.5), "近い相手ほど雷が多くない");
        helper.succeed();
    }

    /** 寄り道: 留まり始めたのがもう背が上でも、出ていくときは腹が上へ戻る。放電で留まっている間は出ていかず雷も落とさない。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void feedingHoldsForDischargeAndLeavesBellyUp(GameTestHelper helper) {
        SkyBossFlight f = straightFlight();
        f.rollFrom = 180.0F;
        f.rollTo = 0.0F;
        f.rollStart = f.tick - 10000L;
        // 雲の真ん中を腕の付いた所に置く(寄る区間ですぐ留まる)
        double[][] arm = {new double[3]};
        f.trail.pointsBehind(f.x, f.y, f.z, new double[]{SkyBossBody.ARM_CENTER}, arm);
        SkyBossFeeding.Storm st = new SkyBossFeeding.Storm(7L, arm[0][0], arm[0][2], 1500.0F, 1.0F);
        SkyBossFeeding.Orders o = new SkyBossFeeding.Orders();
        java.util.List<SkyBossFeeding.Storm> storms = java.util.List.of(st);
        // 留まり始める区間: segment は前の状態が FEED でないときに転がり始める
        SkyBossFeeding entry = new SkyBossFeeding();
        entry.force(st, f.tick);
        entry.lookLead = 0;
        // 腕の付いた所が真ん中にある = すぐ留まる
        for (int i = 0; i < 3 && entry.mode != SkyBossFeeding.FEED; i++) {
            entry.segment(f, f.tick, storms, o);
        }
        helper.assertTrue(entry.mode == SkyBossFeeding.FEED, "雲の真ん中で留まらない: " + SkyBossFeeding.name(entry.mode));
        helper.assertTrue(Math.abs(Math.IEEEremainder(f.rollTo, 360.0)) < 1.0E-3, "もう背が上なのに腹が上へ転がった: " + f.rollTo);
        // 放電で留まる間は出ていかない・雷を落とさない
        entry.hold = true;
        entry.nextStrike = f.tick;
        helper.assertTrue(!entry.strikeDue(f, f.tick + 10), "放電で留まっている間に雷が落ちる");
        entry.segment(f, f.tick + SkyBossTuning.FEED_TICKS + 10, storms, o);
        helper.assertTrue(entry.mode == SkyBossFeeding.FEED, "放電で留まっている間に出ていった");
        entry.hold = false;
        f.tick += SkyBossTuning.FEED_TICKS + 20;
        entry.segment(f, f.tick, storms, o);
        helper.assertTrue(entry.mode == SkyBossFeeding.LEAVE, "留まる時間が過ぎても出ていかない");
        helper.assertTrue(Math.abs(Math.IEEEremainder(f.rollTo - 180.0, 360.0)) < 1.0E-3, "出ていくのに腹が上へ戻らない: " + f.rollTo);
        helper.succeed();
    }

    // ------------------------------------------------------------------ 技の時刻表(10-19)
    /**
     * 体・触手・扇の技のアニメの時刻表: 雲を吐くと体の層が spit_body → 終わるとうねりへ、触手の技は drift → 打つ / 絡め取る → drift、
     * 下降気流は扇の層の downdraft を 1 回。どれも当たり判定の形が技の間に動き(見た目と同じアニメを引く)、長さは形のファイルの値。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void actClocksMoveHitShapes(GameTestHelper helper) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null) {
            helper.succeed();
            return;
        }
        helper.assertTrue(Math.abs(com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.bodyLength(com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.SPIT)
                - 6.0 * SkyBossTuning.ANIM_SLOW * 20.0) < 1.0, "spit_body の長さが道具と違う");
        helper.assertTrue(Math.abs(com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock.length(com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock.DOWNDRAFT)
                - 7.0 * SkyBossTuning.ANIM_SLOW * 20.0) < 1.0, "downdraft の長さが道具と違う");
        // 雲を吐く: 頭の輪切りが動く
        SkyBossFlight a = straightFlight();
        SkyBossFlight b = straightFlight();
        long t0 = 2000L;
        a.tick = b.tick = t0;
        com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.startSpit(b, t0);
        double[] seg = new double[4];
        double mid = t0 + SkyBossTuning.BODY_TRANSITION + 90.0;
        com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.body(b, mid, seg);
        helper.assertTrue(seg[0] == com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.SPIT && seg[2] == com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.UNDULATE,
                "雲を吐く間が spit_body にならない");
        double end = com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.spitEnd(b);
        com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.body(b, end + 5.0, seg);
        helper.assertTrue(seg[0] == com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.UNDULATE && seg[1] == end, "雲を吐き終えてもうねりへ戻らない");
        SkyBossPose pa = new SkyBossPose();
        SkyBossPose pb = new SkyBossPose();
        pa.compute(a, a.x, a.y, a.z, 0.0F, mid, true);
        pb.compute(b, b.x, b.y, b.z, 0.0F, mid, true);
        double headMove = 0.0;
        int heads = s.bodySlices[s.bodyIndex("head")].length;
        int first = 0;
        for (int i = 0; i < s.bodyIndex("head"); i++) {
            first += s.bodySlices[i].length;
        }
        for (int k = 0; k < heads; k++) {
            int o = (first + k) * 3;
            headMove = Math.max(headMove, Math.sqrt(sq(pa.sA[o] - pb.sA[o]) + sq(pa.sA[o + 1] - pb.sA[o + 1]) + sq(pa.sA[o + 2] - pb.sA[o + 2])));
        }
        helper.assertTrue(headMove > 5.0, "雲を吐く間に頭の当たり判定が動かない: " + headMove);
        // 触手で打つ: 触手の先が動く
        SkyBossFlight c = straightFlight();
        c.tick = t0;
        com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.startTentacle(c, t0, 0);
        double tm = t0 + SkyBossTuning.TENT_TRANSITION + 60.0;
        SkyBossPose pc = new SkyBossPose();
        pc.compute(c, c.x, c.y, c.z, 0.0F, tm, true);
        pa.compute(a, a.x, a.y, a.z, 0.0F, tm, true);
        double tipMove = 0.0;
        int ci = s.armCount * (s.armPoints - 1);
        for (int k = 0; k < s.tentCount * (s.tentPoints - 1); k++) {
            int o = (ci + k) * 3;
            tipMove = Math.max(tipMove, Math.sqrt(sq(pa.cB[o] - pc.cB[o]) + sq(pa.cB[o + 1] - pc.cB[o + 1]) + sq(pa.cB[o + 2] - pc.cB[o + 2])));
        }
        helper.assertTrue(tipMove > 10.0, "触手で打つ間に触手の当たり判定が動かない: " + tipMove);
        // 下降気流: 扇の層が 1 回 downdraft になり、流し終えると戻る
        SkyBossFlight d = straightFlight();
        d.downdraftAt = d.tick + 1;
        d.step();
        d.step();
        helper.assertTrue(d.fanMode == SkyBossFlight.FAN_DOWNDRAFT, "下降気流の扇にならない: " + d.fanMode);
        int len = (int) (SkyBossFanClock.TRANSITION + SkyBossFanClock.length(SkyBossFanClock.DOWNDRAFT)) + 2;
        for (int i = 0; i < len; i++) {
            d.step();
        }
        helper.assertTrue(d.fanMode != SkyBossFlight.FAN_DOWNDRAFT, "下降気流が終わっても扇が戻らない");
        helper.succeed();
    }

    /**
     * 一日のリズム(10-19 夕方): 夜に眠る → 夜明けに伸びとあくび(扇 stretch・体 stretch_body を 6 秒 × 時間の倍率)→ 晴れた朝は日光浴(雨なら しない)→ 朝 9 時で終わる /
     * 戦いになるとすぐ起き、伸びはしない / ときどきの身震いは扇の身震いと体の震えだけ(雷の身震い・放電の溜めに混ざらない)/ 眠り・日光浴は雷雲のホバリング・放電の扇より弱い /
     * 飛び方の追加の値は通信の上限 32 の中。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void dailyRhythmSleepsStretchesBasks(GameTestHelper helper) {
        com.yayuy.overgrownvegetation.skyboss.SkyBossRhythm r = new com.yayuy.overgrownvegetation.skyboss.SkyBossRhythm();
        SkyBossFlight f = straightFlight();
        long now = 5000L;
        // 夜(真夜中)
        r.tick(f, now, 18000L, true, true, false, 0.5);
        helper.assertTrue(f.restKind == SkyBossFlight.REST_SLEEP, "夜に眠らない: " + f.restKind);
        for (int i = 0; i < 3; i++) {
            f.step();
        }
        helper.assertTrue(f.fanMode == SkyBossFlight.FAN_SLEEP, "眠っているのに扇が sleep でない: " + f.fanMode);
        // 雷雲のホバリングの方が強い
        f.fanOverride = SkyBossFlight.FAN_HOVER;
        f.step();
        helper.assertTrue(f.fanMode == SkyBossFlight.FAN_HOVER, "眠りが雷雲のホバリングより強い: " + f.fanMode);
        f.fanOverride = -1;
        // 夜明け: 起きて伸びとあくび
        now = f.tick;
        r.tick(f, now, 23100L, true, true, false, 0.5);
        helper.assertTrue(f.restKind != SkyBossFlight.REST_SLEEP && f.stretchAt == now, "夜明けに起きて伸びをしない");
        double len = com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock.length(com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock.STRETCH);
        helper.assertTrue(Math.abs(len - 6.0 * SkyBossTuning.ANIM_SLOW * 20.0) < 1.0, "伸びの長さが道具と違う: " + len);
        double[] seg = new double[4];
        com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.body(f, now + 50, seg);
        helper.assertTrue(seg[0] == com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.STRETCH, "伸びの間に体が stretch_body でない");
        for (int i = 0; i < 3; i++) {
            f.step();
        }
        helper.assertTrue(f.fanMode == SkyBossFlight.FAN_STRETCH, "伸びの間に扇が stretch でない: " + f.fanMode);
        // 伸びの間は日光浴にならず、伸びが終わったら晴れた朝は日光浴
        r.tick(f, f.tick, 23200L, true, true, false, 0.5);
        helper.assertTrue(f.restKind != SkyBossFlight.REST_BASK, "伸びの途中で日光浴になった");
        long after = f.stretchAt + (long) (com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock.TRANSITION + len) + 2;
        while (f.tick < after) {
            f.step();
        }
        r.tick(f, f.tick, 500L, true, true, false, 0.5);
        helper.assertTrue(f.restKind == SkyBossFlight.REST_BASK, "晴れた朝に日光浴しない: " + f.restKind);
        f.yaw = 0.0F;
        double tilt = com.yayuy.overgrownvegetation.skyboss.SkyBossRhythm.baskRoll(f, f.tick + SkyBossTuning.BASK_EASE + 1);
        helper.assertTrue(Math.abs(Math.abs(tilt) - SkyBossTuning.BASK_TILT_DEG) < 0.5, "東が真横なのに日光浴の傾きが最大でない: " + tilt);
        // 日光浴の傾きで腹(腹の扉の外向き)が東(+x。日の昇る方)へ向く
        SkyBossShapes shp = SkyBossShapes.get();
        if (shp != null) {
            SkyBossFlight plain = straightFlight();
            plain.yaw = 0.0F;
            plain.straightTrail(SkyBossTuning.TRAIL_KEEP);
            SkyBossFlight basking = straightFlight();
            basking.yaw = 0.0F;
            basking.straightTrail(SkyBossTuning.TRAIL_KEEP);
            basking.restKind = SkyBossFlight.REST_BASK;
            basking.restSince = 0L;
            double tb = SkyBossTuning.BASK_EASE + 10.0;
            plain.tick = basking.tick = (long) tb;
            SkyBossPose p0 = new SkyBossPose();
            SkyBossPose p1 = new SkyBossPose();
            p0.compute(plain, plain.x, plain.y, plain.z, 0.0F, tb, true);
            p1.compute(basking, basking.x, basking.y, basking.z, 0.0F, tb, true);
            double[] n0 = new double[3];
            double[] n1 = new double[3];
            p0.spotOutward("bay_seed", n0);
            p1.spotOutward("bay_seed", n1);
            helper.assertTrue(n1[0] > n0[0] + 0.2, "日光浴で腹が東へ傾かない: 東の向きの成分 " + n0[0] + " → " + n1[0]);
        }
        // 朝 9 時で終わる(傾きを戻してから)
        r.tick(f, f.tick + 10, 3500L, true, true, false, 0.5);
        helper.assertTrue(f.restKind == com.yayuy.overgrownvegetation.skyboss.SkyBossRhythm.REST_BASK_END, "朝が過ぎても日光浴が終わらない");
        // 雨の朝は日光浴しない
        SkyBossFlight g = straightFlight();
        com.yayuy.overgrownvegetation.skyboss.SkyBossRhythm r2 = new com.yayuy.overgrownvegetation.skyboss.SkyBossRhythm();
        r2.tick(g, g.tick, 1000L, false, true, false, 0.5);
        helper.assertTrue(g.restKind == SkyBossFlight.REST_NONE, "雨の朝に日光浴した");
        // 戦いですぐ起き、伸びはしない
        SkyBossFlight h = straightFlight();
        com.yayuy.overgrownvegetation.skyboss.SkyBossRhythm r3 = new com.yayuy.overgrownvegetation.skyboss.SkyBossRhythm();
        r3.tick(h, h.tick, 18000L, true, true, false, 0.5);
        r3.tick(h, h.tick + 1, 18001L, true, false, false, 0.5);
        helper.assertTrue(h.restKind == SkyBossFlight.REST_NONE && h.stretchAt == SkyBossFlight.NONE, "戦いになっても起きない・伸びをした");
        // ときどきの身震い: 間の中で起き、雷の身震い・放電の溜めに混ざらない
        SkyBossFlight k = straightFlight();
        k.charge = 0.4F;
        com.yayuy.overgrownvegetation.skyboss.SkyBossRhythm r4 = new com.yayuy.overgrownvegetation.skyboss.SkyBossRhythm();
        long t0 = 100000L;
        long shiverAt = -1;
        for (long t = t0; t < t0 + SkyBossTuning.CASUAL_SHIVER_MAX + 10; t += 5) {
            r4.tick(k, t, 6000L, true, true, false, 0.0);
            if (k.shakeAt != SkyBossFlight.NONE) {
                shiverAt = k.shakeAt;
                break;
            }
        }
        helper.assertTrue(shiverAt >= t0 + SkyBossTuning.CASUAL_SHIVER_MIN - 5 && shiverAt <= t0 + SkyBossTuning.CASUAL_SHIVER_MAX + 5, "ときどきの身震いの間が違う: " + (shiverAt - t0));
        helper.assertTrue(k.shakeKind == SkyBossFlight.SHAKE_CASUAL && k.shudderAt == SkyBossFlight.NONE && Math.abs(k.charge - 0.4F) < 1.0E-6,
                "ときどきの身震いが雷の身震い・放電の溜めに混ざった");
        helper.assertTrue(f.extraLongs().length <= 32 && f.extraFloats().length <= 32, "飛び方の追加の値が通信の上限 32 を越えた");
        helper.succeed();
    }

    /**
     * 雲海からの飛び出し(10-19 夕方): 頭の道は始めの深さへ戻り、てっぺんは雲面より十分上・頭が雲面を抜けるのはアニメの「頭が雲面を抜ける」に近い所 /
     * ひねりは 360° で、道具と同じ時刻の外では 0 / 飛び出しの間は扇 breach_fans・体 breach_body。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void breachArcReturnsToDepth(GameTestHelper helper) {
        SkyBossFlight f = straightFlight();
        f.y = 394.0;
        f.pitch = 0.0F;
        f.pitchRate = 0.0F;
        f.speed = f.speedTarget = (float) SkyBossTuning.AIRSPEED;
        double y0 = f.y;
        double top = y0 + 94.0;
        long t0 = f.tick + 1;
        com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.startBreach(f, t0);
        double apex = y0;
        long surfaced = -1;
        boolean sawFan = false;
        boolean sawBody = false;
        long end = t0 + (long) (com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock.TRANSITION
                + com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock.length(com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock.BREACH)) + 2;
        double[] seg = new double[4];
        while (f.tick < end) {
            f.step();
            apex = Math.max(apex, f.y);
            if (surfaced < 0 && f.y > top) {
                surfaced = f.tick - t0;
            }
            sawFan |= f.fanMode == SkyBossFlight.FAN_BREACH;
            com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.body(f, f.tick, seg);
            sawBody |= seg[0] == com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.BREACH;
        }
        OvergrownVegetation.LOGGER.info("[sky boss test] breach: apex +{} blocks, head surfaced at {} ticks, back to {} (start {})",
                Math.round(apex - y0), surfaced, String.format(java.util.Locale.ROOT, "%.1f", f.y), y0);
        helper.assertTrue(Math.abs(f.y - y0) < 6.0, "飛び出しの後に始めの深さへ戻らない: " + y0 + " → " + f.y);
        helper.assertTrue(apex - top > 100.0, "てっぺんが雲面から低すぎる: " + (apex - top));
        helper.assertTrue(surfaced > 60 && surfaced < 200, "頭が雲面を抜ける時刻がアニメから外れすぎ: " + surfaced);
        helper.assertTrue(sawFan && sawBody, "飛び出しの間に扇・体が breach にならない");
        helper.assertTrue(com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.breachRoll(f, t0 + 100) == 0.0
                && com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.breachRoll(f, t0 + 600) == 0.0, "ひねりが道具の時刻の外で 0 でない");
        double mid = com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.breachRoll(f, t0 + 40 + 8.6 * SkyBossTuning.ANIM_SLOW * 20.0);
        helper.assertTrue(Math.abs(mid - 180.0) < 1.0, "ひねりの真ん中が 180° でない: " + mid);
        helper.assertTrue(Math.abs(f.pitch) < 0.5F, "飛び出しの後に機首が戻らない: " + f.pitch);
        helper.succeed();
    }

    /**
     * 雲海の状態(10-19 夕方。雲は偽の雲の海で): 深い雲の海の上で潜る → 下りきって隠れる → 上を飛ぶ相手に予告(盛り上がり・唸り)→ 飛び出す → 頭が雲海へ戻る所に穴 →
     * 奇襲の後は上がる → 雲を抜けたら潮吹き → 渡りの高さで終わり / 浅い雲の海では潜らない / 戦いになると上がる。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void cloudSeaHidesAmbushesAndRises(GameTestHelper helper) {
        com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.Probe deep = (x, z) -> new double[]{300.0, 488.0};
        com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.Probe shallow = (x, z) -> new double[]{300.0, 360.0};
        com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea c = new com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea();
        SkyBossFlight f = straightFlight();
        com.yayuy.overgrownvegetation.skyboss.SkyBossFeeding.Orders o = new com.yayuy.overgrownvegetation.skyboss.SkyBossFeeding.Orders();
        // 浅い雲の海では潜らない(確かめ用の命令でないとき)
        c.segment(f, f.tick, shallow, true, false, 0.0, o);
        helper.assertTrue(c.mode == com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.NONE, "浅い雲の海で潜った");
        // 深い雲の海: 潜る(高さの目標は雲の海の真ん中・扇はすぼめる)
        c.forceDive = true;
        o = new com.yayuy.overgrownvegetation.skyboss.SkyBossFeeding.Orders();
        c.segment(f, f.tick, deep, true, false, 0.0, o);
        helper.assertTrue(c.mode == com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.DIVE && Math.abs(o.altitude - 394.0) < 1.0
                && f.fanOverride == SkyBossFlight.FAN_TUCK, "深い雲の海で潜らない: " + c.mode + " 高さ " + o.altitude);
        // 下りきったら隠れる
        f.y = 394.0;
        f.pitch = 0.0F;
        c.tick(f, f.tick, false, 0.5);
        helper.assertTrue(c.mode == com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.HIDDEN, "下りきっても隠れない");
        // 上を相手が飛ぶ: 予告(盛り上がり・唸り)
        long now = f.tick + 5;
        c.tick(f, now, true, 0.5);
        boolean bulge = false;
        for (var e : c.events) {
            bulge |= e.kind() == com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.Event.BULGE;
        }
        helper.assertTrue(bulge && c.ambush, "上を相手が飛んでも予告しない");
        // 予告の後に飛び出す
        now += SkyBossTuning.BREACH_TELEGRAPH;
        c.tick(f, now, true, 0.5);
        helper.assertTrue(c.mode == com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.BREACH && f.breachAt == now, "予告の後に飛び出さない");
        // 頭が雲海へ戻る所に穴
        f.tick = now + SkyBossTuning.BREACH_REENTER;
        c.tick(f, f.tick, false, 0.5);
        boolean hole = false;
        for (var e : c.events) {
            hole |= e.kind() == com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.Event.HOLE;
        }
        helper.assertTrue(hole, "頭が雲海へ戻っても穴を開けない");
        // 終わり: 奇襲の後は上がる
        f.tick = now + 2000;
        c.tick(f, f.tick, false, 0.9);
        helper.assertTrue(c.mode == com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.RISE, "奇襲の後に上がらない: " + c.mode);
        o = new com.yayuy.overgrownvegetation.skyboss.SkyBossFeeding.Orders();
        c.segment(f, f.tick, deep, true, false, 0.5, o);
        helper.assertTrue(Math.abs(o.altitude - SkyBossTuning.CRUISE_Y) < 1.0 && f.fanOverride == SkyBossFlight.FAN_EAGER, "上がる指示が違う");
        // 雲を抜けたら潮吹き
        f.y = 520.0;
        c.tick(f, f.tick + 1, false, 0.5);
        helper.assertTrue(f.spoutAt == f.tick + 1, "雲を抜けても潮吹きをしない");
        // 渡りの高さで終わり
        f.y = SkyBossTuning.CRUISE_Y;
        c.tick(f, f.tick + 2, false, 0.5);
        helper.assertTrue(c.mode == com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.NONE && f.fanOverride == -1, "渡りの高さへ戻っても終わらない");
        // 戦いになると上がる
        com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea c2 = new com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea();
        SkyBossFlight g = straightFlight();
        c2.forceDive = true;
        c2.segment(g, g.tick, deep, true, false, 0.0, new com.yayuy.overgrownvegetation.skyboss.SkyBossFeeding.Orders());
        c2.segment(g, g.tick + 160, deep, false, true, 0.0, new com.yayuy.overgrownvegetation.skyboss.SkyBossFeeding.Orders());
        helper.assertTrue(c2.mode == com.yayuy.overgrownvegetation.skyboss.SkyBossCloudSea.RISE, "戦いになっても上がらない: " + c2.mode);
        helper.succeed();
    }

    /**
     * 技の弾(10-19): 先回りの狙いで動く相手に当たる / 種の爆弾は落ちる / 寿命で終わる / 蕾の一斉射の区切り(芯が光る → 開く → 撃つ → 閉じる)/
     * 段階 1・2 で開くのは腹の側の蕾(13〜20 番)だけ。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void attackShotsLeadAndPhases(GameTestHelper helper) {
        double[] from = {0.0, 0.0, 0.0};
        double[] at = {300.0, 40.0, -120.0};
        double[] vel = {0.6, 0.0, 0.9};
        double[] v = new double[3];
        com.yayuy.overgrownvegetation.skyboss.SkyBossShots.lead(from, at, vel, SkyBossTuning.AA_SPEED, v);
        com.yayuy.overgrownvegetation.skyboss.SkyBossShots shots = new com.yayuy.overgrownvegetation.skyboss.SkyBossShots();
        var s = shots.add(1, com.yayuy.overgrownvegetation.skyboss.SkyBossShots.SPORE, 0.0, 0.0, 0.0, v[0], v[1], v[2], 0L, SkyBossTuning.AA_LIFE);
        double best = Double.MAX_VALUE;
        for (int t = 1; t <= SkyBossTuning.AA_LIFE; t++) {
            com.yayuy.overgrownvegetation.skyboss.SkyBossShots.move(s);
            double tx = at[0] + vel[0] * t;
            double ty = at[1] + vel[1] * t;
            double tz = at[2] + vel[2] * t;
            best = Math.min(best, Math.sqrt(sq(s.x - tx) + sq(s.y - ty) + sq(s.z - tz)));
        }
        helper.assertTrue(best < SkyBossTuning.AA_BLAST, "先回りした胞子の塊が動く相手に届かない(いちばん近くて " + best + ")");
        var seed = shots.add(2, com.yayuy.overgrownvegetation.skyboss.SkyBossShots.SEED, 0.0, 950.0, 0.0, 0.0, 1.0, 0.0, 0L, 2000);
        com.yayuy.overgrownvegetation.skyboss.SkyBossShots.advanceTo(seed, 400L);
        helper.assertTrue(seed.y < 900.0 && seed.vy < 0.0, "種の爆弾が落ちない: y " + seed.y);
        var sh = shots.add(3, com.yayuy.overgrownvegetation.skyboss.SkyBossShots.SAP, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0L, 50);
        com.yayuy.overgrownvegetation.skyboss.SkyBossShots.advanceTo(sh, 60L);
        helper.assertTrue(sh.dead, "寿命を過ぎても樹液の弾が終わらない");
        // 対空砲火のばらけ方と時限信管(10-19 夜ユーザー「狙った弾 6 割・周囲にばらける弾 3 割・離れた所へ 1 割」「player の距離 ± 100 で爆発」)
        {
            java.util.Random rnd = new java.util.Random(7);
            double[] tgt = {600.0, -500.0, 200.0};
            double[] still = {0.0, 0.0, 0.0};
            double dist = Math.sqrt(sq(tgt[0]) + sq(tgt[1]) + sq(tgt[2]));
            int[] count = new int[3];
            double[] missSum = new double[3];
            double aimedWorst = 0.0;
            double scatterWorst = 0.0;
            double fuseLo = Double.MAX_VALUE;
            double fuseHi = -Double.MAX_VALUE;
            double aimedFuseLo = Double.MAX_VALUE;
            int n = 4000;
            for (int i = 0; i < n; i++) {
                int[] lk = com.yayuy.overgrownvegetation.skyboss.SkyBossShots.aimFlak(from, tgt, still, SkyBossTuning.AA_SPEED, rnd::nextDouble, v);
                count[lk[1]]++;
                // まっすぐ飛ぶ弾のいちばん近づく距離(動かない相手)
                double vl = Math.sqrt(sq(v[0]) + sq(v[1]) + sq(v[2]));
                double along = (tgt[0] * v[0] + tgt[1] * v[1] + tgt[2] * v[2]) / vl;
                double miss = Math.sqrt(Math.max(0.0, dist * dist - along * along));
                missSum[lk[1]] += miss;
                if (lk[1] == com.yayuy.overgrownvegetation.skyboss.SkyBossShots.AIMED) {
                    aimedWorst = Math.max(aimedWorst, miss);
                } else if (lk[1] == com.yayuy.overgrownvegetation.skyboss.SkyBossShots.SCATTERED) {
                    scatterWorst = Math.max(scatterWorst, miss);
                }
                double burst = lk[0] * SkyBossTuning.AA_SPEED - dist;
                fuseLo = Math.min(fuseLo, burst);
                fuseHi = Math.max(fuseHi, burst);
                if (lk[1] == com.yayuy.overgrownvegetation.skyboss.SkyBossShots.AIMED) {
                    aimedFuseLo = Math.min(aimedFuseLo, burst);
                }
            }
            helper.assertTrue(aimedFuseLo >= 0.0, "狙った弾が相手の手前で炸裂する: " + aimedFuseLo);
            // 動く相手は弾が着くときの位置までの距離 ± 100(真上へ落ちてくる相手: 1000 上から秒速 10 で落ちる → 当たるのは 1000 / 5.5 tick 後)
            double[] high = {0.0, 1000.0, 0.0};
            double[] falling = {0.0, -0.5, 0.0};
            double meet = 1000.0 - 0.5 * 1000.0 / (SkyBossTuning.AA_SPEED + 0.5);
            double movLo = Double.MAX_VALUE;
            double movHi = -Double.MAX_VALUE;
            for (int i = 0; i < 500; i++) {
                int[] lk = com.yayuy.overgrownvegetation.skyboss.SkyBossShots.aimFlak(from, high, falling, SkyBossTuning.AA_SPEED, rnd::nextDouble, v);
                double burst = lk[0] * SkyBossTuning.AA_SPEED - meet;
                movLo = Math.min(movLo, burst);
                movHi = Math.max(movHi, burst);
            }
            helper.assertTrue(movLo >= -SkyBossTuning.AA_FUSE_SPREAD - 1.0 && movHi <= SkyBossTuning.AA_FUSE_SPREAD + SkyBossTuning.AA_SPEED + 1.0,
                    "落ちてくる相手で、炸裂する距離が当たる所 ± " + SkyBossTuning.AA_FUSE_SPREAD + " に収まらない: " + movLo + "〜" + movHi);
            helper.assertTrue(Math.abs(count[0] / (double) n - SkyBossTuning.AA_AIMED_SHARE) < 0.03
                            && Math.abs(count[1] / (double) n - SkyBossTuning.AA_SCATTER_SHARE) < 0.03
                            && Math.abs(count[2] / (double) n - (1.0 - SkyBossTuning.AA_AIMED_SHARE - SkyBossTuning.AA_SCATTER_SHARE)) < 0.02,
                    "対空砲火のばらけ方の割合が違う: " + java.util.Arrays.toString(count));
            helper.assertTrue(aimedWorst < SkyBossTuning.AA_SPREAD * 2.0 * dist, "狙った弾がそれすぎる: " + aimedWorst);
            helper.assertTrue(scatterWorst <= SkyBossTuning.AA_SCATTER_MAX + 1.0 && missSum[1] / count[1] > SkyBossTuning.AA_SCATTER_MIN,
                    "まわりにばらける弾の散らばりが違う: 平均 " + missSum[1] / count[1] + " いちばん遠く " + scatterWorst);
            helper.assertTrue(missSum[2] / count[2] > SkyBossTuning.AA_SCATTER_MAX, "離れた所へ飛ぶ弾が相手の近くを通る: 平均 " + missSum[2] / count[2]);
            helper.assertTrue(fuseLo >= -SkyBossTuning.AA_FUSE_SPREAD && fuseHi <= SkyBossTuning.AA_FUSE_SPREAD + SkyBossTuning.AA_SPEED
                            && fuseHi - fuseLo > SkyBossTuning.AA_FUSE_SPREAD * 1.6,
                    "時限信管の炸裂する距離が相手の距離 ± " + SkyBossTuning.AA_FUSE_SPREAD + " に収まらない: " + fuseLo + "〜" + fuseHi);
        }
        // 蕾の一斉射の区切り
        SkyBossFlight f = straightFlight();
        f.budsAt = 1000L;
        f.budsEnd = 1000L + SkyBossTuning.AA_GLOW_TICKS + SkyBossTuning.BUDS_OPEN_TICKS + SkyBossTuning.AA_FIRE_TICKS + SkyBossTuning.BUDS_CLOSE_TICKS;
        helper.assertTrue(f.budsPhase(999) == 0 && f.budsPhase(1005) == 1, "芯が光る間になっていない");
        helper.assertTrue(f.budsPhase(1000 + SkyBossTuning.AA_GLOW_TICKS + 1) == 2, "開く間になっていない");
        helper.assertTrue(f.budsPhase(1000 + SkyBossTuning.AA_GLOW_TICKS + SkyBossTuning.BUDS_OPEN_TICKS + 1) == 3, "撃つ間になっていない");
        helper.assertTrue(f.budsPhase(f.budsEnd - 2) == 4 && f.budsPhase(f.budsEnd) == 0, "閉じる間・終わりが違う");
        // 蕾ごとにばらばらに開いて撃ち始める(10-19 夜): 開ききる時刻が蕾ごとに 2 秒より散らばり、どれも開くアニメの中に収まる。右の蕾は左と同じ
        {
            SkyBossShapes shp0 = SkyBossShapes.get();
            if (shp0 != null) {
                double lo = Double.MAX_VALUE;
                double hi = 0.0;
                for (int i = 0; i < shp0.budCount; i++) {
                    double o = com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.budOpenTicks(shp0.budName[i]);
                    lo = Math.min(lo, o);
                    hi = Math.max(hi, o);
                }
                helper.assertTrue(hi - lo > 40.0 && hi <= SkyBossTuning.BUDS_OPEN_TICKS, "蕾の開ききる時刻の散らばり・収まりが違う: " + lo + "〜" + hi);
                helper.assertTrue(com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.budOpenTicks("bud13_r")
                        == com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.budOpenTicks("bud13_l"), "右の蕾が左の写しと違う時刻に開く");
            }
        }
        helper.assertTrue(com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.skyBud("bud13_l") && com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.skyBud("bud20_r")
                && !com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.skyBud("bud12_l") && !com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.skyBud("bud21_r"),
                "腹の側の蕾の見分けが道具(13〜20 番)と違う");
        // 形のファイルの技の出どころの点(版 5)
        SkyBossShapes shp = SkyBossShapes.get();
        if (shp != null) {
            for (String sp : new String[]{"gland1_l", "gland2_r", "sac1_l", "sac3_r", "maw", "bay_seed"}) {
                helper.assertTrue(shp.spots.containsKey(sp), "技の出どころの点 " + sp + " が無い(形の版 5 で書き出し直す)");
            }
        }
        helper.succeed();
    }

    /**
     * 段階 2 の技(10-19): 種の爆弾の弧が狙った所の近くへ落ちる(y 950 から)/ 段階が変わる震えは扇の身震いを流すが、雷の身震い(放電の溜めに数える)は使わない /
     * 種の爆弾の反動は体だけ震える。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void seedLobAndStageShake(GameTestHelper helper) {
        double[] from = {0.0, 950.0, 0.0};
        double[] v = new double[3];
        for (double[] at : new double[][]{{180.0, 64.0, -120.0}, {-40.0, 70.0, 30.0}, {0.0, 64.0, 5.0}}) {
            com.yayuy.overgrownvegetation.skyboss.SkyBossShots.aimLob(from, at, SkyBossTuning.SEED_UP, v);
            com.yayuy.overgrownvegetation.skyboss.SkyBossShots shots = new com.yayuy.overgrownvegetation.skyboss.SkyBossShots();
            var s = shots.add(1, com.yayuy.overgrownvegetation.skyboss.SkyBossShots.SEED, from[0], from[1], from[2], v[0], v[1], v[2], 0L, SkyBossTuning.SEED_LIFE);
            int ticks = 0;
            while (!(s.vy < 0.0 && s.y <= at[1]) && ticks < 5000) {
                com.yayuy.overgrownvegetation.skyboss.SkyBossShots.move(s);
                ticks++;
            }
            double miss = Math.hypot(s.x - at[0], s.z - at[2]);
            helper.assertTrue(miss < 3.0, "種の爆弾が狙った所に落ちない: " + String.format("%.1f", miss) + " ブロック外れた(" + at[0] + ", " + at[2] + ")");
            helper.assertTrue(ticks < SkyBossTuning.SEED_LIFE, "種の爆弾が寿命の前に地上へ届かない: " + ticks + " tick");
        }
        // 届かない遠くでも数が壊れない
        com.yayuy.overgrownvegetation.skyboss.SkyBossShots.aimLob(from, new double[]{5000.0, 64.0, 0.0}, SkyBossTuning.SEED_UP, v);
        helper.assertTrue(Double.isFinite(v[0]) && v[0] > 0.0, "遠すぎる所を狙うと速さが壊れる: " + v[0]);
        // 段階が変わる震え
        SkyBossFlight f = straightFlight();
        f.charge = 0.3F;
        f.shakeAt = f.tick + 1;
        f.shakeKind = SkyBossFlight.SHAKE_STAGE;
        for (int i = 0; i < 4; i++) {
            f.step();
        }
        helper.assertTrue(f.fanMode == SkyBossFlight.FAN_SHIVER, "段階が変わっても扇が身震いしない: " + f.fanMode);
        helper.assertTrue(f.shudderAt == SkyBossFlight.NONE && Math.abs(f.charge - 0.3F) < 1.0E-6, "段階の震えが雷の身震い・放電の溜めに混ざった");
        SkyBossShapes s = SkyBossShapes.get();
        if (s != null) {
            SkyBossFlight g = straightFlight();
            g.tick = f.tick;
            SkyBossPose pf = new SkyBossPose();
            SkyBossPose pg = new SkyBossPose();
            double t = f.shakeAt + 9.0;
            pf.compute(f, f.x, f.y, f.z, 0.0F, t, true);
            pg.compute(g, f.x, f.y, f.z, 0.0F, t, true);
            double bend = 0.0;
            for (int k = 0; k < SkyBossPose.BEND_COUNT; k++) {
                bend = Math.max(bend, Math.abs(pf.bends[k][0] - pg.bends[k][0]));
            }
            helper.assertTrue(bend > 1.0E-3, "段階が変わっても体が震えない");
            // 種の爆弾の反動: 体は震えるが扇は身震いしない
            SkyBossFlight h = straightFlight();
            h.shakeAt = h.tick + 1;
            h.shakeKind = SkyBossFlight.SHAKE_SEED;
            for (int i = 0; i < 4; i++) {
                h.step();
            }
            helper.assertTrue(h.fanMode != SkyBossFlight.FAN_SHIVER, "種の爆弾の反動で扇が身震いした");
        }
        helper.succeed();
    }

    /**
     * 大技 1(10-19): 転がりはゆっくり始まって速くなり、回りきって腹が上へ戻る(跳ばない)/ 蕾は芯が光る → 頭から尾へ開く → ビーム → 閉じる /
     * ビームは頭の蕾から先に出る / 速く掃いたビームが 1 tick に通り越した相手にも当たる / 転がり終わった体は転がらなかった体と同じ姿勢。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void beamRollsAroundAndReturnsBellyUp(GameTestHelper helper) {
        SkyBossFlight f = straightFlight();
        long t0 = 1000L;
        com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.start(f, t0, SkyBossTuning.BEAM_TURNS);
        long rs = t0 + SkyBossTuning.BEAM_ROLL_DELAY;
        long re = com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.rollEnd(f);
        helper.assertTrue(com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.roll(f, rs - 1) == 0.0, "転がりが早く始まった");
        double nearEnd = com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.roll(f, re - 1.0E-3);
        helper.assertTrue(Math.abs(nearEnd - SkyBossTuning.BEAM_TURNS * 360.0) < 0.5, "回りきらない: " + nearEnd);
        helper.assertTrue(com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.roll(f, re) == 0.0, "転がり終わりに足す分が残る");
        double last = -1.0;
        double early = com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.rate(f, rs + 10);
        double peak = 0.0;
        for (long t = rs; t < re; t++) {
            double r = com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.roll(f, t);
            helper.assertTrue(r >= last, "転がりが戻った: " + t);
            last = r;
            peak = Math.max(peak, com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.rate(f, t));
        }
        helper.assertTrue(early < peak * 0.2, "ゆっくり始まらない: " + early + " / " + peak);
        // 蕾の区切り(種類 2)
        helper.assertTrue(f.budsPhase(t0 + 5) == 1, "ビームの前に芯が光らない");
        helper.assertTrue(f.budsPhase(t0 + SkyBossTuning.AA_GLOW_TICKS + 1) == 2, "頭から尾へ開く間にならない");
        helper.assertTrue(f.budsPhase(t0 + SkyBossTuning.AA_GLOW_TICKS + SkyBossTuning.BEAM_WAVE_TICKS + 1) == 3, "ビームの間にならない");
        helper.assertTrue(f.budsPhase(re + 1) == 4 && f.budsPhase(f.beamEnd) == 0, "閉じる間・終わりが違う");
        // 速く掃いたビーム: 前の向き(1, 0, 0)→ 今の向き(0, 1, 0)の間の 45° にいる相手
        double[] prev = {1.0, 0.0, 0.0};
        double[] cur = {0.0, 1.0, 0.0};
        double[] p = {700.0, 700.0, 0.0};
        helper.assertTrue(com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.beamHits(0.0, 0.0, 0.0, prev, 0, cur, p), "掃いた間の相手に当たらない");
        helper.assertTrue(!com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.beamHits(0.0, 0.0, 0.0, prev, 0, prev, p), "動かないビームが 45° 外の相手に当たった");
        helper.assertTrue(!com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.beamHits(0.0, 0.0, 0.0, prev, 0, cur, new double[]{-700.0, 700.0, 0.0}),
                "掃いた外の相手に当たった");
        // 出す所(10-19 夜ユーザー「中心付近の横にいる時」): 腕の付いた所の真ん中の横は入り、頭の横は入らない
        {
            SkyBossFlight z = straightFlight();
            SkyBossPose pz = new SkyBossPose();
            pz.compute(z, z.x, z.y, z.z, 0.0F, 0.0, false);
            double[] fwd = z.forward();
            double[] side = {fwd[2], 0.0, -fwd[0]};
            double[] head = pz.points[0];
            double[] mid = null;
            for (int i = 0; i + 1 < pz.points.length; i++) {
                if (SkyBossPose.DIST[i] <= com.yayuy.overgrownvegetation.skyboss.SkyBossBody.ARM_CENTER
                        && SkyBossPose.DIST[i + 1] >= com.yayuy.overgrownvegetation.skyboss.SkyBossBody.ARM_CENTER) {
                    mid = pz.points[i];
                }
            }
            double[] nearMid = {mid[0] + side[0] * 400.0, mid[1], mid[2] + side[2] * 400.0};
            double[] nearHead = {head[0] + side[0] * 400.0, head[1], head[2] + side[2] * 400.0};
            double[] am = com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.alongAxis(pz, nearMid);
            double[] ah = com.yayuy.overgrownvegetation.skyboss.SkyBossAttacks.alongAxis(pz, nearHead);
            helper.assertTrue(Math.abs(am[0] - com.yayuy.overgrownvegetation.skyboss.SkyBossBody.ARM_CENTER) <= SkyBossTuning.BEAM_ZONE_HALF
                    && am[1] <= SkyBossTuning.BEAM_ZONE_DIST, "体の真ん中の横がビームの出す所に入らない: " + am[0] + " / " + am[1]);
            helper.assertTrue(Math.abs(ah[0] - com.yayuy.overgrownvegetation.skyboss.SkyBossBody.ARM_CENTER) > SkyBossTuning.BEAM_ZONE_HALF,
                    "頭の横がビームの出す所に入った: " + ah[0]);
        }
        SkyBossShapes s = SkyBossShapes.get();
        if (s != null) {
            int head = 0;
            int tail = 0;
            for (int i = 0; i < s.budCount; i++) {
                if (s.budCenter[i][2] < s.budCenter[head][2]) {
                    head = i;
                }
                if (s.budCenter[i][2] > s.budCenter[tail][2]) {
                    tail = i;
                }
            }
            double onHead = com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.onAt(f, s, head);
            double onTail = com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.onAt(f, s, tail);
            helper.assertTrue(onHead < onTail && onTail < re, "ビームが頭から尾へ順に出ない: " + onHead + " / " + onTail + " / " + re);
            // 転がりの真ん中では蕾の向きが転がらない体と違い、転がり終わった後は同じ
            SkyBossFlight g = straightFlight();
            SkyBossPose pf = new SkyBossPose();
            SkyBossPose pg = new SkyBossPose();
            double[] a = new double[3];
            double[] b = new double[3];
            double mid = rs + SkyBossTuning.BEAM_ROLL_TICKS * 0.5;
            f.tick = g.tick = (long) mid;
            pf.compute(f, f.x, f.y, f.z, 0.0F, mid, true);
            pg.compute(g, g.x, g.y, g.z, 0.0F, mid, true);
            double maxAng = 0.0;
            for (int i = 0; i < s.budCount; i++) {
                pf.budOutward(i, a);
                pg.budOutward(i, b);
                maxAng = Math.max(maxAng, Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, a[0] * b[0] + a[1] * b[1] + a[2] * b[2])))));
            }
            helper.assertTrue(maxAng > 30.0, "転がりの真ん中で蕾の向きが変わらない: " + maxAng);
            // ビームの揺れ(10-19 ユーザー「上下左右に少しだけ動かしながら」): 外向きから最大の傾きの中で動き、時刻が進むと向きが変わる
            double sway = 0.0;
            double moved = 0.0;
            double[] c = new double[3];
            for (int i = 0; i < s.budCount; i++) {
                pf.budOutward(i, a);
                com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.aim(pf, i, mid, b);
                com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.aim(pf, i, mid + 20.0, c);
                sway = Math.max(sway, Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, a[0] * b[0] + a[1] * b[1] + a[2] * b[2])))));
                moved = Math.max(moved, Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, b[0] * c[0] + b[1] * c[1] + b[2] * c[2])))));
            }
            helper.assertTrue(sway > 1.0 && sway <= SkyBossTuning.BEAM_SWAY_DEG * Math.sqrt(2.0) + 0.1, "ビームの揺れが決めた大きさの外: " + sway);
            helper.assertTrue(moved > 0.5, "ビームの向きが時刻で動かない: " + moved);
            double after = re + SkyBossTuning.BEAM_ROLL_LAG + 2.0;
            f.tick = g.tick = (long) after;
            pf.compute(f, f.x, f.y, f.z, 0.0F, after, true);
            pg.compute(g, g.x, g.y, g.z, 0.0F, after, true);
            maxAng = 0.0;
            for (int i = 0; i < s.budCount; i++) {
                pf.budOutward(i, a);
                pg.budOutward(i, b);
                maxAng = Math.max(maxAng, Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, a[0] * b[0] + a[1] * b[1] + a[2] * b[2])))));
            }
            helper.assertTrue(maxAng < 0.5, "転がり終わった後に腹が上へ戻っていない: " + maxAng);
        }
        helper.succeed();
    }

    /**
     * 触手の技(10-19): 打つ当たりの間({@link SkyBossTuning#LASH_FROM}〜{@link SkyBossTuning#LASH_TO})に、触手の先がいちばん速く動く /
     * 絡め取る捕まえる間に触手の先が伸びている。触手の届く長さと速さはログへ(数値を決める手がかり)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 20)
    public static void tentacleStrikeWindowsMatchAnimation(GameTestHelper helper) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null) {
            helper.succeed();
            return;
        }
        for (int kind = 0; kind <= 1; kind++) {
            SkyBossFlight f = straightFlight();
            long t0 = 3000L;
            com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.startTentacle(f, t0, kind);
            SkyBossPose ps = new SkyBossPose();
            int first = ps.tentFirstCapsule();
            double[] prev = null;
            double best = 0.0;
            long bestAt = -1;
            double reachMax = 0.0;
            long end = (long) Math.ceil(com.yayuy.overgrownvegetation.skyboss.SkyBossActClock.tentEnd(f));
            StringBuilder prof = new StringBuilder();
            for (long t = t0; t <= end; t++) {
                f.tick = t;
                ps.compute(f, f.x, f.y, f.z, 0.0F, t, true);
                if (first < 0) {
                    first = ps.tentFirstCapsule();
                }
                int each = ps.tentCapsulesEach();
                double[] tips = new double[ps.tentCount() * 3];
                double speed = 0.0;
                for (int tn = 0; tn < ps.tentCount(); tn++) {
                    int c = first + tn * each + each - 1;
                    int r0 = first + tn * each;
                    System.arraycopy(ps.cB, c * 3, tips, tn * 3, 3);
                    reachMax = Math.max(reachMax, Math.sqrt(sq(ps.cB[c * 3] - ps.cA[r0 * 3]) + sq(ps.cB[c * 3 + 1] - ps.cA[r0 * 3 + 1]) + sq(ps.cB[c * 3 + 2] - ps.cA[r0 * 3 + 2])));
                    if (prev != null) {
                        speed = Math.max(speed, Math.sqrt(sq(tips[tn * 3] - prev[tn * 3]) + sq(tips[tn * 3 + 1] - prev[tn * 3 + 1]) + sq(tips[tn * 3 + 2] - prev[tn * 3 + 2])));
                    }
                }
                if ((t - t0) % 6 == 0) {
                    prof.append(t - t0).append(':').append(String.format(java.util.Locale.ROOT, "%.1f", speed)).append(' ');
                }
                if (speed > best) {
                    best = speed;
                    bestAt = t - t0;
                }
                prev = tips;
            }
            OvergrownVegetation.LOGGER.info("[sky boss test] tentacle {}: fastest tip {} blocks/tick at {} ticks, reach {} blocks; speeds {}",
                    kind == 0 ? "lash" : "grab", String.format(java.util.Locale.ROOT, "%.1f", best), bestAt, Math.round(reachMax), prof);
            if (kind == 0) {
                helper.assertTrue(bestAt >= SkyBossTuning.LASH_FROM && bestAt <= SkyBossTuning.LASH_TO,
                        "触手で打つ当たりの間が、触手の先がいちばん速い所(" + bestAt + " tick)を外れている");
            }
        }
        helper.succeed();
    }

    private static SkyBossFlight straightFlight() {
        SkyBossFlight f = new SkyBossFlight();
        f.x = 1000.0;
        f.y = 900.0;
        f.z = -500.0;
        f.yaw = 30.0F;
        f.straightTrail(SkyBossTuning.TRAIL_KEEP);
        return f;
    }

    /** 点からいちばん近い胴の輪切りの軸 → 点の向き(外向き)。 */
    private static double[] outward(SkyBossPose pose, double px, double py, double pz) {
        double best = Double.MAX_VALUE;
        double[] n = {0.0, 1.0, 0.0};
        for (int i = 0; i < pose.sliceCount; i++) {
            int o = i * 3;
            double u = (px - pose.sA[o]) * pose.sEz[o] + (py - pose.sA[o + 1]) * pose.sEz[o + 1] + (pz - pose.sA[o + 2]) * pose.sEz[o + 2];
            u = Math.max(0.0, Math.min(pose.sLen[i], u));
            double qx = pose.sA[o] + pose.sEz[o] * u;
            double qy = pose.sA[o + 1] + pose.sEz[o + 1] * u;
            double qz = pose.sA[o + 2] + pose.sEz[o + 2] * u;
            double d = Math.sqrt(sq(px - qx) + sq(py - qy) + sq(pz - qz));
            if (d < best && d > 1.0E-6) {
                best = d;
                n = new double[]{(px - qx) / d, (py - qy) / d, (pz - qz) / d};
            }
        }
        return n;
    }

    /** 節 {@code bone} の真ん中の輪切り(中心・横の向き・横の半径)。 */
    private static Slice slice(SkyBossShapes s, SkyBossPose pose, String bone) {
        int b = s.bodyIndex(bone);
        int idx = 0;
        for (int i = 0; i < b; i++) {
            idx += s.bodySlices[i].length;
        }
        idx += s.bodySlices[b].length / 2;
        return slice(pose, idx);
    }

    /**
     * 後ろの胴(b13〜b18)の輪切りのうち、横の向き × {@code side} の側から真横に撃って、最初に当たるのが胴の表面(苔)になるもの(添字)。
     * 10-19 に脇腹へ小さな蕾を足したら、決め打ちの節の脇腹に蕾が来て、表面より先に蕾に当たった。無ければ -1。
     */
    private static int clearSliceIndex(SkyBossShapes s, SkyBossPose pose, double side) {
        int first = 0;
        int b13 = s.bodyIndex("b13");
        int b18 = s.bodyIndex("b18");
        for (int i = 0; i < b13; i++) {
            first += s.bodySlices[i].length;
        }
        int last = first;
        for (int i = b13; i <= b18; i++) {
            last += s.bodySlices[i].length;
        }
        SkyBossPose.Hit hit = new SkyBossPose.Hit();
        CLEAR_WHY.setLength(0);
        for (int idx = first; idx < last; idx++) {
            Slice sl = slice(pose, idx);
            double reach = sl.rx + 40.0;
            double ox = sl.ex[0] * side;
            double oy = sl.ex[1] * side;
            double oz = sl.ex[2] * side;
            if (!pose.raycast(sl.cx + ox * reach, sl.cy + oy * reach, sl.cz + oz * reach, sl.cx, sl.cy, sl.cz, hit)) {
                continue;
            }
            double d = Math.sqrt(sq(hit.x - sl.cx) + sq(hit.y - sl.cy) + sq(hit.z - sl.cz));
            // 矢を撃つ所(表面の外 2.5)も、ほかの部位から離れていること
            double px = sl.cx + ox * (sl.rx + 2.5);
            double py = sl.cy + oy * (sl.rx + 2.5);
            double pz = sl.cz + oz * (sl.rx + 2.5);
            if (hit.part != SkyBossPose.PART_MOSS || Math.abs(d - sl.rx) >= 1.0) {
                if (CLEAR_WHY.length() < 400) {
                    CLEAR_WHY.append(idx).append(":部位").append(hit.part).append("/").append(String.format(java.util.Locale.ROOT, "%.1f-%.1f", d, sl.rx)).append(" ");
                }
                continue;
            }
            boolean clear = true;
            for (int b = 0; b < pose.sphCount && clear; b++) {
                int o = b * 3;
                clear = Math.sqrt(sq(px - pose.spC[o]) + sq(py - pose.spC[o + 1]) + sq(pz - pose.spC[o + 2])) - pose.spR[b] > 3.0;
            }
            if (clear) {
                return idx;
            }
        }
        return -1;
    }

    /** {@link #clearSliceIndex} が外した輪切りの理由(テストの失敗の文に)。 */
    private static final StringBuilder CLEAR_WHY = new StringBuilder();

    private static Slice slice(SkyBossPose pose, int idx) {
        int o = idx * 3;
        Slice sl = new Slice();
        double half = pose.sLen[idx] * 0.5;
        sl.cx = pose.sA[o] + pose.sEz[o] * half;
        sl.cy = pose.sA[o + 1] + pose.sEz[o + 1] * half;
        sl.cz = pose.sA[o + 2] + pose.sEz[o + 2] * half;
        sl.ex = new double[]{pose.sEx[o], pose.sEx[o + 1], pose.sEx[o + 2]};
        sl.rx = pose.sRx[idx];
        return sl;
    }

    private static final class Slice {
        double cx;
        double cy;
        double cz;
        double[] ex;
        double rx;
    }

    private static double sq(double v) {
        return v * v;
    }

    private SkyBossGameTests() {
    }
}
