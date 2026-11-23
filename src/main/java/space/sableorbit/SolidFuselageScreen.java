package space.sableorbit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

final class SolidFuselageScreen extends FuselageModeScreen<SolidFuselageMenu> {

    SolidFuselageScreen(SolidFuselageMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, SolidFuselageMenu.WIDTH, SolidFuselageMenu.HEIGHT);
        inventoryLabelY = SolidFuselageMenu.INVENTORY_Y - 12;
    }

    @Override int modeY() { return 55; }
    @Override FuselageTile.Mode mode() { return menu.mode(); }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        String visible = font.plainSubstrByWidth(title.getString(), imageWidth - 16);
        graphics.drawString(font, visible, titleLabelX, titleLabelY, 0x404040, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0x404040, false);
    }

    @Override public void extractBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff171717);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xffc6c6c6);
        for (var slot : menu.slots) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xffeeeeee);
            graphics.fill(x - 1, y - 1, x + 16, y + 16, 0xff373737);
            graphics.fill(x, y, x + 16, y + 16, 0xff8b8b8b);
        }

        int charge = menu.charge();
        int width = (int) (112L * charge / Math.max(1, menu.capacity()));
        graphics.fill(leftPos + 46, topPos + 26, leftPos + 160, topPos + 35, 0xff373737);
        graphics.fill(leftPos + 47, topPos + 27, leftPos + 47 + width, topPos + 34, 0xffcc7e3c);
        graphics.drawString(font, Component.translatable("gui.sableorbits.fuselage.solid_amount", charge, menu.capacity()),
            leftPos + 46, topPos + 39, 0xff404040, false);
        drawMode(graphics);
    }
}
