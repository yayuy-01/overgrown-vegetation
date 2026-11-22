package com.yayuy.overgrownvegetation.port.registries;

import com.yayuy.overgrownvegetation.port.attachment.AttachmentType;
import com.yayuy.overgrownvegetation.port.component.DataComponentType;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * 【1.20.1 だけの物・互換の層】NeoForge の {@code NeoForgeRegistries.Keys} のうち、本流が使う鍵。
 * 1.20.1 にはこの登録簿が無いので、{@link DeferredRegister} が「この中だけで持つ」目印に使う(本物の登録簿は作らない)。
 */
public final class NeoForgeRegistries {

    private NeoForgeRegistries() {
    }

    public static final class Keys {
        private Keys() {
        }

        public static final ResourceKey<Registry<AttachmentType<?>>> ATTACHMENT_TYPES =
                ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("overgrown_vegetation", "port_attachment_types"));

        /** バニラ 1.20.5 の {@code Registries.DATA_COMPONENT_TYPE} の代わり。 */
        public static final ResourceKey<Registry<DataComponentType<?>>> DATA_COMPONENT_TYPE =
                ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath("overgrown_vegetation", "port_data_component_types"));
    }
}
