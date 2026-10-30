package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.G1Table;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.BlightFernPlacement;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 苗床の中身: {@code g1.json nursery.intervalTicks}(仮 5 分)ごとに萌芽体を 1 体、苗床の上に生む。
 * 周り({@code aliveRadius})に生きている萌芽体が {@code maxAlive} 以上なら待つ。生んだ数が {@code maxTotal} に届いたら枯れて止まる
 * (萌芽体は歩いて根付き、その升の放出を増やすので、1 つの苗床が放出を際限なく増やさないように。数は仮決め)。
 * {@code g4Chance} の確率で {@code g4Types} から 1 種を生む枠(今は 0 と空)。/ov corruption false の間は待つ。
 */
public class ParasiteNurseryBlockEntity extends BlockEntity {

    private int clock;
    private int born;

    public ParasiteNurseryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PARASITE_NURSERY.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ParasiteNurseryBlockEntity be) {
        if (level instanceof ServerLevel server) {
            be.tick(server, pos);
        }
    }

    private void tick(ServerLevel level, BlockPos pos) {
        if (CorruptionSwitch.isOff()) {
            return;
        }
        G1Table g = DesignData.g1();
        if (born >= g.nurseryMaxTotal) {
            return;
        }
        if (++clock < g.nurseryIntervalTicks) {
            if ((clock & 63) == 0) setChanged();
            return;
        }
        clock = 0;
        setChanged();
        produce(level, pos, g);
    }

    /** 1 体生む(上が塞がっている・周りに多すぎるときは生まない)。生んだら true。 */
    public boolean produce(ServerLevel level, BlockPos pos, G1Table g) {
        BlockPos at = pos.above();
        if (!level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
            return false;
        }
        int alive = level.getEntitiesOfClass(BlightFernEntity.class, new AABB(pos).inflate(g.nurseryAliveRadius)).size();
        if (alive >= g.nurseryMaxAlive) {
            return false;
        }
        boolean ok = false;
        if (g.nurseryG4Chance > 0.0D && !g.nurseryG4Types.isEmpty() && level.random.nextDouble() < g.nurseryG4Chance) {
            // G4 以上を生む枠(2026-10-22 決定「苗床から G4 以上が生まれてもよい」。何をいつ生むかは司令塔の苗床の生産と一緒に後で)
            ResourceLocation id = ResourceLocation.tryParse(g.nurseryG4Types.get(level.random.nextInt(g.nurseryG4Types.size())));
            EntityType<?> type = id != null ? BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) : null;
            if (type != null && type.spawn(level, at, MobSpawnType.MOB_SUMMONED) != null) {
                ok = true;
            }
        }
        if (!ok) {
            ok = BlightFernPlacement.spawn(level, at, MobSpawnType.MOB_SUMMONED) != null;
        }
        if (ok) {
            born++;
            setChanged();
        }
        return ok;
    }

    public int born() {
        return born;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Clock", clock);
        tag.putInt("Born", born);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        clock = tag.getInt("Clock");
        born = tag.getInt("Born");
    }
}
