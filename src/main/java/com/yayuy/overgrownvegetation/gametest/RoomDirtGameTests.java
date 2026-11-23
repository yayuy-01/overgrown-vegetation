package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.DirtSpec;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModVent;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.survival.decon.GearDirt;
import com.yayuy.overgrownvegetation.survival.room.RoomAir;
import com.yayuy.overgrownvegetation.survival.room.RoomCache;
import com.yayuy.overgrownvegetation.survival.room.RoomDirt;
import com.yayuy.overgrownvegetation.survival.room.RoomScan;
import com.yayuy.overgrownvegetation.survival.vent.AirFilters;
import com.yayuy.overgrownvegetation.survival.vent.VentBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 部屋が汚れる(2026-11-14 サバイバルの背骨 段 3c): 汚れた装備・覆われた○○を持って閉じた部屋にいると部屋の濃度が上がる(きれいな物では上がらない)・
 * ドアを 2 枚続けた玄関では前室だけが汚れ、奥の部屋はきれいなまま・ドアを開けてつながると薄まり、閉じれば戻る・汚れた部屋では暴露量がたまる・
 * 汚れはゆっくり減り、換気機の範囲では速く減ってフィルターが詰まる・保存と復元。期待値は設計値({@code spore_field.json} の {@code dirt})から引く。
 *
 * <p>テスト場の外の濃度は 0(汚染の無い所)。汚れのある部屋はそれでも調べられる(段 3c で足した確かめ)ことも、ここで試す。
 * 部屋の判定は 1 tick にたどる回数に上限があるので {@link RoomCache#unlimitedForTests} を立てて自分だけのバッチで走らせる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class RoomDirtGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterRoomDirtGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(RoomDirtGameTests.class);
        }

        private Registration() {
        }
    }

    private RoomDirtGameTests() {
    }

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "survival_room_dirt";

    private static DirtSpec spec() {
        return DesignData.sporeField().dirt();
    }

    /** 前室 A(x=2)と奥 B(x=4)を、x=3 のドアで仕切った板材の小屋(中はそれぞれ 1×2×1)。 */
    static void buildVestibule(GameTestHelper helper, boolean doorOpen) {
        for (int x = 1; x <= 5; x++)
            for (int y = 2; y <= 5; y++)
                for (int z = 1; z <= 3; z++) {
                    boolean inside = z == 2 && (y == 3 || y == 4) && x >= 2 && x <= 4;
                    if (inside && x == 3) continue;
                    helper.setBlock(new BlockPos(x, y, z), inside ? Blocks.AIR.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState());
                }
        setDoor(helper, doorOpen);
    }

    static void setDoor(GameTestHelper helper, boolean open) {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.OPEN, open);
        helper.setBlock(new BlockPos(3, 3, 2), lower);
        helper.setBlock(new BlockPos(3, 4, 2), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    static Player dirtyPlayerAt(GameTestHelper helper, BlockPos abs) {
        Player p = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
        p.setPos(abs.getX() + 0.5D, abs.getY(), abs.getZ() + 0.5D);
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack st = new ItemStack(switch (slot) {
                case HEAD -> Items.IRON_HELMET;
                case CHEST -> Items.IRON_CHESTPLATE;
                case LEGS -> Items.IRON_LEGGINGS;
                default -> Items.IRON_BOOTS;
            });
            GearDirt.setDirt(st, spec().full());
            p.setItemSlot(slot, st);
        }
        return p;
    }

    /**
     * 玄関の前室で汚れた装備の人が過ごすと前室だけが汚れ(奥はきれい)、地点の読みにも出る(外は 0%)。奥のドアを開けると 2 部屋分に薄まり、
     * 閉じれば前室に戻る。きれいな装備では汚れない。覆われた○○ も 1 枠 1 点で汚す。汚れた部屋では暴露量がたまる。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void dirtyGearContaminatesOnlyTheVestibule(GameTestHelper helper) {
        SporeAmbient.isolate(helper, 40); // 隣のテスト場から広がる胞子の場の浮遊を読まない(技が直に与えた分だけを見る。2026-11-18 揺れるテストの調べ)
        ServerLevel level = helper.getLevel();
        RoomCache.unlimitedForTests = true;
        RoomDirt.get(level).clearAll();
        try {
            buildVestibule(helper, false);
            BlockPos a = helper.absolutePos(new BlockPos(2, 3, 2));
            BlockPos b = helper.absolutePos(new BlockPos(4, 3, 2));
            RoomScan ra = RoomCache.roomAt(level, a), rb = RoomCache.roomAt(level, b);
            if (ra == null || rb == null || ra.volume() != 2 || rb.volume() != 2) {
                helper.fail("前室と奥が 2 マスずつの部屋にならない(前提): " + (ra == null ? "null" : ra.volume()) + " / " + (rb == null ? "null" : rb.volume()));
                return;
            }
            // きれいな人は汚さない
            Player clean = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
            clean.setPos(a.getX() + 0.5D, a.getY(), a.getZ() + 0.5D);
            clean.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            for (int i = 0; i < 10; i++) GearDirt.step(level, clean, 20, 0.0D);
            if (RoomAir.extraPercent(level, ra) != 0.0D) {
                helper.fail("きれいな装備で部屋が汚れた");
                return;
            }
            // 汚れた人が 2 秒
            Player p = dirtyPlayerAt(helper, a);
            double points = GearDirt.carriedPoints(p);
            if (points != 12.0D) {
                helper.fail("持っている汚れ " + points + " 点(4 か所 × 3 段 = 12 のはず)");
                return;
            }
            int seconds = 2;
            for (int i = 0; i < seconds; i++) GearDirt.step(level, p, 20, 0.0D);
            DirtSpec s = spec();
            double expect = Math.min(s.roomMaxPercent(), points * s.roomPerPoint() * seconds * s.roomRefVolume() / 2.0D);
            double got = RoomAir.extraPercent(level, ra);
            if (Math.abs(got - expect) > 1e-6 || RoomAir.extraPercent(level, rb) != 0.0D) {
                helper.fail(String.format("前室 %.3f%%(%.3f のはず)・奥 %.3f%%", got, expect, RoomAir.extraPercent(level, rb)));
                return;
            }
            // 安い確かめは汚した部屋の箱の中だけ(床の下・奥の部屋では部屋を調べない)
            if (!RoomAir.dirtNear(level, a) || RoomAir.dirtNear(level, a.below(3)) || RoomAir.dirtNear(level, b)) {
                helper.fail("汚れの確かめの箱が違う: 前室 " + RoomAir.dirtNear(level, a) + "・床の下 " + RoomAir.dirtNear(level, a.below(3)) + "・奥 " + RoomAir.dirtNear(level, b));
                return;
            }
            SporeConcentration.clearCacheForTests();
            double read = SporeConcentration.percentAt(level, a);
            if (Math.abs(read - got) > 1e-6 || SporeConcentration.percentAt(level, b) != 0.0D) {
                helper.fail(String.format("地点の読み: 前室 %.3f%%(%.3f のはず)・奥 %.3f%%", read, got, SporeConcentration.percentAt(level, b)));
                return;
            }
            // 汚れた部屋では、きれいな装備の人にも暴露量がゆっくりたまる(部屋の汚れ 1% あたり roomSeepPerPercent。2026-11-14 決定 35)
            Player guest = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
            guest.setPos(a.getX() + 0.5D, a.getY(), a.getZ() + 0.5D);
            for (int i = 0; i < 10; i++) SporeExposureTracker.tick(level, guest, 20);
            double expectDose = got * s.roomSeepPerPercent() * 10.0D;
            if (Math.abs(SporeExposureTracker.dose(guest) - expectDose) > 1e-6) {
                helper.fail("汚れた部屋(" + read + "%)で 10 秒の暴露量 " + SporeExposureTracker.dose(guest) + "(" + expectDose + " のはず)");
                return;
            }
            // 奥のドアを開ける → つながって薄まる
            setDoor(helper, true);
            RoomScan merged = RoomCache.roomAt(level, a);
            if (merged == null || merged.volume() <= 4) {
                helper.fail("ドアを開けてもつながらない: " + (merged == null ? "null" : merged.volume()));
                return;
            }
            double mixed = RoomAir.extraPercent(level, merged);
            if (Math.abs(mixed - got * 2.0D / merged.volume()) > 1e-6) {
                helper.fail(String.format("つながった部屋 %.3f%%(%.3f のはず)", mixed, got * 2.0D / merged.volume()));
                return;
            }
            // 閉じれば前室に戻る(汚れは置いた側に残る)
            setDoor(helper, false);
            if (Math.abs(RoomAir.extraPercent(level, RoomCache.roomAt(level, a)) - got) > 1e-6
                    || RoomAir.extraPercent(level, RoomCache.roomAt(level, b)) != 0.0D) {
                helper.fail("ドアを閉め直したら汚れが動いた");
                return;
            }
            // 覆われた○○ は 1 枠 1 点
            Player carrier = GtCompat.mockPlayer(helper, GameType.SURVIVAL);
            carrier.getInventory().add(new ItemStack(ModItems.OVERGROWN_BEEF.get(), 10));
            carrier.getInventory().add(new ItemStack(ModItems.OVERGROWN_BONE.get(), 3));
            if (GearDirt.carriedPoints(carrier) != 2.0D) {
                helper.fail("覆われた○○ 2 枠で " + GearDirt.carriedPoints(carrier) + " 点");
                return;
            }
            // 上限
            for (int i = 0; i < 600; i++) GearDirt.step(level, p, 20, 0.0D);
            if (Math.abs(RoomAir.extraPercent(level, RoomCache.roomAt(level, a)) - s.roomMaxPercent()) > 1e-6) {
                helper.fail("上限 " + s.roomMaxPercent() + " で止まらない: " + RoomAir.extraPercent(level, RoomCache.roomAt(level, a)));
                return;
            }
            helper.succeed();
        } finally {
            RoomCache.unlimitedForTests = false;
            RoomDirt.get(level).clearAll();
            SporeConcentration.clearCacheForTests();
        }
    }

    /** 汚れは半減期でゆっくり減る。稼働中の換気機の範囲では速く減り、抜いた分だけフィルターが詰まる。保存して読み戻せる。 */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 40)
    public static void ventPushesRoomDirtBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RoomCache.unlimitedForTests = true;
        RoomDirt dirt = RoomDirt.get(level);
        dirt.clearAll();
        BlockPos ventRel = new BlockPos(6, 2, 6);
        try {
            buildVestibule(helper, false);
            BlockPos a = helper.absolutePos(new BlockPos(2, 3, 2));
            RoomScan ra = RoomCache.roomAt(level, a);
            DirtSpec s = spec();
            RoomAir.contaminate(level, ra, s.roomMaxPercent());
            double p0 = RoomAir.extraPercent(level, ra);
            // 保存と復元
            CompoundTag tag = dirt.save(new CompoundTag(), level.registryAccess());
            RoomDirt copy = RoomDirt.load(tag, level.registryAccess());
            if (Math.abs(copy.percentIn(ra) - p0) > 1e-9 || !copy.anyNear(a, 1)) {
                helper.fail("保存と復元で部屋の汚れが変わった: " + copy.percentIn(ra) + " / " + p0);
                return;
            }
            // 換気機なし: 半減期どおり
            dirt.decay(level, 60.0D);
            double p1 = RoomAir.extraPercent(level, ra);
            double expectSlow = p0 * Math.pow(0.5D, 60.0D / s.roomHalfLifeSeconds());
            if (Math.abs(p1 - expectSlow) > 1e-6) {
                helper.fail(String.format("換気機なしで 60 秒: %.4f%%(%.4f のはず)", p1, expectSlow));
                return;
            }
            // 換気機あり: 速く減り、フィルターが詰まる
            helper.setBlock(ventRel, ModVent.VENTILATOR.get().defaultBlockState());
            VentBlockEntity vent = (VentBlockEntity) helper.getBlockEntity(ventRel);
            vent.energyPort().receiveEnergy(DesignData.sporeField().vent().energyBuffer(), false);
            vent.setFilter(new ItemStack(ModVent.AIR_FILTER.get()));
            vent.tickOnce();
            if (!vent.running()) {
                helper.fail("換気機が回らない(前提)");
                return;
            }
            dirt.decay(level, s.roomVentHalfLifeSeconds());
            double p2 = RoomAir.extraPercent(level, ra);
            if (Math.abs(p2 - p1 * 0.5D) > 1e-6) {
                helper.fail(String.format("換気機ありで半減期 %.0f 秒: %.4f%%(%.4f のはず)", s.roomVentHalfLifeSeconds(), p2, p1 * 0.5D));
                return;
            }
            if (s.roomVentFilterLoadPerPercent() > 0.0D && !(AirFilters.used(vent.filter()) > 0)) {
                helper.fail("部屋の汚れを抜いてもフィルターが詰まらない");
                return;
            }
            // 減り切れば消える
            dirt.decay(level, s.roomVentHalfLifeSeconds() * 40.0D);
            if (!dirt.isEmpty()) {
                helper.fail("十分に時間がたっても汚れが残る");
                return;
            }
            helper.succeed();
        } finally {
            RoomCache.unlimitedForTests = false;
            dirt.clearAll();
            helper.setBlock(ventRel, Blocks.AIR);
        }
    }
}
