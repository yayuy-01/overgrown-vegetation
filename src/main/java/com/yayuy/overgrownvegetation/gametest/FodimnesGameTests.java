package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.adaptation.Adaptation;
import com.yayuy.overgrownvegetation.entity.FodimnesBody;
import com.yayuy.overgrownvegetation.entity.FodimnesEntity;
import com.yayuy.overgrownvegetation.entity.FodimnesFrames;
import com.yayuy.overgrownvegetation.entity.FodimnesPart;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Locale;

/**
 * フォディムネス(グループ5の地中の砂虫型ワーム、2026-10-01)のテスト。守りたいのはユーザーが決めた動かし方(SPEC の原文):
 * <ul>
 *     <li><b>頭の通った道をたどる</b>: 節 16 + 尾が、頭の道を弧長で <b>0.80 ブロック</b>おきにさかのぼった点に並ぶ(まっすぐ・曲がる・縦に潜る)。
 *         履歴はスプライン(向心の Catmull-Rom)でつなぎ、<b>弧長で等間隔</b>(点の間隔がばらばらでも)。</li>
 *     <li><b>ひねり</b>: 旋回の速さに比例して内側へ傾き(バンク)、頭の roll が位置と同じ遅れで尾へ伝わる。</li>
 *     <li><b>向き</b>は回転最小化フレーム(真上を向いてもねじれない)。</li>
 *     <li><b>すべての節に攻撃が当たり、体力は共通</b>。爆発のような多重ヒットは 1 回分。</li>
 *     <li><b>ブロックを壊さない</b>・窒息しない(地形をすり抜ける)。</li>
 *     <li>採用案の能力: 採掘の音を聞いて向かい、壁の中で待ち伏せ(lurk)→ 飛び出して(burst)→ 噛む(snap)。音が 8 秒途絶えたら見失う。</li>
 * </ul>
 * 道・フレームの計算は世界に入れていない実体と静的な関数で直接叩く(tick の代わりに {@code updateBody} を呼ぶ)。
 * 世界で動かすテストは 7×7 の中を石で埋めて作る(床は y=1)。世界に入れる個体の体(13.6 ブロック)は<b>頭の真上へ</b>伸ばして置く
 * ({@link #layBodyUp}。天井のバリアを抜けて空へ出るだけで、どのテスト場にも入らない)。頭の後ろへ横に伸ばすと、12〜13 ブロックおきに
 * 並んで同時に走る隣のテスト場まで節が届き、そこの矢・爆発に当たる(2026-10-09)。
 * 採掘の音は近くの全個体に届くので、音の口({@link FodimnesEntity#onBlockBroken})を通すテストは別のバッチ({@value #BATCH_HEARING})。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class FodimnesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(FodimnesGameTests.class);
        }

        private Registration() {
        }
    }

    /** 採掘の音(近くの全個体に届く)を通すテストのバッチ。ほかのフォディムネスのテストと同時に走らせない。 */
    static final String BATCH_HEARING = "fodimnes_hearing";
    /** 世界に入れない実体を動かす場所(ワールドには触らないので、どこでもよい)。 */
    private static final Vec3 FAR_ORIGIN = new Vec3(1000.5D, 80.0D, -2000.5D);

    private FodimnesGameTests() {
    }

    // ================================================================== 足場

    private static FodimnesEntity offLevel(GameTestHelper helper) {
        FodimnesEntity w = ModEntities.FODIMNES.get().create(helper.getLevel());
        if (w == null) {
            throw new GameTestAssertException("フォディムネスを作れない");
        }
        return pinScale(w);
    }

    /**
     * テストの足場の既定(全部の個体がここを通る):
     * <ul>
     *   <li>体格 1 に固定。テストは体格 1 の寸法で組んである(テスト場 7 マス・高さ 2 の横穴・体 13.6 ブロック)。mobs.json の体格を変えても
     *       揺れないように(2026-10-01、ユーザー「サイズが小さすぎる」で体格を上げたとき)。体格を上げた体は {@link #bodyFollowsPathAtLargeScale} が見る。</li>
     *   <li>洞窟の Mob に自分で気付かない(2026-10-02、Mob も襲うようにしたとき)。テスト場は 12〜13 ブロックおき(列 12・行 13。
     *       {@code StructureGridSpawner} の 7 + 5・7 + 6)に並んで同時に走るので、隣のテスト場の屋根の下の Mob に気付いて、頼んでいない標的を追い始める。
     *       気付く動きは {@link #noticesCaveMobsAndStrikesThem} が有効にして見る。</li>
     *   <li>洞窟に棲む個体に固定(2026-10-09、地上にも棲むようにしたとき)。テスト場の天井はバリア(岩でない)なので、放っておくと
     *       最初にうろつくときに地上に棲む個体と決まり、地表の Mob に気付く・地表で跳ねる動きになる。地上の個体は {@link #surfaceDwellerHuntsWithoutOuting} が見る。</li>
     * </ul>
     */
    private static FodimnesEntity pinScale(FodimnesEntity w) {
        w.getAttribute(Attributes.SCALE).setBaseValue(1.0D);
        w.refreshDimensions();
        w.setNoticeMobsForTests(false);
        w.setHabitatForTests(FodimnesEntity.Habitat.CAVE);
        return w;
    }

    private static float yawOf(Vec3 dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
    }

    private static float pitchOf(Vec3 dir) {
        double horiz = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        return (float) Math.toDegrees(-Math.atan2(dir.y, horiz));
    }

    /** 頭の軸の点を axis・向き (yaw, pitch) に置き、道を捨てて後ろへまっすぐ伸ばす。 */
    private static void start(FodimnesEntity w, Vec3 axis, float yaw, float pitch) {
        w.moveTo(axis.x, axis.y - FodimnesEntity.AXIS_Y * w.getScale(), axis.z, yaw, pitch);
        w.resetBodyStraight();
    }

    /** 1 tick 進めたことにする: 前の位置・向きを控えてから、頭の軸の点を axis・向きを (yaw, pitch) にして体を解く。 */
    private static void drive(FodimnesEntity w, Vec3 axis, float yaw, float pitch) {
        w.xo = w.getX();
        w.yo = w.getY();
        w.zo = w.getZ();
        w.yRotO = w.getYRot();
        w.xRotO = w.getXRot();
        w.setYRot(yaw);
        w.setXRot(pitch);
        w.setPos(axis.x, axis.y - FodimnesEntity.AXIS_Y * w.getScale(), axis.z);
        w.updateBody(false);
    }

    /** 向きを進む向き dir から出す版(真上・真下では yaw をそのまま)。 */
    private static void driveDir(FodimnesEntity w, Vec3 axis, Vec3 dir) {
        double horiz = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        drive(w, axis, horiz > 1.0E-6D ? yawOf(dir) : w.getYRot(), pitchOf(dir));
    }

    private static String fmt(Vec3 v) {
        return String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }

    /** テスト場の中(x・z 0〜6、y y0〜y1)を石で埋める。 */
    private static void fillStone(GameTestHelper helper, int y0, int y1) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                for (int y = y0; y <= y1; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
    }

    /** 石で埋めたはずのマスのうち、石でなくなった数(ブロックを壊していないかを見る)。skip は元から空けたマス。 */
    private static int missingStone(GameTestHelper helper, int y0, int y1, java.util.function.Predicate<BlockPos> skip) {
        int missing = 0;
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!skip.test(p) && !helper.getBlockState(p).is(Blocks.STONE)) {
                        missing++;
                    }
                }
            }
        }
        return missing;
    }

    /**
     * テスト場の中(x・z 0〜6、y y0〜y1)で、中身の詰まったブロック(全面の当たり)でなくなったマスの数と最初の 3 つ(相対の位置 = ブロック)。
     * 壊れていなければ空文字。汚染などで石の種類が変わっても当たりが残っていれば数えない(長いテストはテスト場の外からの汚染に当たることがある)。
     */
    private static String brokenSolid(GameTestHelper helper, int y0, int y1) {
        int n = 0;
        StringBuilder first = new StringBuilder();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                for (int y = y0; y <= y1; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    net.minecraft.world.level.block.state.BlockState st = helper.getBlockState(p);
                    if (!st.isCollisionShapeFullBlock(helper.getLevel(), helper.absolutePos(p))) {
                        if (++n <= 3) {
                            first.append(n > 1 ? "、" : "").append(p.toShortString()).append(" = ").append(st.getBlock().getName().getString());
                        }
                    }
                }
            }
        }
        return n == 0 ? "" : n + " 個(" + first + ")";
    }

    /** 世界に入れた個体を、テスト場の相対の軸の点 axisRel に、絶対の向き (yaw, pitch) で置き直す。体は真上へ({@link #layBodyUp})。 */
    private static FodimnesEntity spawnWorm(GameTestHelper helper, Vec3 axisRel, float yaw, float pitch) {
        Vec3 feet = axisRel.subtract(0.0D, FodimnesEntity.AXIS_Y, 0.0D);
        FodimnesEntity w = pinScale(helper.spawn(ModEntities.FODIMNES.get(), feet));
        Vec3 abs = helper.absoluteVec(feet);
        w.moveTo(abs.x, abs.y, abs.z, yaw, pitch);
        layBodyUp(w);
        return w;
    }

    /**
     * 道を捨てて、体を頭の真上へまっすぐ置き直す(頭の向きはそのまま。動き出せば体は頭の通った道をたどる)。世界に入れる個体は全部これを通す。
     * 2026-10-09: 隅 (0.6, 0.6) から対角線の向きに置いた個体の体が頭の後ろへ 13.6 ブロック伸びて、斜め隣(列 −12・行 −13)のテスト場の
     * 真ん中まで届き、スクティムネスの {@code guardStoresEnchantedArrowDamage} の矢を節が横取りしていた(矢は節に当たって消え、
     * こちらの個体も矢のダメージを受けていた)。部位の当たり判定は {@code Level#getEntities} が世界じゅうから拾う。
     */
    private static void layBodyUp(FodimnesEntity w) {
        float pitch = w.getXRot();
        w.setXRot(90.0F);
        w.resetBodyStraight();
        w.setXRot(pitch);
    }

    // ================================================================== 道をたどる(ユーザー指定: パス追従・間隔 0.80)

    /** まっすぐ進むと、節・尾は頭の真後ろに 0.80 ずつ並び、向き(前 = 頭の方)は進む向き。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bodyFollowsStraightPath(GameTestHelper helper) {
        FodimnesEntity w = offLevel(helper);
        Vec3 dir = new Vec3(1.0D, 0.0D, 0.0D);
        Vec3 p = FAR_ORIGIN;
        start(w, p, yawOf(dir), 0.0F);
        for (int t = 0; t < 80; t++) {
            p = p.add(dir.scale(0.25D));
            driveDir(w, p, dir);
        }
        double s = FodimnesEntity.SPACING * w.getScale();
        for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
            Vec3 want = p.subtract(dir.scale(s * i));
            Vec3 got = w.partCenter(i);
            if (got.distanceTo(want) > 1.0E-3D) {
                helper.fail("部位 " + i + " が頭の道の 0.8×" + i + " の点に無い: " + fmt(got) + "(期待 " + fmt(want) + ")");
            }
            if (w.partForward(i).dot(dir) < 0.9999D) {
                helper.fail("部位 " + i + " の向きが進む向きでない: " + fmt(w.partForward(i)));
            }
        }
        if (Math.abs(w.partCenter(1).distanceTo(w.partCenter(0)) - s) > 1.0E-3D) {
            helper.fail("頭 ↔ 節 1 が 0.8 でない: " + w.partCenter(1).distanceTo(w.partCenter(0)));
        }
        helper.succeed();
    }

    /** 半径 4 の円を回ると、全部の部位が円の上に、弧長 0.80 おきに並ぶ(頭の後ろから)。向きは円の接線。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bodyFollowsCurvedPath(GameTestHelper helper) {
        FodimnesEntity w = offLevel(helper);
        double r = 4.0D;
        double v = 0.2D;
        Vec3 c = FAR_ORIGIN;
        double theta = 0.0D;
        start(w, c.add(r, 0.0D, 0.0D), yawOf(new Vec3(0.0D, 0.0D, 1.0D)), 0.0F);
        for (int t = 0; t < 120; t++) {
            theta += v / r;
            Vec3 p = c.add(r * Math.cos(theta), 0.0D, r * Math.sin(theta));
            driveDir(w, p, new Vec3(-Math.sin(theta), 0.0D, Math.cos(theta)));
        }
        double s = FodimnesEntity.SPACING * w.getScale();
        for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
            double th = theta - s * i / r;
            Vec3 want = c.add(r * Math.cos(th), 0.0D, r * Math.sin(th));
            Vec3 got = w.partCenter(i);
            if (got.distanceTo(want) > 0.02D) {
                helper.fail(String.format(Locale.ROOT, "部位 %d が円の道の弧長 %.2f の点から %.3f ずれた: %s(期待 %s)",
                        i, s * i, got.distanceTo(want), fmt(got), fmt(want)));
            }
            Vec3 tangent = new Vec3(-Math.sin(th), 0.0D, Math.cos(th));
            if (w.partForward(i).dot(tangent) < 0.999D) {
                helper.fail("部位 " + i + " の向きが円の接線でない: " + fmt(w.partForward(i)) + "(期待 " + fmt(tangent) + ")");
            }
        }
        helper.succeed();
    }

    /**
     * 体格を上げても(mobs.json の scale。2026-10-01 にユーザー「サイズが小さすぎる」で上げた)、尾まで頭の道をなぞる。
     * 道の容量・積む間隔・保存の長さは体格に比例する(体格 1 の容量 20 ブロックのままだと、体格 2.5 の体 34 ブロックの後ろ側が
     * 道の終わりからまっすぐ伸びて、頭の開けた穴を通らない)。体格 2.5 で半径 8 の円をゆっくり(0.06 ブロック/tick)回ると、
     * 全部の部位が円の上・弧長 0.8 × 体格 おきに並ぶ。保存して読み込んでも同じ形。
     * 速さ 0.06 は、体格を掛けない作りで 2 tick ごと(0.12 おき = 容量 24 ブロック)に積まれる値(0.05 だと 3 tick ごと = 30 ブロックで見分けにくい)。
     * 体格を掛けない作りでは節 13 から円を外れて落ちる(計算。0.05 のときに節 16 で落ちるのは 2026-10-01 に実際に流して確かめた)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bodyFollowsPathAtLargeScale(GameTestHelper helper) {
        FodimnesEntity w = offLevel(helper);
        w.getAttribute(Attributes.SCALE).setBaseValue(2.5D);
        w.refreshDimensions();
        double r = 8.0D;
        double v = 0.06D;
        Vec3 c = FAR_ORIGIN;
        double theta = 0.0D;
        start(w, c.add(r, 0.0D, 0.0D), yawOf(new Vec3(0.0D, 0.0D, 1.0D)), 0.0F);
        double s = FodimnesEntity.SPACING * w.getScale();
        int ticks = (int) Math.ceil(s * (FodimnesEntity.PARTS + 2) * 1.5D / v);
        for (int t = 0; t < ticks; t++) {
            theta += v / r;
            Vec3 p = c.add(r * Math.cos(theta), 0.0D, r * Math.sin(theta));
            driveDir(w, p, new Vec3(-Math.sin(theta), 0.0D, Math.cos(theta)));
        }
        for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
            double th = theta - s * i / r;
            Vec3 want = c.add(r * Math.cos(th), 0.0D, r * Math.sin(th));
            Vec3 got = w.partCenter(i);
            if (got.distanceTo(want) > 0.05D) {
                helper.fail(String.format(Locale.ROOT, "体格 %.1f: 部位 %d が円の道の弧長 %.2f の点から %.3f ずれた: %s(期待 %s)",
                        w.getScale(), i, s * i, got.distanceTo(want), fmt(got), fmt(want)));
            }
        }
        CompoundTag tag = new CompoundTag();
        w.saveWithoutId(tag);
        FodimnesEntity loaded = offLevel(helper);
        loaded.load(tag);
        if (Math.abs(loaded.getScale() - w.getScale()) > 1.0E-6F) {
            helper.fail("読み込んだ個体の体格が違う: " + loaded.getScale() + "(保存 " + w.getScale() + ")");
        }
        compareBodies(helper, "体格 2.5 の保存 → 読み込み", w.solvedPartPositions(), loaded.solvedPartPositions());
        helper.succeed();
    }

    /**
     * 横に進んでから半径 1.5 で曲がって真下へ潜ると、部位は頭の通った道(縦 → 四分円 → 横)をそのままなぞる
     * (= 全部の節が頭の開けた穴を通る)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bodyFollowsVerticalDive(GameTestHelper helper) {
        FodimnesEntity w = offLevel(helper);
        DivePath path = new DivePath(FAR_ORIGIN, 16.0D, 1.5D);
        double v = 0.2D;
        double end = path.horizontal + path.arcLength() + 6.0D;
        start(w, path.at(0.0D), yawOf(path.dir(0.0D)), 0.0F);
        double sHead = 0.0D;
        while (sHead + v <= end + 1.0E-9D) {
            sHead += v;
            driveDir(w, path.at(sHead), path.dir(sHead));
        }
        double s = FodimnesEntity.SPACING * w.getScale();
        for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
            Vec3 want = path.at(sHead - s * i);
            Vec3 got = w.partCenter(i);
            if (got.distanceTo(want) > 0.03D) {
                helper.fail(String.format(Locale.ROOT, "部位 %d が頭の道(縦に潜る)から %.3f ずれた: %s(期待 %s)",
                        i, got.distanceTo(want), fmt(got), fmt(want)));
            }
        }
        // 頭は真下を向いている(yaw に意味が無い所)
        if (w.getXRot() < 89.0F) {
            helper.fail("頭が真下を向いていない: pitch " + w.getXRot());
        }
        helper.succeed();
    }

    /**
     * 履歴(リングバッファ)は<b>どんなに遅く動いても体の長さを覆う</b>(SPEC「長さは 16 × 0.8 ブロックを最も遅い速さでも覆える数」)、
     * <b>止まっても体が縮まない</b>(待ち伏せの間)。半径 4 の円を 1 tick 0.01 ブロック(地中の速さの 1/20)で 15 ブロック回ると、
     * 全部の部位が円の上の弧長 0.8 × 番号の点に並ぶ。そのまま 400 tick 止まっても同じ位置。
     * (使い捨ての main で確かめた値: ずれ 0.00014。道を 1 tick に 1 点ずつ積んで 200 点で溢れる作りだと、2 ブロックより後ろが
     * 接線の向きへまっすぐ伸びて 13 ブロックずれ、止まると 5.8 ブロック縮む。)
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bodyKeepsShapeWhenSlowOrStopped(GameTestHelper helper) {
        FodimnesEntity w = offLevel(helper);
        double r = 4.0D;
        double v = 0.01D;
        Vec3 c = FAR_ORIGIN;
        double theta = 0.0D;
        start(w, c.add(r, 0.0D, 0.0D), yawOf(new Vec3(0.0D, 0.0D, 1.0D)), 0.0F);
        Vec3 p = c.add(r, 0.0D, 0.0D);
        for (int t = 0; t < 1500; t++) {
            theta += v / r;
            p = c.add(r * Math.cos(theta), 0.0D, r * Math.sin(theta));
            driveDir(w, p, new Vec3(-Math.sin(theta), 0.0D, Math.cos(theta)));
        }
        double s = FodimnesEntity.SPACING * w.getScale();
        for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
            double th = theta - s * i / r;
            Vec3 want = c.add(r * Math.cos(th), 0.0D, r * Math.sin(th));
            Vec3 got = w.partCenter(i);
            if (got.distanceTo(want) > 0.01D) {
                helper.fail(String.format(Locale.ROOT, "1 tick 0.01 で円を 15 ブロック回った後、部位 %d が円の道の弧長 %.1f の点から %.4f ずれた: %s(期待 %s)。"
                        + "履歴が体の長さを覆っていない", i, s * i, got.distanceTo(want), fmt(got), fmt(want)));
            }
        }
        double[] moving = w.solvedPartPositions();
        for (int t = 0; t < 400; t++) {
            drive(w, p, w.getYRot(), 0.0F);
        }
        double[] stopped = w.solvedPartPositions();
        for (int k = 0; k < moving.length; k++) {
            if (Math.abs(moving[k] - stopped[k]) > 1.0E-6D) {
                helper.fail("止まって 400 tick で部位 " + (k / 3 + 1) + " が動いた(体が縮んだ): " + (stopped[k] - moving[k]));
            }
        }
        helper.succeed();
    }

    /** 横(+X)に horizontal 進み、半径 radius の四分円で下へ曲がり、真下へ進む道(弧長 s でのパラメータ表示)。 */
    private record DivePath(Vec3 origin, double horizontal, double radius) {
        double arcLength() {
            return Math.PI * 0.5D * this.radius;
        }

        Vec3 at(double s) {
            if (s <= this.horizontal) {
                return this.origin.add(s, 0.0D, 0.0D);
            }
            double a = s - this.horizontal;
            if (a <= arcLength()) {
                double phi = a / this.radius;
                return this.origin.add(this.horizontal + this.radius * Math.sin(phi), -this.radius + this.radius * Math.cos(phi), 0.0D);
            }
            double d = a - arcLength();
            return this.origin.add(this.horizontal + this.radius, -this.radius - d, 0.0D);
        }

        Vec3 dir(double s) {
            if (s <= this.horizontal) {
                return new Vec3(1.0D, 0.0D, 0.0D);
            }
            double a = s - this.horizontal;
            if (a <= arcLength()) {
                double phi = a / this.radius;
                return new Vec3(Math.cos(phi), -Math.sin(phi), 0.0D);
            }
            return new Vec3(0.0D, -1.0D, 0.0D);
        }
    }

    /**
     * スプラインの弧長の等間隔(計算の関数を直接): 半径 3 の円の上の点を<b>ばらばらの間隔</b>(0.15〜1.0 ブロック)で渡しても、
     * 出てくる点は円の上に、弧長 0.80 おきに並ぶ(点の置き方に左右されない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void splineSpacingIsEvenInArcLength(GameTestHelper helper) {
        double r = 3.0D;
        double[] gaps = {0.05D, 0.30D, 0.12D, 0.25D, 0.08D, 0.20D, 0.33D, 0.10D, 0.18D, 0.06D, 0.28D};
        int cap = 200;
        double[] px = new double[cap];
        double[] py = new double[cap];
        double[] pz = new double[cap];
        float[] pr = new float[cap];
        int n = 0;
        double ang = 0.0D;
        int g = 0;
        while (n < cap && ang > -6.0D) {
            px[n] = r * Math.cos(ang);
            py[n] = 5.0D;
            pz[n] = r * Math.sin(ang);
            pr[n] = (float) (-ang * 10.0D);   // roll も弧に沿って変える(同じ遅れで読むことを見る)
            n++;
            ang -= gaps[g++ % gaps.length];
        }
        int count = FodimnesEntity.PARTS;
        double spacing = FodimnesEntity.SPACING;
        double[] pos = new double[3 * count];
        double[] fwd = new double[3 * count];
        float[] roll = new float[count];
        // 道の後ろ向き(道が 2 点以上あれば使われない)
        FodimnesBody.solvePoints(px, py, pz, pr, n, 0.0D, 0.0D, -1.0D, spacing, count, new FodimnesBody.Path(4096), pos, fwd, roll);
        // 隣どうしの弦はどれも同じ(弧長 0.8 の弦 = 2R sin(0.4 / R))。粗い点(最大 1 ブロック)をつないだスプラインの膨らみの分だけ揺れる
        double chord = 2.0D * r * Math.sin(spacing / (2.0D * r));
        for (int i = 1; i < count; i++) {
            double dx = pos[3 * i] - pos[3 * i - 3];
            double dy = pos[3 * i + 1] - pos[3 * i - 2];
            double dz = pos[3 * i + 2] - pos[3 * i - 1];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (Math.abs(d - chord) > 0.01D) {
                helper.fail(String.format(Locale.ROOT, "点 %d と %d の間が %.4f(期待 %.4f)。弧長で等間隔になっていない", i, i + 1, d, chord));
            }
        }
        for (int i = 0; i < count; i++) {
            double x = pos[3 * i];
            double z = pos[3 * i + 2];
            double rad = Math.sqrt(x * x + z * z);
            if (Math.abs(rad - r) > 0.04D) {
                helper.fail(String.format(Locale.ROOT, "点 %d が円から %.4f 外れた(スプラインが円をなぞっていない)", i + 1, rad - r));
            }
            double want = spacing * (i + 1);
            // 頭(角度 0)から後ろへ測った弧長。体は半周を超えるので atan2 の折り返しを期待の角度の近くへ戻す
            double th = Math.atan2(z, x);
            double wantTh = -want / r;
            while (th - wantTh > Math.PI) {
                th -= 2.0D * Math.PI;
            }
            while (th - wantTh < -Math.PI) {
                th += 2.0D * Math.PI;
            }
            double arc = -th * r;
            if (Math.abs(arc - want) > 0.04D) {
                helper.fail(String.format(Locale.ROOT, "点 %d の弧長が %.4f(期待 %.2f)。弧長で等間隔になっていない", i + 1, arc, want));
            }
            // roll は同じ弧長の履歴の値(角度 × 10 度)
            double wantRoll = arc / r * 10.0D;
            if (Math.abs(roll[i] - wantRoll) > 0.3D) {
                helper.fail(String.format(Locale.ROOT, "点 %d の roll が %.2f(期待 %.2f)。位置と同じ遅れで読めていない", i + 1, roll[i], wantRoll));
            }
        }
        helper.succeed();
    }

    // ================================================================== 向き(ユーザー指定: 回転最小化フレーム)

    /**
     * 回転最小化フレーム(関数を直接): 水平(+Z)から真上へ曲がる道に沿って向きを運ぶと、どの部位の前も道の向きに一致し、
     * 曲がりの面に垂直な「右」はずっと同じ(= ねじれない)。真上を向いたまま yaw が変わっても頭の向きは変わらない。
     * 真上を越えて反対側へ倒れても、1 歩ごとの回転は道の曲がりの分だけ(くるりと回らない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void rotationMinimizingFrameDoesNotTwistStraightUp(GameTestHelper helper) {
        int n = 40;
        Vector3f[] dirs = new Vector3f[n];
        Quaternionf[] out = new Quaternionf[n];
        for (int i = 0; i < n; i++) {
            double phi = Math.min(90.0D, i * 4.0D) * Math.PI / 180.0D;   // 0°(+Z)→ 90°(真上)、その先は真上のまま
            dirs[i] = new Vector3f(0.0F, (float) Math.sin(phi), (float) Math.cos(phi));
            out[i] = new Quaternionf();
        }
        Quaternionf head = FodimnesFrames.upright(0.0F, 0.0F, new Quaternionf());
        Vector3f headDir = new Vector3f(0.0F, 0.0F, 1.0F);
        FodimnesFrames.propagate(head, headDir, dirs, out);
        Vector3f right0 = head.transform(new Vector3f(1.0F, 0.0F, 0.0F));
        for (int i = 0; i < n; i++) {
            Vector3f f = out[i].transform(new Vector3f(FodimnesFrames.MODEL_FORWARD));
            if (f.distance(dirs[i]) > 1.0E-4F) {
                helper.fail("部位 " + i + " の前が道の向きでない: " + f + "(期待 " + dirs[i] + ")");
            }
            Vector3f right = out[i].transform(new Vector3f(1.0F, 0.0F, 0.0F));
            if (right.dot(right0) < 0.9999F) {
                helper.fail("部位 " + i + " でねじれた(右の向きが変わった): " + right + "(はじめ " + right0 + ")");
            }
        }
        Vector3f upTop = out[n - 1].transform(new Vector3f(FodimnesFrames.MODEL_UP));
        if (upTop.z > -0.999F) {
            helper.fail("真上を向いた部位の背が後ろ(−Z)を向いていない: " + upTop);
        }

        // 真上を向いたまま yaw だけ変わる(真上では yaw に意味が無い): 向きは変わらない
        Quaternionf q = new Quaternionf(out[n - 1]);
        Quaternionf before = new Quaternionf(q);
        Vector3f upDir = new Vector3f(0.0F, 1.0F, 0.0F);
        for (int k = 1; k <= 9; k++) {
            FodimnesFrames.advanceHead(q, upDir, upDir, k * 20.0F, -90.0F, 0.15F);
        }
        if (FodimnesFrames.angleBetween(before, q) > 0.01F) {
            helper.fail("真上を向いたまま yaw が変わっただけで頭が " + FodimnesFrames.angleBetween(before, q) + " 度回った");
        }

        // 真上を越えて反対側へ倒れる(−30° → +30°、2° ずつ): 1 歩の回転は 2° だけ、背はひっくり返らない
        Vector3f prev = new Vector3f(0.0F, (float) Math.cos(Math.toRadians(-30.0D)), (float) Math.sin(Math.toRadians(-30.0D)));
        Quaternionf a = FodimnesFrames.fromForwardUp(prev, new Vector3f(0.0F, 0.0F, 1.0F), new Quaternionf());
        for (int k = 1; k <= 30; k++) {
            double th = Math.toRadians(-30.0D + k * 2.0D);
            Vector3f d = new Vector3f(0.0F, (float) Math.cos(th), (float) Math.sin(th));
            Quaternionf was = new Quaternionf(a);
            Vector3f upWas = was.transform(new Vector3f(FodimnesFrames.MODEL_UP));
            FodimnesFrames.transport(a, prev, d);
            a.normalize();
            float step = FodimnesFrames.angleBetween(was, a);
            float turn = (float) Math.toDegrees(Math.acos(Math.min(1.0F, prev.dot(d))));
            if (step > turn + 0.05F) {
                helper.fail(String.format(Locale.ROOT, "真上を越える所で 1 歩に %.2f 度回った(道の曲がりは %.2f 度)", step, turn));
            }
            Vector3f upNow = a.transform(new Vector3f(FodimnesFrames.MODEL_UP));
            if (upNow.dot(upWas) < (float) Math.cos(Math.toRadians(3.0D))) {
                helper.fail("真上を越える所で背がひっくり返った: " + upWas + " → " + upNow);
            }
            prev.set(d);
        }
        helper.succeed();
    }

    // ================================================================== ひねり(ユーザー指定: バンクと遅れ)

    /**
     * 右へ曲がり始めると頭が右(曲がりの内側)へ傾き、ひねりは頭から尾へ遅れて伝わる(まだ直線の上にいる尾は 0)。
     * 曲がり続けると、全部の部位が同じ傾き(旋回の速さ × 倍率)に揃う。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void bankLeansIntoTurnAndTwistTravelsTailward(GameTestHelper helper) {
        FodimnesEntity w = offLevel(helper);
        double v = 0.3D;
        float yaw = -90.0F;   // +X へ
        Vec3 p = FAR_ORIGIN;
        start(w, p, yaw, 0.0F);
        for (int t = 0; t < 60; t++) {
            p = p.add(dirOfYaw(yaw).scale(v));
            drive(w, p, yaw, 0.0F);
        }
        // 右へ 4 度/tick で 8 tick 曲がる(yaw が増える = 右回り)
        for (int t = 0; t < 8; t++) {
            yaw += 4.0F;
            p = p.add(dirOfYaw(yaw).scale(v));
            drive(w, p, yaw, 0.0F);
        }
        float head = w.partRoll(0);
        if (!(head > 5.0F && head <= 35.0F)) {
            helper.fail("曲がり始めて 8 tick の頭の傾きが " + head + " 度(5〜35 のはず)");
        }
        for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
            if (w.partRoll(i) > w.partRoll(i - 1) + 1.0E-3F) {
                helper.fail("ひねりが頭から尾へ順に伝わっていない: 部位 " + (i - 1) + " = " + w.partRoll(i - 1) + "、部位 " + i + " = " + w.partRoll(i));
            }
        }
        if (w.partRoll(1) < 1.0F || Math.abs(w.partRoll(4)) > 1.0E-3F || Math.abs(w.partRoll(FodimnesEntity.PARTS)) > 1.0E-3F) {
            helper.fail(String.format(Locale.ROOT, "遅れの形が違う: 節 1 = %.2f(> 1 のはず)、節 4 = %.3f・尾 = %.3f(まだ直線の上 = 0 のはず)",
                    w.partRoll(1), w.partRoll(4), w.partRoll(FodimnesEntity.PARTS)));
        }
        // 内側へ傾く: 背(roll を掛けた上)が右(曲がりの中心の側)を向く
        Vec3 dir = dirOfYaw(yaw);
        Vec3 right = new Vec3(-dir.z, 0.0D, dir.x);
        Quaternionf q = FodimnesFrames.applyRoll(FodimnesFrames.upright(yaw, 0.0F, new Quaternionf()), head);
        Vector3f up = q.transform(new Vector3f(FodimnesFrames.MODEL_UP));
        if (up.x * right.x + up.z * right.z < 0.05D) {
            helper.fail("右へ曲がっているのに背が右(内側)へ傾いていない: 背 " + up + "、右 " + fmt(right));
        }
        // 曲がり続ける(60 tick = 18 ブロック、体 13.6 が全部円の上に乗る): どの部位も同じ傾き
        for (int t = 0; t < 60; t++) {
            yaw += 4.0F;
            p = p.add(dirOfYaw(yaw).scale(v));
            drive(w, p, yaw, 0.0F);
        }
        float steady = w.partRoll(0);
        for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
            if (Math.abs(w.partRoll(i) - steady) > 0.5F) {
                helper.fail("曲がり続けても部位 " + i + " の傾き " + w.partRoll(i) + " が頭 " + steady + " に揃わない");
            }
        }
        helper.succeed();
    }

    private static Vec3 dirOfYaw(float yaw) {
        double r = Math.toRadians(yaw);
        return new Vec3(-Math.sin(r), 0.0D, Math.cos(r));
    }

    // ================================================================== 当たり判定

    /**
     * 節・尾の箱は部位の中心にあり、長さ 0.8(間隔)で隣とすき間なく並び、太さは先細り(尾がいちばん細い)。
     * 部位の id は本体の続き(クライアントの {@code setId} と揃う)。部位は保存されない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void partHitboxesCoverTaperedTube(GameTestHelper helper) {
        FodimnesEntity w = offLevel(helper);
        Vec3 dir = new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 p = FAR_ORIGIN;
        start(w, p, yawOf(dir), 0.0F);
        for (int t = 0; t < 70; t++) {
            p = p.add(dir.scale(0.25D));
            driveDir(w, p, dir);
        }
        double s = FodimnesEntity.SPACING * w.getScale();
        double prevMinZ = w.getBoundingBox().minZ;
        double prevHeight = Double.MAX_VALUE;
        for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
            FodimnesPart part = w.part(i);
            AABB box = part.getBoundingBox();
            Vec3 c = w.partCenter(i);
            if (box.getCenter().distanceTo(c) > 1.0E-6D) {
                helper.fail("部位 " + i + " の箱が部位の中心に無い: " + fmt(box.getCenter()) + "(中心 " + fmt(c) + ")");
            }
            if (Math.abs(box.getZsize() - s) > 1.0E-6D) {
                helper.fail("部位 " + i + " の箱の長さが間隔 0.8 でない: " + box.getZsize());
            }
            double wantThick = part.getDimensions(Pose.STANDING).height() * w.getScale();
            if (Math.abs(box.getYsize() - wantThick) > 1.0E-6D || Math.abs(box.getXsize() - wantThick) > 1.0E-6D) {
                helper.fail("部位 " + i + " の箱の太さが " + box.getXsize() + " × " + box.getYsize() + "(期待 " + wantThick + ")");
            }
            if (box.getYsize() > prevHeight + 1.0E-9D) {
                helper.fail("部位 " + i + " が前の部位より太い(尾へ細くならない)");
            }
            prevHeight = box.getYsize();
            if (i > 1 && Math.abs(box.maxZ - prevMinZ) > 1.0E-6D) {
                helper.fail("部位 " + (i - 1) + " と " + i + " の箱の間にすき間・重なり: " + (prevMinZ - box.maxZ));
            }
            prevMinZ = box.minZ;
            if (part.shouldBeSaved()) {
                helper.fail("部位が保存される");
            }
            if (part.getId() != w.getId() + i) {
                helper.fail("部位 " + i + " の id が本体の続きでない: " + part.getId() + "(本体 " + w.getId() + ")");
            }
        }
        float tail = FodimnesEntity.partTaper(FodimnesEntity.PARTS);
        if (!(tail < FodimnesEntity.partTaper(1) && Math.abs(tail - FodimnesEntity.partTaper(FodimnesEntity.SEGMENTS)) < 1.0E-6F)) {
            helper.fail("尾の倍率が最後の節と同じでない・細くない: " + tail);
        }
        helper.succeed();
    }

    /**
     * どの部位を殴っても本体の共通の体力が減る(頭を同じだけ殴った個体と同じだけ減る)。同じ攻撃が同じ tick に頭と複数の部位に当たっても、
     * いちばん大きい 1 回分だけ(爆発のような多重ヒット)。世界の部位の一覧から id で引ける。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void anyPartHitDamagesSharedHealthOnce(GameTestHelper helper) {
        FodimnesEntity partHit = airWorm(helper, 1.5D);
        FodimnesEntity tailHit = airWorm(helper, 3.5D);
        FodimnesEntity headHit = airWorm(helper, 5.5D);
        FodimnesEntity multi = airWorm(helper, 2.5D);
        FodimnesEntity control = airWorm(helper, 4.5D);
        helper.runAfterDelay(2L, () -> {
            for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
                if (helper.getLevel().getEntityOrPart(partHit.getId() + i) != partHit.part(i)) {
                    helper.fail("世界の部位の一覧から部位 " + i + " を id で引けない(殴れない)");
                }
            }
            Object asObject = partHit.part(9);
            if (!partHit.part(9).isPickable() || asObject instanceof LivingEntity) {
                helper.fail("部位が狙えない・部位が体力を持っている");
            }
            float amount = 6.0F;
            boolean a = partHit.part(9).hurt(generic(helper), amount);
            boolean b = tailHit.part(FodimnesEntity.PARTS).hurt(generic(helper), amount);
            headHit.hurt(generic(helper), amount);
            float lossPart = partHit.getMaxHealth() - partHit.getHealth();
            float lossTail = tailHit.getMaxHealth() - tailHit.getHealth();
            float lossHead = headHit.getMaxHealth() - headHit.getHealth();
            if (!a || !b || lossHead <= 0.0F) {
                helper.fail("殴っても当たらない(節 " + a + "・尾 " + b + "・頭の減り " + lossHead + ")");
            }
            if (Math.abs(lossPart - lossHead) > 1.0E-3F || Math.abs(lossTail - lossHead) > 1.0E-3F) {
                helper.fail("部位を殴ったときの減りが頭と違う: 節 " + lossPart + "・尾 " + lossTail + "・頭 " + lossHead);
            }
            // 同じ攻撃(同じ DamageSource)が同じ tick に頭と 3 つの部位へ: 大きい 1 回分(5)だけ
            DamageSource once = generic(helper);
            multi.part(2).hurt(once, 3.0F);
            multi.part(5).hurt(once, 5.0F);
            multi.hurt(once, 4.0F);
            multi.part(12).hurt(once, 2.0F);
            control.hurt(generic(helper), 5.0F);
            float lossMulti = multi.getMaxHealth() - multi.getHealth();
            float lossControl = control.getMaxHealth() - control.getHealth();
            if (lossControl <= 0.0F || Math.abs(lossMulti - lossControl) > 1.0E-3F) {
                helper.fail("同じ攻撃が 4 か所に当たって " + lossMulti + " 減った(1 回分 = " + lossControl + " のはず)");
            }
            helper.succeed();
        });
    }

    private static DamageSource generic(GameTestHelper helper) {
        // 毎回新しい DamageSource(同じ種類でも別の攻撃)。generic は防具を無視するので減りがそのまま比べられる
        return helper.getLevel().damageSources().source(DamageTypes.GENERIC);
    }

    /** 空中で動かない個体(NoAI)。頭は下を向き、体は真上へ伸びる(テスト場の天井を抜ける。ほかのテスト場へは向かない)。 */
    private static FodimnesEntity airWorm(GameTestHelper helper, double x) {
        FodimnesEntity w = spawnWorm(helper, new Vec3(x, 2.6D, 3.5D), 0.0F, 90.0F);
        w.setNoAi(true);
        w.resetBodyStraight();
        return w;
    }

    /** 爆発 1 回は、頭と何か所の部位に当たっても 1 回分(爆発 1 つから受けうる最大の 1 回分を超えない)。ブロックは壊れない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void explosionCountsOnce(GameTestHelper helper) {
        FodimnesEntity w = airWorm(helper, 3.5D);
        helper.runAfterDelay(2L, () -> {
            Vec3 at = helper.absoluteVec(new Vec3(4.5D, 4.2D, 3.5D));
            float radius = 2.0F;
            double reach = radius * 2.0D;
            int inRange = w.distanceToSqr(at) < reach * reach ? 1 : 0;
            for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
                if (w.partCenter(i).distanceTo(at) < reach - 0.5D) {
                    inRange++;
                }
            }
            if (inRange < 4) {
                helper.fail("テストの前提が崩れた: 爆発の届く部位が " + inRange + " か所しか無い");
            }
            helper.getLevel().explode(null, at.x, at.y, at.z, radius, Level.ExplosionInteraction.NONE);
            float loss = w.getMaxHealth() - w.getHealth();
            // バニラの爆発の 1 回の最大 = (1 + 1) / 2 × 7 × (半径 × 2) + 1(真ん中・丸見え、防具の前)
            float maxOnce = 7.0F * radius * 2.0F + 1.0F;
            if (loss <= 0.0F) {
                helper.fail("爆発が当たっていない");
            }
            if (loss > maxOnce + 1.0E-3F) {
                helper.fail("爆発 1 回で " + loss + " 減った(" + inRange + " か所に当たって足し合わされた。1 回分の最大は " + maxOnce + ")");
            }
            helper.succeed();
        });
    }

    // ================================================================== 地形(ユーザー指定: ブロックを壊さない)

    /**
     * 岩の中に置いた個体は、世界に入った時点で押し出されない(SpawnFit の埋まり直し)。岩の中で眠っても窒息しない。
     * 体は真上へ置き直し({@link #layBodyUp})、石の上の空き・天井のバリア(岩でない所)へ出るので、体を引き込む SETTLE は止めておく
     * (SETTLE は {@link #surfaceWormPullsWholeBodyUnderground})。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void staysInRockWithoutSuffocating(GameTestHelper helper) {
        fillStone(helper, 2, 6);
        Vec3 feetRel = new Vec3(3.5D, 3.2D, 3.5D);
        FodimnesEntity w = pinScale(helper.spawn(ModEntities.FODIMNES.get(), feetRel));
        layBodyUp(w);
        w.holdStillForTests(100);
        Vec3 want = helper.absoluteVec(feetRel);
        if (w.position().distanceTo(want) > 1.0E-6D) {
            helper.fail("世界に入った時点で岩の中から押し出された: ずれ " + fmt(w.position().subtract(want)));
        }
        helper.runAfterDelay(30L, () -> {
            if (w.position().distanceTo(want) > 1.0E-6D) {
                helper.fail("音も無いのに動いた(眠って聞いているはず): " + fmt(w.position().subtract(want)));
            }
            if (w.getHealth() < w.getMaxHealth() || w.isInWall()) {
                helper.fail("岩の中で窒息した(体力 " + w.getHealth() + "、isInWall " + w.isInWall() + ")");
            }
            helper.succeed();
        });
    }

    /** 岩の中を音のした方へ進んでも、ブロックは 1 つも壊さず、窒息もしない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void burrowsWithoutBreakingBlocksOrSuffocating(GameTestHelper helper) {
        fillStone(helper, 2, 6);
        Vec3 from = helper.absoluteVec(new Vec3(1.5D, 3.5D, 3.5D));
        Vec3 noise = helper.absoluteVec(new Vec3(5.5D, 3.5D, 3.5D));
        FodimnesEntity w = spawnWorm(helper, new Vec3(1.5D, 3.5D, 3.5D), yawOf(noise.subtract(from)), 0.0F);
        w.hearForTests(noise, null);
        helper.runAfterDelay(60L, () -> {
            double moved = w.axisPoint().distanceTo(from);
            if (moved < 1.0D) {
                helper.fail("音のした方へ進んでいない(動いた距離 " + moved + ")");
            }
            if (w.axisPoint().distanceTo(noise) > from.distanceTo(noise) - 1.0D) {
                helper.fail("音から遠ざかった");
            }
            int missing = missingStone(helper, 2, 6, pos -> false);
            if (missing > 0) {
                helper.fail("ブロックを " + missing + " 個壊した(壊さないはず)");
            }
            if (w.getHealth() < w.getMaxHealth() || w.isInWall()) {
                helper.fail("岩の中を進んで窒息した(体力 " + w.getHealth() + ")");
            }
            if (!w.isHeadInEarth()) {
                helper.fail("地中を進まずに外へ出た");
            }
            helper.succeed();
        });
    }

    /**
     * 地表に出した個体(スポーンエッグ・/summon)は、下の地面へ潜り(DIVE)、体を全部頭の開けた穴から引き込む(SETTLE)。
     * 2026-10-01 の runClient でユーザー「地面に潜れてない」: 前は頭が岩に入った所で眠り、体の 13 ブロックが地表に残っていた。
     * 体は真上へ伸ばして置く(ほかのテスト場へ向かない)。ブロックは 1 つも壊さない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void surfaceWormPullsWholeBodyUnderground(GameTestHelper helper) {
        fillStone(helper, 2, 6);
        FodimnesEntity w = spawnWorm(helper, new Vec3(3.5D, 7.0D + FodimnesEntity.AXIS_Y, 3.5D), 0.0F, 90.0F);
        double top = helper.absoluteVec(new Vec3(0.0D, 7.0D, 0.0D)).y;
        helper.succeedWhen(() -> {
            if (!w.isHeadInEarth()) {
                helper.fail("頭が地中に居ない(" + w.getMode() + ")");
            }
            for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
                double above = w.partCenter(i).y - top;
                if (above > -0.3D) {
                    helper.fail(String.format(Locale.ROOT, "部位 %d がまだ穴の外(地表から %.2f、%s)", i, above, w.getMode()));
                }
            }
            if (missingStone(helper, 2, 6, pos -> false) > 0) {
                helper.fail("ブロックを壊した");
            }
        });
    }

    /** 洞窟のテスト場: 床 y 1〜2(石。上面 3.0)・空き y 3〜5(高さ 3)・天井 y 6(石。ceiling のとき)。 */
    private static void buildCave(GameTestHelper helper, boolean ceiling) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
                for (int y = 3; y <= 5; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
                helper.setBlock(new BlockPos(x, 6, z), ceiling ? Blocks.STONE : Blocks.AIR);
            }
        }
    }

    /**
     * うろついていて洞窟(床・高さ 3 の空き・岩の天井)を見つけると、床の下から飛び出してアーチを描き、反対側の床へ潜る
     * (2026-10-01 夜のユーザー指定「ちょくちょく洞窟内に飛び出してアーチを描くようにジャンプ」)。頭は床より 1.5 以上高く上がり、
     * 最後は地中に戻る。ブロックは 1 つも壊さない。テスト場の対角線の向き(床の上 6.5 ブロック)に飛ぶ。
     * 天井の下の空の光が落ち着くのを待ってから(20 tick)うろつかせる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void roamBreachesThroughCaveInArch(GameTestHelper helper) {
        buildCave(helper, true);
        Vec3 dir = new Vec3(1.0D, 0.0D, 1.0D).normalize();
        FodimnesEntity w = spawnWorm(helper, new Vec3(0.6D, 2.2D, 0.6D), yawOf(dir), 0.0F);
        w.holdStillForTests(1000);
        double floorTop = helper.absoluteVec(new Vec3(0.0D, 3.0D, 0.0D)).y;
        double[] maxRise = {Double.NEGATIVE_INFINITY};
        boolean[] arced = {false};
        helper.onEachTick(() -> {
            maxRise[0] = Math.max(maxRise[0], w.axisPoint().y - floorTop);
            arced[0] |= w.isBreachingArc();
        });
        helper.runAfterDelay(20L, () -> {
            if (!w.canBreachForTests(dir)) {
                helper.fail("前提: 天井のある洞窟なのにアーチを組めない");
                return;
            }
            w.startRoamForTests(dir);
            helper.succeedWhen(() -> {
                if (!arced[0]) {
                    helper.fail("アーチに入っていない(" + w.getMode() + ")");
                }
                if (maxRise[0] < 1.5D) {
                    helper.fail(String.format(Locale.ROOT, "頭が床から %.2f しか上がっていない", maxRise[0]));
                }
                if (w.getMode() == FodimnesEntity.Mode.BREACH) {
                    helper.fail("まだアーチの途中");
                }
                if (!w.isHeadInEarth()) {
                    helper.fail("反対側の床へ潜っていない(" + w.getMode() + ")");
                }
                if (missingStone(helper, 1, 2, pos -> false) + missingStone(helper, 6, 6, pos -> false) > 0) {
                    helper.fail("ブロックを壊した");
                }
            });
        });
    }

    /**
     * アーチは洞窟の中だけ: 天井の無い所(空が見える)ではアーチを組まない。床が溶岩で覆われていても組まない(液体の上・中は通らない)。
     * 同じ場所で、天井あり → 天井なし → 天井あり + 溶岩の床 の順に見る(NoAI で動かさない。光が落ち着くまで 20 tick ずつ待つ)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void breachNeedsCaveCeilingAndDryFloor(GameTestHelper helper) {
        buildCave(helper, true);
        Vec3 dir = new Vec3(1.0D, 0.0D, 1.0D).normalize();
        FodimnesEntity w = spawnWorm(helper, new Vec3(0.6D, 2.2D, 0.6D), yawOf(dir), 0.0F);
        w.setNoAi(true);
        helper.runAfterDelay(20L, () -> {
            if (!w.canBreachForTests(dir)) {
                helper.fail("前提: 天井のある洞窟なのにアーチを組めない");
                return;
            }
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    helper.setBlock(new BlockPos(x, 6, z), Blocks.AIR);
                }
            }
            helper.runAfterDelay(20L, () -> {
                if (w.canBreachForTests(dir)) {
                    helper.fail("天井の無い所(空が見える)でアーチを組んだ");
                    return;
                }
                for (int x = 0; x <= 6; x++) {
                    for (int z = 0; z <= 6; z++) {
                        helper.setBlock(new BlockPos(x, 6, z), Blocks.STONE);
                        helper.setBlock(new BlockPos(x, 3, z), Blocks.LAVA);
                    }
                }
                helper.runAfterDelay(20L, () -> {
                    if (w.canBreachForTests(dir)) {
                        helper.fail("溶岩で覆われた床からアーチを組んだ");
                        return;
                    }
                    helper.succeed();
                });
            });
        });
    }

    /** 常に動き続ける(10-02 ユーザー指定): 岩の中に置いて何も聞かせなくても、一息ついたら動き出し、80 tick で 2 ブロック以上進む。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void keepsMovingWithoutNoise(GameTestHelper helper) {
        fillStone(helper, 2, 6);
        FodimnesEntity w = spawnWorm(helper, new Vec3(3.5D, 4.0D, 3.5D), -90.0F, 0.0F);
        Vec3 start = w.axisPoint();
        helper.runAfterDelay(80L, () -> {
            double moved = w.axisPoint().distanceTo(start);
            if (moved < 2.0D) {
                helper.fail(String.format(Locale.ROOT, "80 tick で %.2f ブロックしか動いていない(%s)", moved, w.getMode()));
                return;
            }
            if (missingStone(helper, 2, 6, pos -> false) > 0) {
                helper.fail("ブロックを壊した");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 洞窟があれば跳ね続ける(10-02 ユーザー指定「動いている間の 8 割くらいは洞窟の表面を動きながらアーチを描くように飛び出す」):
     * 1 回跳んで潜った先から、続けて次のアーチを描く(テスト場の対角線を行って戻る)。寄り道は止めて数える。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300)
    public static void porpoisesAlongCaveRepeatedly(GameTestHelper helper) {
        buildCave(helper, true);
        Vec3 dir = new Vec3(1.0D, 0.0D, 1.0D).normalize();
        FodimnesEntity w = spawnWorm(helper, new Vec3(0.6D, 2.2D, 0.6D), yawOf(dir), 0.0F);
        w.holdStillForTests(1000);
        int[] arcs = {0};
        boolean[] wasArc = {false};
        helper.onEachTick(() -> {
            boolean arc = w.isBreachingArc();
            if (arc && !wasArc[0]) {
                arcs[0]++;
            }
            wasArc[0] = arc;
        });
        helper.runAfterDelay(20L, () -> {
            w.startRoamForTests(dir);
            helper.succeedWhen(() -> {
                if (arcs[0] < 2) {
                    helper.fail("続けて跳ねない(アーチ " + arcs[0] + " 回、" + w.getMode() + ")");
                }
                if (missingStone(helper, 1, 2, pos -> false) + missingStone(helper, 6, 6, pos -> false) > 0) {
                    helper.fail("ブロックを壊した");
                }
            });
        });
    }

    /**
     * 洞窟の中の相手へ跳びかかる(10-02 ユーザー指定「洞窟内に player が来たら襲いに行く」。形は仮決め): 気付くと床の下へ回り込み、
     * 溜め(床にひび)の後に床から飛び出して、相手の上半身を通るアーチで噛む。的はハスク(気付いたことにする)。ブロックは壊さない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void strikesCavePreyWithArcBite(GameTestHelper helper) {
        buildCave(helper, true);
        fillUnderFloor(helper);
        Vec3 dir = new Vec3(1.0D, 0.0D, 1.0D).normalize();
        FodimnesEntity w = spawnWorm(helper, new Vec3(0.6D, 2.2D, 0.6D), yawOf(dir), 0.0F);
        w.holdStillForTests(1000);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 3.0D, 3.5D));
        husk.setNoAi(true);
        double floorTop = helper.absoluteVec(new Vec3(0.0D, 3.0D, 0.0D)).y;
        double[] maxRise = {Double.NEGATIVE_INFINITY};
        // 気付いてから噛んだ 5 tick 後まで、止まった tick を数える(2026-10-09 ユーザー指定「攻撃の際にも止まらず」)
        boolean[] watching = {false};
        int[] still = {0};
        int[] stillRun = {0};
        int[] worstRun = {0};
        int[] afterBite = {-1};
        Vec3[] last = {w.axisPoint()};
        helper.onEachTick(() -> {
            Vec3 now = w.axisPoint();
            maxRise[0] = Math.max(maxRise[0], now.y - floorTop);
            if (watching[0] && afterBite[0] < 5) {
                if (now.distanceTo(last[0]) < 0.02D) {
                    still[0]++;
                    stillRun[0]++;
                    worstRun[0] = Math.max(worstRun[0], stillRun[0]);
                } else {
                    stillRun[0] = 0;
                }
                if (afterBite[0] >= 0 || husk.getHealth() < husk.getMaxHealth()) {
                    afterBite[0]++;
                }
            }
            last[0] = now;
        });
        helper.runAfterDelay(20L, () -> {
            if (!w.canStrikeForTests(husk)) {
                helper.fail("前提: 開けた洞窟の相手に跳びかかるアーチを組めない");
                return;
            }
            w.noticeForTests(husk);
            watching[0] = true;
            helper.succeedWhen(() -> {
                if (husk.getHealth() >= husk.getMaxHealth() || afterBite[0] < 5) {
                    helper.fail("噛んでいない(" + w.getMode() + "、床からの高さの最大 " + String.format(Locale.ROOT, "%.2f", maxRise[0]) + ")");
                }
                if (maxRise[0] < 1.0D) {
                    helper.fail("床から飛び出していない");
                }
                if (worstRun[0] > 1 || still[0] > 2) {
                    helper.fail("止まった: 動かなかった tick " + still[0] + "(続けて最大 " + worstRun[0] + ")");
                }
                if (w.lastStrikeTellTicksForTests() < 10) {
                    helper.fail("飛び出す前の予兆が短い: " + w.lastStrikeTellTicksForTests() + " tick");
                }
                if (missingStone(helper, 0, 2, pos -> pos.getX() == 0 && pos.getY() == 0 && pos.getZ() == 0)
                        + missingStone(helper, 6, 6, pos -> false) > 0) {
                    helper.fail("ブロックを壊した");
                }
            });
        });
    }

    /**
     * 跳びかかるのは開けた洞窟だけ: 相手の足元の上が 3 マス空いていれば組める。天井を下げて高さ 2(坑道 = ブランチマイニングの通路)にすると組まない
     * (そこでは今までどおり壁から待ち伏せる)。NoAI で動かさない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void strikeNeedsOpenCave(GameTestHelper helper) {
        buildCave(helper, true);
        Vec3 dir = new Vec3(1.0D, 0.0D, 1.0D).normalize();
        FodimnesEntity w = spawnWorm(helper, new Vec3(0.6D, 2.2D, 0.6D), yawOf(dir), 0.0F);
        w.setNoAi(true);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 3.0D, 3.5D));
        husk.setNoAi(true);
        helper.runAfterDelay(20L, () -> {
            if (!w.canStrikeForTests(husk)) {
                helper.fail("高さ 3 の洞窟の相手に跳びかかれない");
                return;
            }
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
                }
            }
            if (w.canStrikeForTests(husk)) {
                helper.fail("高さ 2 の通路の相手に跳びかかろうとした(壁から待ち伏せるはず)");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 洞窟の中の Mob にも自分で気付いて跳びかかる(10-02 ユーザー指定「player 以外の mob も攻撃する」): 天井の下のハスクに気付き、
     * 過繁茂した牛(過繁茂側)・水の中のイカ・防具立て・コウモリには気付かない(空の見える所の決まりはプレイヤーと同じ行で、
     * {@link #noticesSurvivalPlayersInCaveOnly} が見る)。決まりを見た後、Mob に気付くのを有効にして(テストの足場は既定で無効)、
     * 教えなくてもハスクへ跳びかかって噛むことを見る。ブロックは壊さない。
     * <p>イカの水は 1 マスに閉じ込める(2026-10-09、全体の実行でたまに「ROAM、標的 null」で落ちた): 囲わない水源は床の上を流れ、
     * ハスクの足元に届くのが tick 31 —— ワームが初めて気付くのと同じ tick。水の中のハスクには気付き直さない(標的の音 160 tick が tick 191 で切れる)うえ、
     * 跳びかかりのアーチも組めず、床からの待ち伏せになる。その 1 回目(tick 126)を外すと、攻撃の待ち 60 tick が明ける前に見失う(試す機会が 1 回だけ)。
     * 1 回目を外させると毎回この形で落ちることを、同じ場面を 100 個並べて確かめた(外れた理由そのものは再現できていない)。
     * 囲えばハスクは乾いたまま 10 tick ごとに気付き直され、跳びかかりを外しても次がある(140 tick まで外させても 151〜173 で噛む)。
     * 聞こえる距離は 8 に縮める(囮は全部 6 以内。隣のテスト場の採掘の音・Mob は拾いにくくする)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 260)
    public static void noticesCaveMobsAndStrikesThem(GameTestHelper helper) {
        buildCave(helper, true);
        // イカの水: 東・北はテスト場のバリア、下は床。西・南を石で塞ぐ
        helper.setBlock(new BlockPos(5, 3, 0), Blocks.STONE);
        helper.setBlock(new BlockPos(6, 3, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(6, 3, 0), Blocks.WATER);
        Vec3 dir = new Vec3(1.0D, 0.0D, 1.0D).normalize();
        FodimnesEntity w = spawnWorm(helper, new Vec3(0.6D, 2.2D, 0.6D), yawOf(dir), 0.0F);
        w.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(8.0D);
        w.holdStillForTests(1000);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 3.0D, 3.5D));
        husk.setNoAi(true);
        Mob overgrown = helper.spawnWithNoFreeWill(ModEntities.PARASITIZED_COW.get(), new Vec3(1.5D, 3.0D, 4.5D));
        overgrown.setNoAi(true);
        overgrown.setInvulnerable(true);
        Squid squid = helper.spawnWithNoFreeWill(EntityType.SQUID, new Vec3(6.5D, 3.0D, 0.5D));
        squid.setNoAi(true);
        ArmorStand stand = helper.spawn(EntityType.ARMOR_STAND, new Vec3(4.5D, 3.0D, 1.5D));
        Bat bat = helper.spawnWithNoFreeWill(EntityType.BAT, new Vec3(2.5D, 4.2D, 5.5D));
        bat.setNoAi(true);
        helper.runAfterDelay(20L, () -> {
            for (LivingEntity e : java.util.List.of(husk, overgrown, squid, stand, bat)) {
                if (e.distanceToSqr(w.axisPoint()) > w.hearingRange() * w.hearingRange()) {
                    helper.fail("前提: " + e.getType().toShortString() + " が聞こえる距離(" + w.hearingRange() + ")の外(距離の外だから気付かない、になる)");
                    return;
                }
            }
            if (husk.isInWater() || !helper.getLevel().getFluidState(husk.blockPosition()).isEmpty()) {
                helper.fail("前提: ハスクの足元に水が流れて来た(イカの水の囲いが破れている)");
                return;
            }
            if (w.getTarget() != null || w.noiseTimer() != 0) {
                helper.fail("前提: 気付くのを有効にする前に、ほかの相手・音を拾った(標的 " + w.getTarget() + "・音の残り " + w.noiseTimer() + ")");
                return;
            }
            if (!w.canNoticeInCave(husk)) {
                helper.fail("天井の下のハスクに気付かない");
                return;
            }
            if (w.canNoticeInCave(overgrown)) {
                helper.fail("過繁茂した牛(過繁茂側)に気付いた");
                return;
            }
            if (w.canNoticeInCave(squid) || w.canNoticeInCave(stand) || w.canNoticeInCave(bat)) {
                helper.fail("気付かないはずの相手に気付いた: イカ " + w.canNoticeInCave(squid) + "・防具立て " + w.canNoticeInCave(stand)
                        + "・コウモリ " + w.canNoticeInCave(bat));
                return;
            }
            w.setNoticeMobsForTests(true);
            helper.succeedWhen(() -> {
                if (husk.getHealth() >= husk.getMaxHealth()) {
                    helper.fail("自分で気付いて跳びかかっていない(" + w.getMode() + "、標的 " + w.getTarget() + "・音の残り " + w.noiseTimer()
                            + "、ハスクに気付ける " + w.canNoticeInCave(husk) + "・ハスクが水の中 " + husk.isInWater()
                            + "、頭(相対) " + fmt(helper.relativeVec(w.axisPoint())) + ")");
                }
                if (missingStone(helper, 1, 2, pos -> false) + missingStone(helper, 6, 6, pos -> false) > 0
                        || !helper.getBlockState(new BlockPos(5, 3, 0)).is(Blocks.STONE) || !helper.getBlockState(new BlockPos(6, 3, 1)).is(Blocks.STONE)) {
                    helper.fail("ブロックを壊した");
                }
            });
        });
    }

    /**
     * 洞窟の中のプレイヤーに気付く決まり(10-02、仮決め): サバイバルのプレイヤーが空の見えない所に居て、聞こえる距離の中なら気付く。
     * スニークしていれば 1/3 の距離まで。クリエイティブ・空の見える所では気付かない。動き(実際に標的にする)は {@link #strikesCavePreyWithArcBite}。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void noticesSurvivalPlayersInCaveOnly(GameTestHelper helper) {
        buildCave(helper, true);
        FodimnesEntity w = spawnWorm(helper, new Vec3(0.6D, 2.2D, 0.6D), -45.0F, 0.0F);
        w.setNoAi(true);
        Player far = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 farPos = helper.absoluteVec(new Vec3(6.5D, 3.0D, 6.5D));
        far.moveTo(farPos.x, farPos.y, farPos.z);
        Player near = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 nearPos = helper.absoluteVec(new Vec3(1.5D, 3.0D, 1.5D));
        near.moveTo(nearPos.x, nearPos.y, nearPos.z);
        Player creative = helper.makeMockPlayer(GameType.CREATIVE);
        creative.moveTo(nearPos.x, nearPos.y, nearPos.z);
        helper.runAfterDelay(20L, () -> {
            if (!w.canNoticeInCave(far)) {
                helper.fail("洞窟の中の歩くプレイヤー(" + String.format(Locale.ROOT, "%.1f", far.position().distanceTo(w.axisPoint())) + " ブロック)に気付かない");
                return;
            }
            far.setShiftKeyDown(true);
            near.setShiftKeyDown(true);
            if (w.canNoticeInCave(far) || !w.canNoticeInCave(near)) {
                helper.fail("スニークの気付く距離(1/3)が違う: 遠く " + w.canNoticeInCave(far) + "・近く " + w.canNoticeInCave(near));
                return;
            }
            if (w.canNoticeInCave(creative)) {
                helper.fail("クリエイティブのプレイヤーに気付いた");
                return;
            }
            near.setShiftKeyDown(false);
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    helper.setBlock(new BlockPos(x, 6, z), Blocks.AIR);
                }
            }
            helper.runAfterDelay(20L, () -> {
                if (w.canNoticeInCave(near)) {
                    helper.fail("空の見える所のプレイヤーに気付いた");
                    return;
                }
                helper.succeed();
            });
        });
    }

    // ================================================================== 地上への遠出(2026-10-02 ユーザー指定「たまに地上に出て mob を殺しに行く」)

    /**
     * 地表の Mob に気付くのは遠出の間だけ: 空の見える床の上のハスクには、遠出していなければ気付かず、遠出を始めると気付く。
     * 足元 2 マス以内に床の無い相手(宙に浮いたオウム = 飛んでいる相手。跳びかかる床が無い)には遠出の間も気付かない。NoAI で動かさない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 60)
    public static void outingNoticesGroundedSurfaceMobsOnly(GameTestHelper helper) {
        buildCave(helper, false);
        FodimnesEntity w = spawnWorm(helper, new Vec3(0.6D, 2.2D, 0.6D), -45.0F, 0.0F);
        w.setNoAi(true);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 3.0D, 3.5D));
        husk.setNoAi(true);
        Parrot parrot = helper.spawnWithNoFreeWill(EntityType.PARROT, new Vec3(5.5D, 6.0D, 1.5D));
        parrot.setNoAi(true);
        parrot.setNoGravity(true);
        helper.runAfterDelay(20L, () -> {
            if (w.canNoticeInCave(husk)) {
                helper.fail("前提: 空の見える所のハスクを洞窟の中とみなした");
                return;
            }
            if (w.canNoticeMobForTests(husk)) {
                helper.fail("遠出していないのに地表のハスクに気付いた");
                return;
            }
            w.startOutingForTests(husk);
            if (w.getOuting() != FodimnesEntity.Outing.ASCEND) {
                helper.fail("遠出を始めても上り始めない(" + w.getOuting() + ")");
                return;
            }
            if (!w.canNoticeMobForTests(husk)) {
                helper.fail("遠出の間なのに地表のハスクに気付かない");
                return;
            }
            if (w.canNoticeMobForTests(parrot)) {
                helper.fail("足元に床の無い(飛んでいる)オウムに気付いた");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * 空の見える相手へは空の下でも跳びかかる(地表の床から飛び出して上半身を通るアーチで噛む)。うろつきの跳ねは今までどおり洞窟の中だけで、
     * 遠出の地表の段階の跳ねだけが空の下でも組める。天井の無いテスト場(床 y 1〜2)で、的はハスク(日光で燃えない)。ブロックは壊さない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void strikesSurfacePreyUnderOpenSky(GameTestHelper helper) {
        buildCave(helper, false);
        Vec3 dir = new Vec3(1.0D, 0.0D, 1.0D).normalize();
        FodimnesEntity w = spawnWorm(helper, new Vec3(0.6D, 2.2D, 0.6D), yawOf(dir), 0.0F);
        w.holdStillForTests(1000);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 3.0D, 3.5D));
        husk.setNoAi(true);
        double floorTop = helper.absoluteVec(new Vec3(0.0D, 3.0D, 0.0D)).y;
        double[] maxRise = {Double.NEGATIVE_INFINITY};
        helper.onEachTick(() -> maxRise[0] = Math.max(maxRise[0], w.axisPoint().y - floorTop));
        helper.runAfterDelay(20L, () -> {
            if (w.canBreachForTests(dir)) {
                helper.fail("うろつきの跳ねを空の下で組んだ(洞窟の中だけのはず)");
                return;
            }
            if (!w.canSurfaceBreachForTests(dir)) {
                helper.fail("遠出の地表の段階の跳ねを空の下で組めない");
                return;
            }
            if (!w.canStrikeForTests(husk)) {
                helper.fail("空の見える相手に跳びかかるアーチを組めない");
                return;
            }
            w.noticeForTests(husk);
            helper.succeedWhen(() -> {
                if (husk.getHealth() >= husk.getMaxHealth()) {
                    helper.fail("噛んでいない(" + w.getMode() + "、床からの高さの最大 " + String.format(Locale.ROOT, "%.2f", maxRise[0]) + ")");
                }
                if (maxRise[0] < 1.0D) {
                    helper.fail("床から飛び出していない");
                }
                if (missingStone(helper, 1, 2, pos -> false) > 0) {
                    helper.fail("ブロックを壊した");
                }
            });
        });
    }

    /**
     * 遠出のひと回り: 地中(床 y 1〜3 の石の底)から遠出を始めると、上りながら地表のハスクに気付いて地表の段階になり、空の下で跳びかかって噛む。
     * 獲物が居なくなってしばらくすると戻り始め、始めた高さの近くまで下りて遠出を終える(段階が NONE に戻る)。ブロックは壊さない。
     * 隣のテスト場の Mob を拾わないように、聞こえる距離(followRange)を 8 にする(遠出の獲物を探す範囲もこれに比例して 10.7)。
     * 噛まれた相手は胞子感染になり、死ぬと死体・汚染シダが出て足元の石を汚染する(MOD の仕組み)ので、噛んだらハスクを片付ける(= 獲物が居なくなった)。
     * 壊したかは石の種類でなく、中身の詰まったブロックでなくなったかで数える(テスト場の外から汚染が来ることもある)。
     * テスト場は 7 マスなので、上る高さは 1 ブロックほど —— 地形の起伏・洞窟を突っ切る上り下りは runClient で見る。
     * 7 マスの場の罠(同じテストを 100〜150 個並べて 5〜13% 落ちたのを、2026-10-02 に 1 つずつ潰した):
     * <ul>
     *   <li>床の下(相対 y=0)は空気の層で、外周のバリアはその下まで無い。床下へ潜った個体が空気の層を「頭の上の地表」とみなして床下を進み、
     *       テスト場の外(tick しないチャンク)へ出て固まった → 床下を石で塞ぐ。隅の床下 (0, 0, 0) はテストの構造ブロック(壊せない)なので残し、個体は隅から離して置く。</li>
     *   <li>噛んだ後に次の獲物を探すと、並んだ隣の場のハスク(12 ブロック先のこともある)を選んで壁の下をくぐって行った → 噛んだら Mob に気付くのを止める。</li>
     *   <li>地表で獲物を待つ 10 秒のうちに壁際で回り道が下へ逃げ、壁の下から外へ出ることがあった → このテストだけ 40 tick に縮める。</li>
     *   <li>地表で跳ねる弧の踏み切りに、壁の外の低い地面(床下の空気の層の底)を選んで外へ跳んだ(200 個で 2 個)→ 噛んだら跳ねさせない
     *       (地表の跳ねは {@link #strikesSurfacePreyUnderOpenSky} が見る)。</li>
     *   <li>構造ブロックへ頭が入って抜けなくなった(舵取りの見張りが少し先の 1 点だけだった)→ 本体を直した({@code steer} は動いた先のマスも見る)。</li>
     * </ul>
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 900)
    public static void surfaceOutingHuntsThenReturnsHome(GameTestHelper helper) {
        fillStone(helper, 1, 3);
        fillUnderFloor(helper);
        FodimnesEntity w = spawnWorm(helper, new Vec3(1.5D, 1.6D, 1.5D), -45.0F, 0.0F);
        w.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(8.0D);
        w.holdStillForTests(1000);
        w.setSurfaceIdleTicksForTests(40);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(4.5D, 4.0D, 4.5D));
        husk.setNoAi(true);
        double[] homeY = {Double.NaN};
        boolean[] sawSurface = {false};
        boolean[] sawDescend = {false};
        boolean[] bitten = {false};
        int[] tick = {0};
        int[] biteTick = {-1};
        // 段階の移り変わり(失敗の説明用。最後の 20 個)
        java.util.ArrayDeque<String> history = new java.util.ArrayDeque<>();
        String[] last = {""};
        helper.onEachTick(() -> {
            tick[0]++;
            sawSurface[0] |= w.getOuting() == FodimnesEntity.Outing.SURFACE;
            sawDescend[0] |= w.getOuting() == FodimnesEntity.Outing.DESCEND;
            if (!bitten[0] && (!husk.isAlive() || husk.getHealth() < husk.getMaxHealth())) {
                bitten[0] = true;
                biteTick[0] = tick[0];
                husk.discard();
                // 次の獲物を探させない(並んだテスト場が近いと、隣の場のハスクを次の獲物に選んで壁の下をくぐって行った)・
                // 地表で跳ねさせない(壁の外の低い地面を踏み切りに選んで外へ跳んだ。地表の跳ねは strikesSurfacePreyUnderOpenSky が見る)
                w.setNoticeMobsForTests(false);
                w.setNoRoamBreachForTests(true);
            }
            String now = w.getOuting() + "/" + w.getMode();
            if (!now.equals(last[0])) {
                last[0] = now;
                history.addLast(tick[0] + ":" + now);
                if (history.size() > 20) {
                    history.removeFirst();
                }
            }
        });
        helper.runAfterDelay(20L, () -> {
            homeY[0] = w.axisPoint().y;
            w.setNoticeMobsForTests(true);
            w.startOutingForTests(husk);
            helper.succeedWhen(() -> {
                if (!bitten[0]) {
                    helper.fail("地表のハスクを噛んでいない(" + w.getOuting() + "・" + w.getMode() + ")");
                }
                if (!sawSurface[0]) {
                    helper.fail("地表の段階にならない(" + w.getOuting() + ")");
                }
                if (w.getOuting() != FodimnesEntity.Outing.NONE) {
                    // 戻る段階は、もう始めた高さの近くに居れば同じ tick のうちに終わる(見えないことがある)ので、終わったことだけを見る
                    Vec3 rel = helper.relativeVec(w.axisPoint());
                    helper.fail("遠出を終えていない(" + w.outingDebugForTests() + "・" + w.getMode() + "、戻る段階を見た " + sawDescend[0]
                            + "、噛んだ tick " + biteTick[0] + "、頭(相対) " + fmt(rel) + " " + helper.getLevel().getBlockState(BlockPos.containing(w.axisPoint())).getBlock().getName().getString()
                            + "、生きている " + w.isAlive() + "・tickCount " + w.tickCount + "、移り変わり " + String.join(" ", history) + ")");
                }
                if (!w.isHeadInEarth() || w.axisPoint().y > homeY[0] + 1.5D) {
                    helper.fail(String.format(Locale.ROOT, "始めた高さへ戻っていない(始め %.2f・今 %.2f・地中 %s)",
                            homeY[0], w.axisPoint().y, w.isHeadInEarth()));
                }
                String broken = brokenSolid(helper, 1, 3);
                if (!broken.isEmpty()) {
                    helper.fail("ブロックを壊した: " + broken);
                }
            });
        });
    }

    /**
     * 地表の段階で高さの基準にする「頭の上の床」: 石の上の空き(地表)の高さを答え、木の幹に当たったら幹の根元(= 地面)を答える
     * (幹を岩と数えて梢の上を地表にしない)。上が水なら無し(NaN)。世界のブロックだけを見る静的な関数を直接叩く。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void floorAboveFindsGroundUnderTrees(GameTestHelper helper) {
        fillStone(helper, 1, 3);
        for (int y = 4; y <= 6; y++) {
            helper.setBlock(new BlockPos(3, y, 3), Blocks.OAK_LOG);
        }
        helper.setBlock(new BlockPos(3, 7, 3), Blocks.OAK_LEAVES);
        helper.setBlock(new BlockPos(5, 4, 5), Blocks.WATER);
        Level level = helper.getLevel();
        int ground = helper.absolutePos(new BlockPos(0, 4, 0)).getY();
        double open = FodimnesEntity.floorAbove(level, helper.absoluteVec(new Vec3(1.5D, 2.5D, 1.5D)), 16);
        double trunk = FodimnesEntity.floorAbove(level, helper.absoluteVec(new Vec3(3.5D, 2.5D, 3.5D)), 16);
        double water = FodimnesEntity.floorAbove(level, helper.absoluteVec(new Vec3(5.5D, 2.5D, 5.5D)), 16);
        if (open != ground) {
            helper.fail("石の上の地表の高さが違う: " + open + "(期待 " + ground + ")");
            return;
        }
        if (trunk != ground) {
            helper.fail("木の幹の下で幹の根元(地面)を答えない: " + trunk + "(期待 " + ground + ")");
            return;
        }
        if (!Double.isNaN(water)) {
            helper.fail("上が水なのに床を答えた: " + water);
            return;
        }
        helper.succeed();
    }

    /**
     * 棲み場所の決め方(2026-10-09、地上にも湧くようにしたとき): 頭の上の最初の床に岩の天井が無ければ地上(地表で湧いた・地表で卵から出した)、
     * 天井があれば(洞窟の床)洞窟。地表の高さ探しは洞窟の床を飛ばして上の地表を答える(地上の個体が坑道へ潜った後に地表へ戻れるように)。
     * 世界に入れない個体で、決め方の式そのものを見る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void habitatFollowsTheFloorAbove(GameTestHelper helper) {
        fillStone(helper, 1, 3);
        // 東側(x 4〜6)は洞窟: 床 y 1〜2・空き y 3〜4・天井 y 5〜6
        for (int x = 4; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 4, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 5, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 6, z), Blocks.STONE);
            }
        }
        Level level = helper.getLevel();
        FodimnesEntity w = offLevel(helper);
        Vec3 open = helper.absoluteVec(new Vec3(1.5D, 1.6D, 1.5D));
        w.moveTo(open.x, open.y - FodimnesEntity.AXIS_Y, open.z, 0.0F, 0.0F);
        if (w.decideHabitatForTests() != FodimnesEntity.Habitat.SURFACE) {
            helper.fail("上が地表の個体を地上の個体と決めない: " + w.getHabitat());
            return;
        }
        Vec3 cave = helper.absoluteVec(new Vec3(5.5D, 1.6D, 3.5D));
        w.moveTo(cave.x, cave.y - FodimnesEntity.AXIS_Y, cave.z, 0.0F, 0.0F);
        if (w.decideHabitatForTests() != FodimnesEntity.Habitat.CAVE) {
            helper.fail("上が洞窟の床の個体を洞窟の個体と決めない: " + w.getHabitat());
            return;
        }
        // 洞窟の床(y 3)は飛ばして、天井の上(y 7)を地表と答える
        int roofTop = helper.absolutePos(new BlockPos(0, 7, 0)).getY();
        double surface = FodimnesEntity.surfaceFloorAbove(level, cave, FodimnesEntity.SURFACE_SEARCH_MAX);
        if (surface != roofTop) {
            helper.fail("洞窟の下から地表を探すと " + surface + "(期待 " + roofTop + " = 洞窟の天井の上)");
            return;
        }
        w.discard();
        helper.succeed();
    }

    /**
     * 地上に棲む個体(2026-10-09 ユーザー指定「地下限定ではなく地上でも湧くように」)は、遠出しなくても地表の Mob に気付き、
     * 空の下で跳びかかって噛む(遠出の段階は NONE のまま)。天井の無いテスト場(床 y 0〜3)、的はハスク。
     * 気付く距離は 8 に絞る(並んで走る隣のテスト場の地表の Mob に気付かないように)。噛んだら的を消して気付きを止める
     * (噛まれた相手は胞子感染し、死ぬと汚染シダが出て石を汚染する。{@link #surfaceOutingHuntsThenReturnsHome} と同じ罠)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 300)
    public static void surfaceDwellerHuntsWithoutOuting(GameTestHelper helper) {
        fillStone(helper, 1, 3);
        fillUnderFloor(helper);
        FodimnesEntity w = spawnWorm(helper, new Vec3(1.5D, 1.6D, 1.5D), -45.0F, 0.0F);
        w.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(8.0D);
        w.holdStillForTests(1000);
        w.setNoRoamBreachForTests(true);
        w.setHabitatForTests(FodimnesEntity.Habitat.SURFACE);
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(4.5D, 4.0D, 4.5D));
        husk.setNoAi(true);
        boolean[] bitten = {false};
        boolean[] outed = {false};
        helper.onEachTick(() -> {
            if (w.getOuting() != FodimnesEntity.Outing.NONE) {
                outed[0] = true;
            }
            if (!bitten[0] && husk.isAlive() && husk.getHealth() < husk.getMaxHealth()) {
                bitten[0] = true;
                husk.discard();
                w.setNoticeMobsForTests(false);
            }
        });
        helper.runAfterDelay(10L, () -> {
            if (!w.canNoticeMobForTests(husk)) {
                helper.fail("地上の個体が、遠出していないのに地表のハスクに気付かない");
                return;
            }
            w.setNoticeMobsForTests(true);
            helper.succeedWhen(() -> {
                if (!bitten[0]) {
                    helper.fail("噛んでいない(" + w.getMode() + "、遠出 " + w.getOuting() + ")");
                }
                if (outed[0]) {
                    helper.fail("地上の個体が遠出した");
                }
                if (w.getHabitat() != FodimnesEntity.Habitat.SURFACE) {
                    helper.fail("棲み場所が変わった: " + w.getHabitat());
                }
                String broken = brokenSolid(helper, 1, 3);
                if (!broken.isEmpty()) {
                    helper.fail("ブロックを壊した: " + broken);
                }
            });
        });
    }

    /**
     * 湧きの関門: 床の上に立つ位置を渡すと、床の下の岩へ潜ってから世界に足す(頭は岩の中、体の後ろにも岩が続く)。
     * 足元が空いていれば湧かない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void spawnCheckBurrowsIntoRock(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 3; z++) {
                for (int y = 2; y <= 5; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
            }
        }
        FodimnesEntity ok = offLevel(helper);
        Vec3 feet = helper.absoluteVec(new Vec3(1.5D, 6.0D, 1.5D));
        ok.moveTo(feet.x, feet.y, feet.z, 0.0F, 0.0F);
        if (!ok.acceptOvergrownSpawn(helper.getLevel())) {
            helper.fail("岩の上なのに湧かない");
        }
        if (!ok.isHeadInEarth()) {
            helper.fail("頭が岩の中に無い: " + fmt(ok.axisPoint()));
        }
        if (!BlockPos.containing(ok.axisPoint()).equals(helper.absolutePos(new BlockPos(1, 4, 1)))) {
            helper.fail("床の 1 マス下(相対 (1, 4, 1))に潜っていない: " + BlockPos.containing(ok.axisPoint()));
        }
        ok.updateBody(true);
        for (int i = 1; i <= 3; i++) {
            if (!FodimnesEntity.isEarth(helper.getLevel(), BlockPos.containing(ok.partCenter(i)))) {
                helper.fail("頭の後ろの節 " + i + " が岩の中に無い(体を岩の続く向きへ伸ばしていない): " + fmt(ok.partCenter(i)));
            }
        }
        FodimnesEntity no = offLevel(helper);
        Vec3 air = helper.absoluteVec(new Vec3(5.5D, 5.0D, 5.5D));
        no.moveTo(air.x, air.y, air.z, 0.0F, 0.0F);
        if (no.acceptOvergrownSpawn(helper.getLevel())) {
            helper.fail("足元に地面が無いのに湧いた");
        }
        helper.succeed();
    }

    // ================================================================== 保存・同期

    /** 曲がった体のまま保存して読み込んでも、出現のパケットで送っても、同じ体の形から始まる。考えは眠りから。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void saveLoadAndSpawnPacketKeepBodyShape(GameTestHelper helper) {
        FodimnesEntity w = offLevel(helper);
        double r = 3.0D;
        Vec3 c = FAR_ORIGIN;
        double theta = 0.0D;
        start(w, c.add(r, 0.0D, 0.0D), yawOf(new Vec3(0.0D, 0.0D, 1.0D)), 0.0F);
        for (int t = 0; t < 90; t++) {
            theta += 0.2D / r;
            Vec3 p = c.add(r * Math.cos(theta), 0.1D * t, r * Math.sin(theta));   // 螺旋(上りながら曲がる)
            driveDir(w, p, new Vec3(-Math.sin(theta) * 0.2D, 0.1D, Math.cos(theta) * 0.2D).normalize());
        }
        double[] want = w.solvedPartPositions();

        CompoundTag tag = new CompoundTag();
        w.saveWithoutId(tag);
        FodimnesEntity loaded = offLevel(helper);
        loaded.load(tag);
        loaded.updateBody(true);
        compareBodies(helper, "保存 → 読み込み", want, loaded.solvedPartPositions());
        if (loaded.getMode() != FodimnesEntity.Mode.REST) {
            helper.fail("読み込んだ個体が眠り(REST)から始まらない: " + loaded.getMode());
        }

        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            w.writeSpawnData(buf);
            FodimnesEntity client = offLevel(helper);
            client.moveTo(w.getX(), w.getY(), w.getZ(), w.getYRot(), w.getXRot());
            client.readSpawnData(buf);
            client.updateBody(true);
            compareBodies(helper, "出現のパケット", want, client.solvedPartPositions());
        } finally {
            buf.release();
        }
        helper.succeed();
    }

    /**
     * 部位の当たり判定は<b>最初の tick を待たずに</b>体の上に置かれる: 読み込んだ直後(チャンクから読み込まれた個体は tick しない端のチャンクに
     * 居続けることがある)と、世界に足した直後。点検の前は最初の tick まで世界の原点 (0, 0, 0) に残っていた(原点のそばの爆発・矢が当たる)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void partsArePlacedBeforeFirstTick(GameTestHelper helper) {
        // 読み込んだ直後(updateBody を呼ばない)
        FodimnesEntity w = offLevel(helper);
        double r = 3.0D;
        Vec3 c = FAR_ORIGIN;
        double theta = 0.0D;
        start(w, c.add(r, 0.0D, 0.0D), yawOf(new Vec3(0.0D, 0.0D, 1.0D)), 0.0F);
        for (int t = 0; t < 80; t++) {
            theta += 0.2D / r;
            driveDir(w, c.add(r * Math.cos(theta), 0.0D, r * Math.sin(theta)), new Vec3(-Math.sin(theta), 0.0D, Math.cos(theta)));
        }
        CompoundTag tag = new CompoundTag();
        w.saveWithoutId(tag);
        FodimnesEntity loaded = offLevel(helper);
        loaded.load(tag);
        for (int i = 1; i <= FodimnesEntity.PARTS; i++) {
            Vec3 box = loaded.part(i).getBoundingBox().getCenter();
            if (box.distanceTo(w.partCenter(i)) > 1.0E-3D) {
                helper.fail("読み込んだ直後の部位 " + i + " の箱が体の上に無い: " + fmt(box) + "(期待 " + fmt(w.partCenter(i)) + ")");
            }
        }

        // 世界に足した直後(同じ tick のうち)。下を向けて置くので体は真上へ伸びる(ほかのテスト場へ向かない)。確かめたらすぐ消す
        FodimnesEntity fresh = offLevel(helper);
        Vec3 feet = helper.absoluteVec(new Vec3(3.5D, 2.6D, 3.5D));
        fresh.moveTo(feet.x, feet.y, feet.z, 0.0F, 90.0F);
        fresh.setNoAi(true);
        helper.getLevel().addFreshEntity(fresh);
        Vec3 axis = fresh.axisPoint();
        double s = FodimnesEntity.SPACING * fresh.getScale();
        String bad = null;
        for (int i = 1; i <= FodimnesEntity.PARTS && bad == null; i++) {
            Vec3 want = axis.add(0.0D, s * i, 0.0D);
            Vec3 box = fresh.part(i).getBoundingBox().getCenter();
            if (box.distanceTo(want) > 1.0E-3D) {
                bad = "世界に足した直後の部位 " + i + " の箱が頭の後ろ(真上)に無い: " + fmt(box) + "(期待 " + fmt(want) + ")";
            }
        }
        fresh.discard();
        if (bad != null) {
            helper.fail(bad);
        }
        helper.succeed();
    }

    private static void compareBodies(GameTestHelper helper, String what, double[] want, double[] got) {
        for (int i = 0; i < want.length; i += 3) {
            double d = Math.sqrt(Math.pow(want[i] - got[i], 2) + Math.pow(want[i + 1] - got[i + 1], 2) + Math.pow(want[i + 2] - got[i + 2], 2));
            if (d > 1.0E-3D) {
                helper.fail(what + " で部位 " + (i / 3 + 1) + " の位置が " + d + " ずれた(同じ体の形から始まっていない)");
            }
        }
    }

    // ================================================================== 能力(採用案): 坑道の壁を破って噛む(10-09 から止まらない)

    /**
     * 通路(石の中に掘った高さ 2 の横穴)に立つ相手の音を聞くと、真横の壁の奥から助走して壁を破り(burst)、そのまま相手へ突っ込んで噛み(snap)、
     * 向かいの壁へ抜ける。<b>どの tick も止まらない</b>(2026-10-09 ユーザー指定「攻撃の際にも止まらず、常に動き続ける」。前は壁の中で
     * 口の先を出して最低 1.5 秒待ってから飛び出していた)。ブロックは 1 つも壊さない。的はハスク(日光で燃えない)。
     * 床下(y = 0)は石で塞ぐ(空気の層を通って場の外へ出ないように。隅の構造ブロックは残す。{@link #surfaceOutingHuntsThenReturnsHome} と同じ)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void bitesFromTunnelWallWithoutStopping(GameTestHelper helper) {
        fillStone(helper, 2, 6);
        fillUnderFloor(helper);
        for (int x = 0; x <= 6; x++) {
            helper.setBlock(new BlockPos(x, 2, 3), Blocks.AIR);
            helper.setBlock(new BlockPos(x, 3, 3), Blocks.AIR);
        }
        Husk husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new Vec3(3.5D, 2.0D, 3.5D));
        husk.setNoAi(true);
        Vec3 headRel = new Vec3(3.5D, 3.42D, 6.2D);
        Vec3 headAbs = helper.absoluteVec(headRel);
        FodimnesEntity w = spawnWorm(helper, headRel, yawOf(helper.absoluteVec(new Vec3(3.5D, 3.42D, 3.5D)).subtract(headAbs)), 0.0F);
        w.hearForTests(husk.position().add(0.0D, 1.0D, 0.0D), husk);
        int[] tick = {0};
        int[] firstBurst = {-1};
        int[] firstSnap = {-1};
        int[] bite = {-1};
        int[] still = {0};
        int[] stillRun = {0};
        int[] worstRun = {0};
        String[] firstStill = {null};
        Vec3[] last = {w.axisPoint()};
        helper.onEachTick(() -> {
            tick[0]++;
            FodimnesEntity.Mode m = w.getMode();
            Vec3 now = w.axisPoint();
            // 噛んで 10 tick 後まで、1 tick に動いた量(頭の軸の点)
            if (bite[0] < 0 || tick[0] <= bite[0] + 10) {
                if (now.distanceTo(last[0]) < 0.02D) {
                    still[0]++;
                    stillRun[0]++;
                    worstRun[0] = Math.max(worstRun[0], stillRun[0]);
                    if (firstStill[0] == null) {
                        firstStill[0] = tick[0] + " tick 目(" + m + ")";
                    }
                } else {
                    stillRun[0] = 0;
                }
            }
            last[0] = now;
            if (m == FodimnesEntity.Mode.BURST && firstBurst[0] < 0) {
                firstBurst[0] = tick[0];
            }
            if (m == FodimnesEntity.Mode.SNAP && firstSnap[0] < 0) {
                firstSnap[0] = tick[0];
            }
            if (bite[0] < 0 && husk.getHealth() < husk.getMaxHealth()) {
                bite[0] = tick[0];
            }
        });
        helper.succeedWhen(() -> {
            if (bite[0] < 0 || tick[0] < bite[0] + 10) {
                helper.fail("噛んでいない(burst " + firstBurst[0] + "・snap " + firstSnap[0] + "、今 " + w.getMode() + ")");
            }
            if (firstBurst[0] < 0 || firstSnap[0] < firstBurst[0] || bite[0] < firstSnap[0]) {
                helper.fail("流れの順が違う: burst " + firstBurst[0] + " → snap " + firstSnap[0] + " → 噛む " + bite[0]);
            }
            if (worstRun[0] > 1 || still[0] > 2) {
                helper.fail("止まった: 動かなかった tick " + still[0] + "(続けて最大 " + worstRun[0] + "、最初は " + firstStill[0] + ")");
            }
            int missing = missingStone(helper, 2, 6, pos -> pos.getZ() == 3 && pos.getY() <= 3);
            if (missing > 0) {
                helper.fail("ブロックを " + missing + " 個壊した(壊さないはず)");
            }
        });
    }

    /** 床下(y = 0)を石で埋める。隅 (0, 0, 0) はテストの構造ブロック(壊せない)なので残す。 */
    private static void fillUnderFloor(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                if (x != 0 || z != 0) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
        }
    }

    /**
     * 待ち伏せの点への寄せ方(静的な関数を直接): 壁の面の外向きを −Z、面を原点、待ち伏せの点を面の 0.632 奥とする。
     * もう壁の中の軸の線の上(最後の寄せの途中)なら回り込まない —— 点検の前は、面の後ろ 1 ブロックより手前で計画を立て直すと
     * 回り込みが付き直し、2.5 奥の点まで引き返していた。通路の側・横から来るときは回り込む。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void lurkPlanDoesNotRestageOnFinalApproach(GameTestHelper helper) {
        Vec3 face = Vec3.ZERO;
        Vec3 normal = new Vec3(0.0D, 0.0D, -1.0D);
        Vec3 axis = face.subtract(normal.scale(FodimnesEntity.WALL_FACE_FWD));
        Object[][] cases = {
                // 頭の軸の点, 回り込むはず
                {new Vec3(0.0D, 0.0D, 3.0D), false},     // 壁の奥の軸の線の上
                {new Vec3(0.0D, 0.0D, 0.9D), false},     // 最後の寄せ(面の後ろ 1 ブロックより手前)
                {new Vec3(0.05D, 0.02D, 0.75D), false},  // 待ち伏せの点のすぐ後ろ(少しずれ)
                {new Vec3(0.0D, 0.0D, 0.1D), true},      // 面のすぐ近く(唇の先が通路へ出ている)
                {new Vec3(0.0D, 0.0D, -1.0D), true},     // 通路の側
                {new Vec3(2.5D, 0.0D, 1.0D), true},      // 横(ずれが奥行きより大きい)
                {new Vec3(0.5D, 0.0D, 0.5D), true},      // 浅くて軸の線から外れている
                {new Vec3(1.0D, 0.0D, 3.0D), false},     // 深い所から斜めに入る(ずれ < 奥行き)
        };
        for (Object[] c : cases) {
            Vec3 me = (Vec3) c[0];
            boolean want = (Boolean) c[1];
            boolean got = FodimnesEntity.needsStaging(me, face, normal, axis, 1.0D);
            if (got != want) {
                helper.fail("頭が " + fmt(me) + " のとき回り込みが " + got + "(期待 " + want + ")");
            }
        }
        helper.succeed();
    }

    /**
     * 採掘の音({@code BlockEvent.BreakEvent} の口)を聞く: サバイバルのプレイヤーの音は聞いて標的にし、そちらへ地中を進む。
     * クリエイティブのプレイヤーの音・聞こえる距離(24)の外の音は聞かない。音が 8 秒途絶えたら見失い、追うのをやめる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400, batch = BATCH_HEARING)
    public static void hearsMiningAndLosesTrackWhenQuiet(GameTestHelper helper) {
        fillStone(helper, 2, 6);
        helper.setBlock(new BlockPos(5, 2, 5), Blocks.AIR);
        helper.setBlock(new BlockPos(5, 3, 5), Blocks.AIR);
        Vec3 startRel = new Vec3(1.5D, 3.42D, 1.5D);
        Vec3 startAbs = helper.absoluteVec(startRel);
        BlockPos minedRel = new BlockPos(5, 3, 5);
        Vec3 noise = Vec3.atCenterOf(helper.absolutePos(minedRel));
        FodimnesEntity w = spawnWorm(helper, startRel, yawOf(noise.subtract(startAbs)), 0.0F);

        // 聞こえる距離の外の音は聞かない(世界に入れない個体で、聞く式そのものを見る)
        FodimnesEntity far = offLevel(helper);
        far.moveTo(FAR_ORIGIN.x, FAR_ORIGIN.y, FAR_ORIGIN.z, 0.0F, 0.0F);
        far.hearForTests(FAR_ORIGIN.add(far.hearingRange() + 2.0D, 0.0D, 0.0D), null);
        if (far.noiseTimer() != 0) {
            helper.fail("聞こえる距離(" + far.hearingRange() + ")の外の音を聞いた");
        }

        Player creative = helper.makeMockPlayer(GameType.CREATIVE);
        creative.moveTo(noise.x, noise.y - 1.5D, noise.z);
        FodimnesEntity.onBlockBroken(helper.getLevel(), helper.absolutePos(minedRel), creative);
        if (w.getTarget() != null || w.noiseTimer() != 0) {
            helper.fail("クリエイティブのプレイヤーの採掘を聞いた");
        }
        Player miner = helper.makeMockPlayer(GameType.SURVIVAL);
        Vec3 minerFeet = helper.absoluteVec(new Vec3(5.5D, 2.0D, 5.5D));
        miner.moveTo(minerFeet.x, minerFeet.y, minerFeet.z);
        FodimnesEntity.onBlockBroken(helper.getLevel(), helper.absolutePos(minedRel), miner);
        if (w.getTarget() != miner || w.noiseTimer() != FodimnesEntity.LOSE_TICKS) {
            helper.fail("サバイバルのプレイヤーの採掘を聞いていない(標的 " + w.getTarget() + "、残り " + w.noiseTimer() + ")");
        }
        double d0 = startAbs.distanceTo(noise);
        helper.runAtTickTime(20L, () -> {
            if (!w.isHunting()) {
                helper.fail("音を聞いたのに追っていない: " + w.getMode());
            }
            double d = w.axisPoint().distanceTo(noise);
            if (d > d0 - 1.0D) {
                helper.fail("音の方へ進んでいない(はじめ " + d0 + " → " + d + ")");
            }
        });
        helper.runAtTickTime(140L, () -> {
            if (w.getTarget() != miner) {
                helper.fail("8 秒より前に見失った(" + w.noiseTimer() + " tick 残っているはず)");
            }
        });
        helper.runAtTickTime((long) FodimnesEntity.LOSE_TICKS + 10L, () -> {
            if (w.getTarget() != null || w.noiseTimer() != 0) {
                helper.fail("音が 8 秒途絶えたのに見失わない(標的 " + w.getTarget() + "、残り " + w.noiseTimer() + ")");
            }
        });
        helper.runAtTickTime((long) FodimnesEntity.LOSE_TICKS + 12L, () -> helper.succeedWhen(() -> {
            if (w.isHunting()) {
                helper.fail("見失ったのに追い続けている: " + w.getMode());
            }
            if (missingStone(helper, 2, 6, pos -> pos.getX() == 5 && pos.getZ() == 5 && pos.getY() <= 3) > 0) {
                helper.fail("ブロックを壊した");
            }
        }));
    }

    /** G5(適応種)で、地形をすり抜け(窒息しない)、重力が無い。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void isGroupFiveBurrower(GameTestHelper helper) {
        FodimnesEntity w = offLevel(helper);
        if (!Adaptation.isAdaptive(w.getType())) {
            helper.fail("group_5 に入っていない(適応が効かない)");
        }
        if (!w.noPhysics || !w.isNoGravity() || w.isInWall()) {
            helper.fail("地形をすり抜けない・重力がある");
        }
        if (w.hurt(helper.getLevel().damageSources().inWall(), 5.0F)) {
            helper.fail("窒息のダメージを受けた");
        }
        helper.succeed();
    }
}
