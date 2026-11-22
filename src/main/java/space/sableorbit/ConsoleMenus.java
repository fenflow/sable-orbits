package space.sableorbit;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.*;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

final class ConsoleMenus {
    static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU,"sableorbits");
    static final Supplier<MenuType<ChestMenu>> TYPE = MENUS.register("station_computer",
        () -> new MenuType<>(ConsoleMenus::clientMenu, FeatureFlags.DEFAULT_FLAGS));
    private static ChestMenu clientMenu(int id,net.minecraft.world.entity.player.Inventory inventory) {
        return new ChestMenu(TYPE.get(),id,inventory,new SimpleContainer(54),6);
    }
}
