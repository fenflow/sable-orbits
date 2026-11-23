package space.sableorbit;

import com.hbm.inventory.gui.GuiUtil;
import dev.golem.core.gui.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

abstract class FuselageModeScreen<T extends AbstractContainerMenu> extends CompatContainerScreen<T> {
    static final ResourceLocation BARREL_UI = ResourceLocation.fromNamespaceAndPath("hbm", "textures/gui/storage/gui_barrel.png");
    FuselageModeScreen(T menu, Inventory inventory, Component title, int width, int height) {
        super(menu, inventory, title, width, height);
    }
    abstract int modeY();
    abstract FuselageTile.Mode mode();
    final void drawMode(GuiGraphics graphics) {
        GuiDraw.blit(graphics, BARREL_UI, leftPos + imageWidth - 25, topPos + modeY(), 176, mode().ordinal() * 18,
            18, 18, 256, 256);
    }
    @Override protected boolean mouseClicked(MouseInput input, boolean doubleClick) {
        if (minecraft.gameMode == null || !isHovering(imageWidth - 25, modeY(), 18, 18, input.x(), input.y())) return false;
        GuiUtil.playClickSound();
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
        return true;
    }
}
