package space.sableorbit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

final class SolidPropellants {
    private static final ResourceLocation REGULAR = ResourceLocation.fromNamespaceAndPath("hbm", "solid_fuel");
    private static final ResourceLocation ROCKET = ResourceLocation.fromNamespaceAndPath("hbm", "rocket_fuel");
    private static final ResourceLocation BALEFIRE = ResourceLocation.fromNamespaceAndPath("hbm", "solid_fuel_bf");

    private SolidPropellants() {}

    static int units(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        var item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (REGULAR.equals(item)) return 1000;
        if (ROCKET.equals(item)) return 2000;
        if (BALEFIRE.equals(item)) return 4000;
        return 0;
    }
}
