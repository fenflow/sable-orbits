package space.sableorbit;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import java.util.*;

@EventBusSubscriber(modid="sableorbits",value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
public final class ConsoleScreen extends AbstractContainerScreen<ChestMenu> {
    private final Map<Integer,Button> controls = new HashMap<>();
    private final List<String> rows = new ArrayList<>();
    private List<String> previous = List.of();
    private boolean flight;
    public ConsoleScreen(ChestMenu menu,Inventory inventory,Component title) {
        super(menu,inventory,title);
        imageWidth = 360;
        imageHeight = 224;
    }
    @SubscribeEvent public static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(RocketPlacementOutline::init);
    }
    @SubscribeEvent public static void engines(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e) {
        e.registerBlockEntityRenderer(Engines.TILE.get(),ctx -> new EngineRenderer());
        e.registerBlockEntityRenderer(Engines.WHEEL_TILE.get(), ctx -> new ReactionWheelRenderer(ctx.getBlockRenderDispatcher()));
        e.registerBlockEntityRenderer(Fuselages.TILE.get(), ctx -> new FuselageRenderer());
        e.registerBlockEntityRenderer(Fuselages.SOLID_TILE.get(), ctx -> new FuselageRenderer());
    }
    @SubscribeEvent public static void register(RegisterMenuScreensEvent e) {
        e.register(ConsoleMenus.PIN.get(),PinScreen::new);
        e.register(ConsoleMenus.TYPE.get(),ConsoleScreen::new);
        e.register(FuselageMenu.TYPE.get(), FuselageScreen::new);
        e.register(SolidFuselageMenu.TYPE.get(), SolidFuselageScreen::new);
    }
    @Override protected void init() { super.init(); previous = List.of(); rebuild(); }
    @Override protected void containerTick() { rebuild(); }
    private String label(int i) {
        var item = menu.getSlot(i).getItem();
        return item.isEmpty() ? "" : item.getHoverName().getString();
    }
    private void button(int slot,int x,int y,int width,String caption) {
        var b = Button.builder(Component.literal(caption),w -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId,slot))
            .bounds(leftPos+x,topPos+y,width,16).build();
        controls.put(slot,addRenderableWidget(b));
    }
    private void rebuild() {
        var snapshot = new ArrayList<String>(54);
        for (int i=0;i<54;i++) snapshot.add(label(i));
        if (snapshot.equals(previous)) return;
        clearWidgets();
        previous = snapshot;
        controls.clear(); rows.clear();
        flight = menu.getSlot(19).getItem().is(Items.WRITABLE_BOOK);
        button(0,8,17,58,"Flight");
        button(1,68,17,75,"Stations");
        button(2,145,17,92,"Rendezvous");
        button(3,239,17,65,"Team");
        if (!label(4).isEmpty()) button(4,306,17,46,"PIN");
        if (flight) {
            button(19,122,43,106,"Write station disk");
            button(20,122,64,106,"Land at disk");
            button(21,122,85,106,"Transfer to planet");
        } else {
            for (int i=0;i<5;i++) {
                int slot = 9+3*i;
                String label = label(slot);
                if (label.isEmpty()) continue;
                int split = label.lastIndexOf(" · ");
                rows.add(split < 0 ? label : label.substring(0,split));
                button(slot,170,36+i*17,125,split < 0 ? "Select" : label.substring(split+3));
                controls.get(slot).active = !label.endsWith(" · Owner") && !label.endsWith(" · Member") && !label.endsWith(" · Invited") && !menu.getSlot(slot).getItem().is(Items.GRAY_DYE);
            }
            if (!label(45).isEmpty()) button(45,244,125,50,"Back");
            if (!label(53).isEmpty()) button(53,298,125,54,"Next");
        }
    }
    private boolean visibleSlot(Slot s) { return s.index >= 54 || (flight && s.index == 10); }
    @Override protected void renderSlot(GuiGraphics g, Slot s) { if (visibleSlot(s)) super.renderSlot(g,s); }
    @Override protected void renderSlotHighlight(GuiGraphics g,Slot s,int x,int y,float t) {
        if (visibleSlot(s)) super.renderSlotHighlight(g,s,x,y,t);
    }
    @Override protected void renderBg(GuiGraphics g,float t,int mx,int my) {
        int x=leftPos,y=topPos;
        g.fill(x-1,y-1,x+imageWidth+1,y+imageHeight+1,0xff758696);
        g.fill(x,y,x+imageWidth,y+imageHeight,0xff202934);
        g.fill(x+5,y+34,x+imageWidth-5,y+122,0xff111920);
        for (var s : menu.slots) if (visibleSlot(s)) {
            g.fill(x+s.x-1,y+s.y-1,x+s.x+17,y+s.y+17,0xff617280);
            g.fill(x+s.x,y+s.y,x+s.x+16,y+s.y+16,0xff293540);
        }
    }
    @Override protected void renderLabels(GuiGraphics g,int x,int y) {
        String orbit = label(0).replace("Flight · ","");
        g.drawString(font,"Orbital station computer",8,5,0xffeef4f6,false);
        g.drawString(font,orbit,imageWidth-8-font.width(orbit),5,0xff9edcc7,false);
        if (flight) g.drawString(font,"Navigation disk",47,41,0xffbacbd6,false);
        else {
            for (int i=0;i<rows.size();i++) g.drawString(font,font.plainSubstrByWidth(rows.get(i),154),10,40+17*i,0xffe3ebee,false);
            if (rows.isEmpty()) g.drawString(font,"No stations listed in this orbit.",10,43,0xffb9cad3,false);
        }
        g.drawString(font,playerInventoryTitle,8,128,0xffb9cad3,false);
    }
    @Override public void render(GuiGraphics g,int mx,int my,float t) {
        super.render(g,mx,my,t);
        if (hoveredSlot != null && visibleSlot(hoveredSlot)) renderTooltip(g,mx,my);
    }
}

