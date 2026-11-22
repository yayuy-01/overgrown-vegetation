package com.yayuy.overgrownvegetation.gear.attach;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModArmorAttach;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import com.yayuy.overgrownvegetation.port.network.StreamCodec;
import com.yayuy.overgrownvegetation.port.network.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import com.yayuy.overgrownvegetation.port.network.RegisterPayloadHandlersEvent;
import com.yayuy.overgrownvegetation.port.network.PayloadRegistrar;

/**
 * 防具の付け物の通信(2026-11-21 夜)。共有の通信とは別の登録で、版を自分で持つ。版 1: ベルトの小物入れを開く(C2S、中身なし)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class AttachNetwork {

    private AttachNetwork() {
    }

    /** ベルトの小物入れを開く(キーから)。 */
    public record OpenBelt() implements CustomPacketPayload {
        public static final OpenBelt INSTANCE = new OpenBelt();
        public static final Type<OpenBelt> TYPE = new Type<>(OvergrownVegetation.id("open_belt_pouch"));
        public static final StreamCodec<ByteBuf, OpenBelt> STREAM_CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("armor-attach-1");
        registrar.playToServer(OpenBelt.TYPE, OpenBelt.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) openBelt(sp);
        }));
    }

    /** 脚の防具にベルトが付いていれば小物入れを開く。開いたら true。 */
    public static boolean openBelt(ServerPlayer player) {
        if (player.isSpectator() || player.containerMenu != player.inventoryMenu) return false;
        if (ArmorAttachments.count(player.getItemBySlot(EquipmentSlot.LEGS), ModArmorAttach.STORAGE_BELT.get()) <= 0) {
            player.displayClientMessage(Component.translatable("armor_attachment.overgrown_vegetation.no_belt"), true);
            return false;
        }
        int size = BeltPouchMenu.configuredSize();
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> BeltPouchMenu.server(id, inv, p),
                Component.translatable("container.overgrown_vegetation.belt_pouch")), buf -> buf.writeVarInt(size));
        return true;
    }
}
