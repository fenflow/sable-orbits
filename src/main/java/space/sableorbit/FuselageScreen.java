package space.sableorbit;

import com.hbm.inventory.gui.GuiUtil;
import com.hbm.inventory.gui.Production2Gui;
import dev.golem.core.gui.GuiDraw;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.Optional;

final class FuselageScreen extends FuselageModeScreen<FuselageMenu> {
    private static final int PANEL = 0xffc6c6c6;
    private static final int LABEL = 0x404040;

    FuselageScreen(FuselageMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.width(), menu.height());
    }

    @Override FuselageTile.Mode mode() { return menu.mode(); }
    @Override int modeY() { return menu.tankTop() + 20; }

    @Override public void extractBackground(GuiGraphics graphics, int mouseX, int mouseY, float tick) {
        if (menu.tankCount() == 1) {
            GuiDraw.blit(graphics, BARREL_UI, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
            // The barrel artwork includes two filter slots. A fuselage has a fixed fluid.
            graphics.fill(leftPos + 6, topPos + 15, leftPos + 27, topPos + 71, PANEL);
        } else {
            graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff181818);
            graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xff555555);
            graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 3, topPos + imageHeight - 3, 0xffeeeeee);
            graphics.fill(leftPos + 3, topPos + 3, leftPos + imageWidth - 3, topPos + imageHeight - 3, PANEL);
            for (int i = 0; i < menu.tankCount(); i++) {
                if (menu.adjustable()) GuiDraw.blit(graphics, BARREL_UI, leftPos + menu.tankLeft(i) - 25, topPos + menu.tankTop(),
                    5, 14, 25, 58, 256, 256);
                GuiDraw.blit(graphics, BARREL_UI, leftPos + menu.tankLeft(i), topPos + menu.tankTop(),
                    30, 14, FuselageMenu.TANK_WIDTH, 58, 256, 256);
            }
            GuiDraw.blit(graphics, BARREL_UI, leftPos + (imageWidth - 164) / 2, topPos + menu.inventoryY() - 2,
                6, 82, 164, 78, 256, 256);
        }
        for (int i = 0; i < menu.tankCount(); i++) {
            Production2Gui.drawTank(graphics, menu.tank(i), leftPos + menu.tankLeft(i) + 41, topPos + menu.tankTop() + 55, 34, 52);
        }
        drawMode(graphics);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        drawCentered(graphics, title.getString(), imageWidth / 2, 6, imageWidth - 16);
        if (menu.tankCount() == 2) {
            for (int i = 0; i < menu.tankCount(); i++) {
                int center = menu.tankLeft(i) + FuselageMenu.TANK_WIDTH / 2;
                drawCentered(graphics, menu.tank(i).getDeclaredFluid().getDisplayName().getString(), center, 16, FuselageMenu.TANK_WIDTH - 6);
            }
        }
        graphics.drawString(font, playerInventoryTitle, (imageWidth - 160) / 2, menu.inventoryY() - 12, LABEL, false);
    }

    private void drawCentered(GuiGraphics graphics, String label, int center, int y, int width) {
        String visible = font.plainSubstrByWidth(label, width);
        graphics.drawString(font, visible, center - font.width(visible) / 2, y, LABEL, false);
    }

    @Override protected void extractOwnTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int i = 0; i < menu.tankCount(); i++) {
            if (isHovering(menu.tankLeft(i) + 41, menu.tankTop() + 3, 34, 52, mouseX, mouseY)) {
                GuiDraw.tooltip(graphics, font, GuiUtil.tankTooltip(menu.tank(i)), Optional.empty(), mouseX, mouseY);
            }
        }
    }

}
