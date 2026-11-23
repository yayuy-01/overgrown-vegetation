package com.yayuy.overgrownvegetation.gametest;

import com.mojang.authlib.GameProfile;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.WallRemainsBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModCreepingWall;
import com.yayuy.overgrownvegetation.wall.CreepingWall;
import com.yayuy.overgrownvegetation.wall.CreepingWalls;
import com.yayuy.overgrownvegetation.wall.PlacedBlocks;
import com.yayuy.overgrownvegetation.wall.WallTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 蠢く壁(2026-11-16 試作)の決まり: 速さ・斜めの速さ・枝分かれ・空の下で止まる・時間で止まる・Mob で延びる・即死と成れの果て・置いたブロックを壊す・灯りが消える。
 *
 * <p>壁は 7×7 のテスト場に収まらないので、<b>決まった絶対座標</b>(x 27200 から 96 ずつ・y 10〜・z 27200)に石の箱を積み、中にトンネルを掘って試す
 * (同じ場所を毎回上書きするので run の世界が膨らまない。BaseGameTests と同じ考え)。チャンクはテストの間だけ強制で読み込む。
 * 期待値は {@link WallTuning} の値から出す(速さを変えてもテストを直さなくてよい)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class CreepingWallGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterCreepingWallGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(CreepingWallGameTests.class);
        }

        private Registration() {
        }
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final int SITE_X = 27200, SITE_Y = 10, SITE_Z = 27200;
    /** 箱の大きさ(x 方向に長い)。 */
    private static final int LEN = 72, HIGH = 24, WIDE = 24;

    /** 試す場所 1 つ(箱の石の中にトンネルを掘る)。 */
    private record Site(ServerLevel level, BlockPos min) {

        static Site build(GameTestHelper helper, int index) {
            ServerLevel level = helper.getLevel();
            BlockPos min = new BlockPos(SITE_X + index * 96, SITE_Y, SITE_Z);
            Site s = new Site(level, min);
            s.force(true);
            BlockState stone = Blocks.STONE.defaultBlockState();
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int x = -1; x <= LEN; x++) {
                for (int y = -1; y <= HIGH; y++) {
                    for (int z = -1; z <= WIDE; z++) {
                        p.set(min.getX() + x, min.getY() + y, min.getZ() + z);
                        level.setBlock(p, stone, Block.UPDATE_CLIENTS);
                    }
                }
            }
            // 箱の外(前の回の跡・落ちたアイテム)も片付ける
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, s.box())) {
                item.discard();
            }
            return s;
        }

        AABB box() {
            return new AABB(min.getX() - 4, min.getY() - 4, min.getZ() - 4, min.getX() + LEN + 4, min.getY() + HIGH + 4, min.getZ() + WIDE + 4);
        }

        void force(boolean on) {
            for (int cx = (min.getX() - 16) >> 4; cx <= (min.getX() + LEN + 16) >> 4; cx++) {
                for (int cz = (min.getZ() - 16) >> 4; cz <= (min.getZ() + WIDE + 16) >> 4; cz++) {
                    level.setChunkForced(cx, cz, on);
                }
            }
        }

        BlockPos at(int x, int y, int z) {
            return min.offset(x, y, z);
        }

        /** 2 段の高さの空きを掘る。 */
        void carve(int x, int y, int z) {
            level.setBlock(at(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(at(x, y + 1, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        /** x 方向のまっすぐなトンネル(x0〜x1、高さ 2)。 */
        void tunnelX(int x0, int x1, int y, int z) {
            for (int x = x0; x <= x1; x++) {
                carve(x, y, z);
            }
        }

        void finish(CreepingWall wall) {
            if (wall != null) {
                CreepingWalls.discard(wall);
            }
            force(false);
        }
    }

    /** 壁を起こす(テスト場にはプレイヤーがいないので、遠い枝を止める範囲は外す)。 */
    private static CreepingWall startWall(ServerLevel level, BlockPos origin) {
        CreepingWall wall = CreepingWalls.start(level, origin);
        wall.activeRadius = 0;
        return wall;
    }

    /** {@code dist} ブロック(1/32 単位の距離 dist32)に届くのは、始まりから何 tick 目か。 */
    private static double ticksFor(double dist32) {
        return dist32 / (WallTuning.speed() / 20.0D * CreepingWall.UNIT);
    }

    // ------------------------------------------------------------------ 速さ

    /** まっすぐな通路で、先頭がダッシュの速さ(1 tick に 0.28 ブロック)で進み、それより先へは行かない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "creeping_wall")
    public static void advancesAtSprintSpeedInStraightTunnel(GameTestHelper helper) {
        Site s = Site.build(helper, 0);
        s.tunnelX(2, 65, 4, 12);
        CreepingWall wall = startWall(s.level(), s.at(2, 4, 12));
        long start = wall.startTick();
        helper.runAtTickTime(110, () -> {
            long t = s.level().getGameTime() - start;
            int expect = (int) Math.floor(t * WallTuning.speed() / 20.0D); // 届いているはずの升
            boolean reached = wall.isFilled(s.at(2 + expect - 1, 4, 12));
            boolean beyond = wall.isFilled(s.at(2 + expect + 2, 4, 12));
            s.finish(wall);
            if (!reached || beyond) {
                helper.fail("t=" + t + " で " + expect + " ブロック先: 届いた " + reached + "・その 2 先も " + beyond);
                return;
            }
            helper.succeed();
        });
    }

    /** 45° の階段の通路でも、道のりのユークリッド距離で同じ速さ(6 方向の幅優先なら √2 倍遅くなる所)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "creeping_wall")
    public static void diagonalStairsKeepEuclideanSpeed(GameTestHelper helper) {
        Site s = Site.build(helper, 1);
        for (int i = 0; i < 18; i++) {
            s.carve(2 + i, 1 + i, 12);
            s.level().setBlock(s.at(2 + i, 3 + i, 12), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        CreepingWall wall = startWall(s.level(), s.at(2, 1, 12));
        long start = wall.startTick();
        int step = 12;
        double expect = ticksFor(step * 45.0D); // 斜め 1 歩 = √2 ブロック
        long[] firstSeen = {-1};
        helper.onEachTick(() -> {
            if (firstSeen[0] < 0 && wall.isFilled(s.at(2 + step, 1 + step, 12))) {
                firstSeen[0] = s.level().getGameTime() - start;
            }
        });
        helper.runAtTickTime(150, () -> {
            s.finish(wall);
            if (firstSeen[0] < 0) {
                helper.fail("階段の " + step + " 段目に届かなかった");
            } else if (Math.abs(firstSeen[0] - expect) > expect * 0.15D + 2) {
                helper.fail("階段の " + step + " 段目に届いたのは t=" + firstSeen[0] + "(期待 " + Math.round(expect) + " ±15%)");
            } else {
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ 枝分かれ

    /** T 字の分岐で、左右の枝の同じ距離の升へ同じ tick に届き、人のいない行き止まりの小部屋も埋まる。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "creeping_wall")
    public static void splitsIntoEveryBranchAtSameSpeed(GameTestHelper helper) {
        Site s = Site.build(helper, 2);
        s.tunnelX(2, 20, 4, 12); // 幹
        for (int dz = 1; dz <= 10; dz++) { // 右と左の枝
            s.carve(20, 4, 12 + dz);
            s.carve(20, 4, 12 - dz);
        }
        for (int x = 21; x <= 23; x++) { // 左の枝の先の行き止まりの小部屋(3×3×2)
            for (int z = 0; z <= 2; z++) {
                s.carve(x, 4, z);
            }
        }
        s.carve(21, 4, 2); // 小部屋への入口(枝の先 z=2 から)
        CreepingWall wall = startWall(s.level(), s.at(2, 4, 12));
        BlockPos right = s.at(20, 4, 20), left = s.at(20, 4, 4);
        long[] tr = {-1}, tl = {-1};
        long start = wall.startTick();
        helper.onEachTick(() -> {
            long t = s.level().getGameTime() - start;
            if (tr[0] < 0 && wall.isFilled(right)) tr[0] = t;
            if (tl[0] < 0 && wall.isFilled(left)) tl[0] = t;
        });
        helper.runAtTickTime(160, () -> {
            boolean room = wall.isFilled(s.at(23, 4, 0)) && wall.isFilled(s.at(23, 5, 0));
            s.finish(wall);
            if (tr[0] < 0 || tl[0] < 0) {
                helper.fail("枝に届かない: 右 " + tr[0] + "・左 " + tl[0]);
            } else if (tr[0] != tl[0]) {
                helper.fail("左右で届く時刻が違う: 右 " + tr[0] + "・左 " + tl[0]);
            } else if (!room) {
                helper.fail("行き止まりの小部屋が埋まらない");
            } else {
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ 空の下

    /** 箱の横へ抜けるトンネル: 外(真上に空が見える升)へは出ず、手前の升は埋まる。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = "creeping_wall")
    public static void stopsWhereSkyIsVisible(GameTestHelper helper) {
        Site s = Site.build(helper, 3);
        s.tunnelX(2, LEN + 3, 4, 12); // 箱の外(x = LEN 以降)まで掘る → 外は空の下
        // 箱の外の空き(壁が出てくるか見る所)
        CreepingWall wall = startWall(s.level(), s.at(LEN - 8, 4, 12));
        helper.runAtTickTime(80, () -> {
            boolean inside = wall.isFilled(s.at(LEN - 1, 4, 12)) && wall.isFilled(s.at(LEN, 4, 12)); // x = LEN は箱の壁の列(掘った)。上は石
            boolean outside = wall.isFilled(s.at(LEN + 1, 4, 12)) || wall.isFilled(s.at(LEN + 2, 4, 12));
            s.finish(wall);
            if (!inside) {
                helper.fail("屋根の下の升が埋まらない");
            } else if (outside) {
                helper.fail("空の下へ出た");
            } else {
                helper.succeed();
            }
        });
    }

    // ------------------------------------------------------------------ 時間

    /** 時間が来たら止まり、それより先へは進まず、先頭が汚染の塊(本物のブロック)になる。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "creeping_wall")
    public static void stopsAfterDurationAndLeavesLump(GameTestHelper helper) {
        Site s = Site.build(helper, 4);
        s.tunnelX(2, 65, 4, 12);
        CreepingWall wall = startWall(s.level(), s.at(2, 4, 12));
        wall.setStopTickForTests(wall.startTick() + 40); // 40 tick ≒ 11 ブロック
        int reachAtStop = (int) Math.floor(40 * WallTuning.speed() / 20.0D);
        int[] filledAtStop = {-1};
        helper.runAtTickTime(60, () -> filledAtStop[0] = wall.filledCount());
        helper.runAtTickTime(150, () -> {
            boolean stopped = wall.isStopped();
            boolean grew = wall.filledCount() != filledAtStop[0];
            boolean beyond = wall.isFilled(s.at(2 + reachAtStop + 2, 4, 12));
            boolean lump = false;
            for (int dx = reachAtStop - 2; dx <= reachAtStop + 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    lump |= s.level().getBlockState(s.at(2 + dx, 4 + dy, 12)).is(ModBlocks.OVERGROWN_DIRT.get());
                }
            }
            s.finish(wall);
            if (!stopped) helper.fail("止まらない");
            else if (grew) helper.fail("止まった後も広がった");
            else if (beyond) helper.fail("止まる時刻より先まで進んだ");
            else if (!lump) helper.fail("止まった所に塊が無い");
            else helper.succeed();
        });
    }

    /** 通り道の Mob は飲まれて消え、止まるまでの時間が 1 体につき 1 秒延びる。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = "creeping_wall")
    public static void swallowingMobExtendsDuration(GameTestHelper helper) {
        Site s = Site.build(helper, 5);
        s.tunnelX(2, 40, 4, 12);
        BlockPos at = s.at(8, 4, 12);
        Mob husk = EntityType.HUSK.create(s.level());
        husk.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        husk.setNoAi(true);
        husk.setPersistenceRequired();
        s.level().addFreshEntity(husk);
        CreepingWall wall = startWall(s.level(), s.at(2, 4, 12));
        long base = wall.stopTick();
        helper.runAtTickTime(60, () -> {
            boolean gone = husk.isRemoved();
            int swallowed = wall.swallowed();
            long ext = wall.stopTick() - base;
            if (!gone) husk.discard();
            s.finish(wall);
            if (!gone) helper.fail("ハスクが飲まれない");
            else if (swallowed != 1 || ext != WallTuning.ticksPerSwallow) helper.fail("飲んだ数 " + swallowed + "・延び " + ext + " tick");
            else helper.succeed();
        });
    }

    // ------------------------------------------------------------------ プレイヤー

    /** 飲まれたプレイヤーは即死し、持ち物(防具を含む)の入った成れの果てが残る。成れの果ては壊すと中身が出る。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = "creeping_wall")
    public static void playerDiesAndLeavesRemainsWithItems(GameTestHelper helper) {
        Site s = Site.build(helper, 6);
        s.tunnelX(2, 40, 4, 12);
        ServerLevel level = s.level();
        String name = "creeping_wall_victim";
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
        player.getInventory().clearContent();
        player.setHealth(player.getMaxHealth());
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        player.getInventory().setItem(9, new ItemStack(Items.TORCH, 16));
        player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        player.giveExperiencePoints(0);
        player.addItem(new ItemStack(Items.TOTEM_OF_UNDYING)); // トーテムがあっても助からない
        BlockPos at = s.at(8, 4, 12);
        player.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        CreepingWall wall = startWall(level, s.at(2, 4, 12));
        boolean[] eaten = {false};
        helper.onEachTick(() -> {
            if (!eaten[0] && CreepingWalls.swallowIfInside(level, wall, player)) {
                eaten[0] = true;
            }
        });
        helper.runAtTickTime(60, () -> {
            BlockPos found = null;
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-2, -2, -2), at.offset(2, 2, 2))) {
                if (level.getBlockState(p).is(ModCreepingWall.WALL_REMAINS.get())) {
                    found = p.immutable();
                }
            }
            boolean dead = player.getHealth() <= 0.0F;
            boolean emptied = player.getInventory().isEmpty();
            int inside = found != null && level.getBlockEntity(found) instanceof WallRemainsBlockEntity r ? r.count() : -1;
            int dropped = 0; // 出たアイテムの数の合計(落とすときに山が割れるので、山の数でなく個数で数える)
            if (found != null) {
                level.destroyBlock(found, true);
                for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(found).inflate(2))) {
                    dropped += item.getItem().getCount();
                    item.discard();
                }
            }
            player.setHealth(player.getMaxHealth());
            player.getInventory().clearContent();
            s.finish(wall);
            if (!eaten[0]) helper.fail("飲まれない");
            else if (!dead) helper.fail("死なない");
            else if (found == null) helper.fail("成れの果てが無い");
            else if (!emptied) helper.fail("持ち物が残っている");
            else if (inside != 4) helper.fail("成れの果ての中身が 4 つでない: " + inside);
            else if (dropped != 5 + 16 + 1 + 1) helper.fail("壊して出たアイテムが 23 個でない: " + dropped);
            else helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 置いたブロック・灯り

    /** プレイヤーが置いた丸石の栓は硬さ × 2 秒で壊して進み、置いた土はそれより早い。生成された建物(覚えていない苔むした丸石)は壊さない(決定 その 8)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 300, batch = "creeping_wall")
    public static void chewsPlacedBlocksByHardness(GameTestHelper helper) {
        Site s = Site.build(helper, 7);
        ServerLevel level = s.level();
        // 3 本の平行なトンネル(z = 4・12・20)。8 ブロック先に栓
        int[] zs = {4, 12, 20};
        for (int z : zs) {
            s.tunnelX(2, 30, 4, z);
        }
        // 3 本を始まりの所でつなぐ
        for (int z = 4; z <= 20; z++) {
            s.carve(2, 4, z);
        }
        BlockPos cobble = s.at(10, 4, 4), stone = s.at(10, 4, 12), dirt = s.at(10, 4, 20);
        for (BlockPos p : new BlockPos[]{cobble, stone, dirt}) {
            Block b = p == cobble ? Blocks.COBBLESTONE : p == stone ? Blocks.MOSSY_COBBLESTONE : Blocks.DIRT;
            level.setBlock(p, b.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(p.above(), b.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        PlacedBlocks.mark(level, dirt);
        PlacedBlocks.mark(level, dirt.above());
        PlacedBlocks.mark(level, cobble);
        PlacedBlocks.mark(level, cobble.above());
        // stone の所の苔むした丸石は覚えていない = ダンジョンなど生成された建物
        CreepingWall wall = startWall(level, s.at(2, 12, 12).below(8)); // = (2, 4, 12)
        long start = wall.startTick();
        long[] cobbleOpen = {-1}, dirtOpen = {-1};
        helper.onEachTick(() -> {
            long t = level.getGameTime() - start;
            if (cobbleOpen[0] < 0 && wall.isFilled(cobble.east())) cobbleOpen[0] = t;
            if (dirtOpen[0] < 0 && wall.isFilled(dirt.east())) dirtOpen[0] = t;
        });
        helper.runAtTickTime(260, () -> {
            // 生成された建物の栓は壊さない(跡で汚染したブロックには変わりうる)
            boolean stoneHeld = !level.getBlockState(stone).isAir() && !wall.isFilled(stone) && !wall.isFilled(stone.east());
            s.finish(wall);
            // 栓の手前に着く時刻(道のり: 始まり → 横へ 8 → 前へ 7)+ 壊す時間
            double arrive = ticksFor(15 * 32.0D);
            double cobbleExpect = arrive + 2.0F * WallTuning.chewTicksPerHardness; // 丸石の硬さ 2
            double dirtExpect = ticksFor(7 * 32.0D) + 0.5F * WallTuning.chewTicksPerHardness; // 土 0.5、真ん中は道のり 7
            if (!stoneHeld) helper.fail("生成された建物(覚えていない苔むした丸石)の栓を壊した");
            else if (cobbleOpen[0] < 0) helper.fail("丸石の栓を壊さない");
            else if (cobbleOpen[0] < cobbleExpect - 4) helper.fail("丸石を壊すのが早すぎる: t=" + cobbleOpen[0] + "(期待 " + Math.round(cobbleExpect) + " 前後)");
            else if (dirtOpen[0] < 0) helper.fail("置いた土を壊さない");
            else if (dirtOpen[0] > cobbleOpen[0]) helper.fail("土の方が丸石より遅い: 土 " + dirtOpen[0] + "・丸石 " + cobbleOpen[0] + "(土の期待 " + Math.round(dirtExpect) + ")");
            else helper.succeed();
        });
    }

    /** 灯り(松明・ランタン)は先頭が来ると消える(落とさない)。チェストは壊さず残る。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = "creeping_wall")
    public static void lightsGoOutChestsStay(GameTestHelper helper) {
        Site s = Site.build(helper, 8);
        ServerLevel level = s.level();
        s.tunnelX(2, 30, 4, 12);
        BlockPos torch = s.at(6, 4, 12), lantern = s.at(9, 4, 12), chest = s.at(12, 4, 12);
        level.setBlock(torch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lantern, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(chest) instanceof net.minecraft.world.Container c) {
            c.setItem(0, new ItemStack(Items.APPLE, 3));
        }
        CreepingWall wall = startWall(level, s.at(2, 4, 12));
        helper.runAtTickTime(80, () -> {
            boolean torchGone = !level.getBlockState(torch).is(Blocks.TORCH);
            boolean lanternGone = !level.getBlockState(lantern).is(Blocks.LANTERN);
            // チェストは壊さず覆う(汚染の決まりで中身を保った「過繁茂したチェスト」に変わることがある)
            boolean chestStays = level.getBlockEntity(chest) instanceof net.minecraft.world.Container c && c.getItem(0).is(Items.APPLE) && c.getItem(0).getCount() == 3;
            boolean passed = wall.isFilled(chest.east(3));
            int drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(torch).inflate(8)).size();
            s.finish(wall);
            if (!torchGone || !lanternGone) helper.fail("灯りが残った: 松明 " + !torchGone + "・ランタン " + !lanternGone);
            else if (drops > 0) helper.fail("灯りがアイテムを落とした: " + drops);
            else if (!chestStays) helper.fail("チェスト(中身)が残らない");
            else if (!passed) helper.fail("チェストの先へ進まない");
            else helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 量(決定 その 8)

    /** 飲んだ Mob による延長は最大 60 秒(止まるまで最長 60 + 60 秒)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = "creeping_wall")
    public static void swallowExtensionIsCapped(GameTestHelper helper) {
        Site s = Site.build(helper, 9);
        s.tunnelX(2, 10, 4, 12);
        CreepingWall wall = startWall(s.level(), s.at(2, 4, 12));
        long base = wall.stopTick();
        for (int i = 0; i < 200; i++) {
            wall.swallowOne();
        }
        long ext = wall.stopTick() - base;
        int swallowed = wall.swallowed();
        s.finish(wall);
        if (swallowed != 200) helper.fail("飲んだ数 " + swallowed);
        else if (ext != WallTuning.maxExtensionTicks) helper.fail("延びが上限でない: " + ext + " tick(上限 " + WallTuning.maxExtensionTicks + ")");
        else helper.succeed();
    }

    /** 人から遠い枝は進めず、人が近づくとその時の距離から進み直す(決定 その 8 の案 c)。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "creeping_wall")
    public static void farBranchesWaitForPlayers(GameTestHelper helper) {
        Site s = Site.build(helper, 10);
        s.tunnelX(2, 60, 4, 12);
        CreepingWall wall = startWall(s.level(), s.at(2, 4, 12));
        wall.activeRadius = 12;
        wall.extraAnchors.add(s.at(2, 4, 12)); // 始まりのそばに人がいる
        long[] woke = {-1};
        helper.runAtTickTime(90, () -> {
            boolean near = wall.isFilled(s.at(12, 4, 12));
            boolean beyond = wall.isFilled(s.at(18, 4, 12));
            if (!near || beyond || wall.dormant.isEmpty()) {
                s.finish(wall);
                helper.fail("90 tick: 12 先 " + near + "・18 先 " + beyond + "(埋まってはいけない)・止めた升 " + wall.dormant.size());
                return;
            }
            wall.extraAnchors.clear();
            wall.extraAnchors.add(s.at(24, 4, 12)); // 人が奥へ移った(止めていた先頭から 10 ブロックほど)
            woke[0] = s.level().getGameTime();
        });
        helper.runAtTickTime(170, () -> {
            boolean resumed = wall.isFilled(s.at(18, 4, 12)) && wall.isFilled(s.at(26, 4, 12));
            s.finish(wall);
            if (!resumed) helper.fail("人が近づいても進み直さない");
            else helper.succeed();
        });
    }

    // ------------------------------------------------------------------ 前触れ・ひるむ

    /** 前触れの間は動かず、灯りが始まりに近い順に消えていく(奥の灯りは後まで残る)。前触れが終わると動き出す。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "creeping_wall")
    public static void omenPutsOutLightsFromTheFarSide(GameTestHelper helper) {
        Site s = Site.build(helper, 11);
        ServerLevel level = s.level();
        s.tunnelX(2, 62, 4, 12);
        BlockPos nearTorch = s.at(6, 4, 12), farTorch = s.at(50, 4, 12);
        level.setBlock(nearTorch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(farTorch, Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        CreepingWall wall = CreepingWalls.startWithOmen(level, s.at(2, 4, 12));
        wall.activeRadius = 0;
        helper.runAtTickTime(WallTuning.omenTicks / 3, () -> {
            boolean nearOut = !level.getBlockState(nearTorch).is(Blocks.TORCH);
            boolean farLit = level.getBlockState(farTorch).is(Blocks.TORCH);
            int filled = wall.filledCount();
            if (!nearOut || !farLit || filled != 0) {
                s.finish(wall);
                helper.fail("前触れの 1/3: 近い松明が消えた " + nearOut + "・遠い松明が残る " + farLit + "・埋まった升 " + filled + "(0 のはず)");
            }
        });
        helper.runAtTickTime(WallTuning.omenTicks + 30, () -> {
            boolean farOut = !level.getBlockState(farTorch).is(Blocks.TORCH);
            boolean moving = wall.filledCount() > 0;
            s.finish(wall);
            if (!farOut) helper.fail("前触れの終わりまでに奥の松明が消えない");
            else if (!moving) helper.fail("前触れの後に動き出さない");
            else helper.succeed();
        });
    }

    /** 殴るとひるんで一瞬止まる(壁全体)。続けて殴っても、間を置かないとひるまない。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 120, batch = "creeping_wall")
    public static void flinchesBrieflyWhenHit(GameTestHelper helper) {
        Site s = Site.build(helper, 12);
        s.tunnelX(2, 60, 4, 12);
        CreepingWall wall = startWall(s.level(), s.at(2, 4, 12));
        int[] at = {-1};
        boolean[] second = {true};
        helper.runAtTickTime(20, () -> {
            long now = s.level().getGameTime();
            boolean first = wall.flinch(now, 20);
            second[0] = wall.flinch(now, 20);
            at[0] = wall.filledCount();
            if (!first) {
                s.finish(wall);
                helper.fail("ひるまない");
            }
        });
        helper.runAtTickTime(36, () -> {
            int during = wall.filledCount();
            if (second[0] || during != at[0]) {
                s.finish(wall);
                helper.fail("続けてひるんだ " + second[0] + "・ひるんでいる間に進んだ " + at[0] + " → " + during);
            }
        });
        helper.runAtTickTime(80, () -> {
            boolean resumed = wall.filledCount() > at[0];
            s.finish(wall);
            if (!resumed) helper.fail("ひるんだ後に進み直さない");
            else helper.succeed();
        });
    }
}
