package com.yayuy.overgrownvegetation.research;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.yayuy.overgrownvegetation.port.saved.OvSavedData;

/**
 * <b>研究の進み</b>(2026-11-17 決定 23)。<b>世界で 1 つ</b>(オーバーワールドに保存。マルチでは仲間と分け合う)。
 *
 * <ul>
 *     <li>開いた節の集合。研究台の節(段 0)ははじめから開いているものとして扱い、ここには書かない。</li>
 *     <li>今の研究(1 つだけ): 節・進み(tick)・どの研究台で回しているか・払った標本(やめたときに返す。決定 23)。</li>
 * </ul>
 */
public final class ResearchData extends OvSavedData {

    private static final String ID = "overgrown_vegetation_research";

    public static final OvSavedData.Factory<ResearchData> FACTORY =
            new OvSavedData.Factory<>(ResearchData::new, ResearchData::load, null);

    /** 今の研究。 */
    public static final class Active {
        public final String node;
        public final GlobalPos bench;
        public final List<ItemStack> paid;
        public int progress;

        Active(String node, GlobalPos bench, List<ItemStack> paid, int progress) {
            this.node = node;
            this.bench = bench;
            this.paid = paid;
            this.progress = progress;
        }
    }

    private final Set<String> done = new LinkedHashSet<>();
    private @Nullable Active active;

    public ResearchData() {
    }

    public static ResearchData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID);
    }

    private static ResearchData load(CompoundTag tag, HolderLookup.Provider registries) {
        ResearchData d = new ResearchData();
        ListTag list = tag.getList("done", Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) d.done.add(list.getString(i));
        if (tag.contains("active", Tag.TAG_COMPOUND)) {
            CompoundTag a = tag.getCompound("active");
            ResourceLocation dim = ResourceLocation.tryParse(a.getString("dim"));
            if (dim != null) {
                GlobalPos pos = GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(a.getLong("pos")));
                List<ItemStack> paid = new ArrayList<>();
                ListTag items = a.getList("paid", Tag.TAG_COMPOUND);
                for (int i = 0; i < items.size(); i++) {
                    java.util.Optional.of(ItemStack.of(items.getCompound(i))).filter(s -> !s.isEmpty()).ifPresent(paid::add);
                }
                d.active = new Active(a.getString("node"), pos, paid, a.getInt("progress"));
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (String s : done) list.add(StringTag.valueOf(s));
        tag.put("done", list);
        if (active != null) {
            CompoundTag a = new CompoundTag();
            a.putString("node", active.node);
            a.putString("dim", active.bench.dimension().location().toString());
            a.putLong("pos", active.bench.pos().asLong());
            a.putInt("progress", active.progress);
            ListTag items = new ListTag();
            for (ItemStack s : active.paid) {
                if (!s.isEmpty()) items.add(s.save(new CompoundTag()));
            }
            a.put("paid", items);
            tag.put("active", a);
        }
        return tag;
    }

    public boolean isDone(ResearchTree tree, String node) {
        ResearchTree.Node n = tree.node(node);
        return (n != null && n.isRoot()) || done.contains(node);
    }

    public Set<String> done() {
        return Collections.unmodifiableSet(done);
    }

    public @Nullable Active active() {
        return active;
    }

    void markDone(String node) {
        if (done.add(node)) setDirty();
    }

    void forget(String node) {
        if (done.remove(node)) setDirty();
    }

    /** すべて閉じる(開発用の命令・GameTest)。走っている研究は先に {@link Research#cancel} で止めること。 */
    public void clearAll() {
        done.clear();
        active = null;
        setDirty();
    }

    void setActive(@Nullable Active a) {
        active = a;
        setDirty();
    }
}
