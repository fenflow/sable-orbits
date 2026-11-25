package space.sableorbit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

final class PinScreen extends AbstractContainerScreen<PinMenu> {
    private EditBox digits;
    private final boolean setup;
    private final boolean manage;

    PinScreen(PinMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        setup = title.getString().equals(PinMenu.SETUP);
        manage = title.getString().equals(PinMenu.MANAGE);
        imageWidth = 290;
        imageHeight = manage ? 130 : 114;
    }

    @Override protected void init() {
        super.init();
        digits = new EditBox(font, leftPos + (imageWidth-86)/2, topPos + (manage ? 54 : 26), 86, 20, Component.literal("Four-digit PIN"));
        if (manage) digits.setHint(Component.literal("New PIN"));
        digits.setMaxLength(4);
        digits.setFilter(value -> value.chars().allMatch(c -> c >= '0' && c <= '9'));
        addRenderableWidget(digits);
        setInitialFocus(digits);
        var submit = addRenderableWidget(Button.builder(Component.literal(manage ? "Save PIN" : setup ? "Set PIN" : "Rendezvous"), b -> submit())
            .bounds(leftPos + (imageWidth-104)/2, topPos + imageHeight-29, 104, 20).build());
        submit.active = false;
        digits.setResponder(value -> submit.active = StationAccess.validPin(value));
    }

    private void submit() {
        if (StationAccess.validPin(digits.getValue())) PacketDistributor.sendToServer(new PinMenu.Submit(menu.containerId, digits.getValue()));
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { submit(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override protected void renderBg(GuiGraphics g, float t, int mx, int my) {
        g.fill(leftPos-1, topPos-1, leftPos+imageWidth+1, topPos+imageHeight+1, 0xff758696);
        g.fill(leftPos, topPos, leftPos+imageWidth, topPos+imageHeight, 0xff202934);
    }

    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        g.drawCenteredString(font, title, imageWidth/2, 9, 0xffeef4f6);
        if (setup) g.drawCenteredString(font, "The PIN will be used by other stations to do rendezvous", imageWidth/2, 53, 0xffbacbd6);
        if (manage) {
            var current = menu.displayedPin();
            if (menu.legacyPin()) g.drawCenteredString(font, "Earlier PIN is active. Set a new PIN to view it.", imageWidth/2, 32, 0xffbacbd6);
            else if (!current.isEmpty()) g.drawCenteredString(font, "Current PIN   " + current, imageWidth/2, 32, 0xffbacbd6);
        }
        String message = switch (menu.error()) {
            case 1 -> "Enter exactly four digits.";
            case 2 -> "PIN rejected or rendezvous unavailable.";
            case 3 -> "Station access has changed.";
            case 4 -> "Wait a second before trying again.";
            default -> "";
        };
        g.drawCenteredString(font, message, imageWidth/2, imageHeight-44, 0xffffbd84);
    }
}
