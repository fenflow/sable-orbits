package space.sableorbit;

import com.hbm.items.weapon.ItemCustomMissilePart.PartType;
import com.ntmspace.rocket.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.*;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.*;
import java.util.*;
import java.util.function.Supplier;

final class Engines {
    static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("sableorbits");
    static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE,"sableorbits");
    static final Supplier<EngineBlock> BLOCK = BLOCKS.register("rocket_engine", () -> new EngineBlock());
    static final Supplier<BlockEntityType<EngineTile>> TILE = TYPES.register("rocket_engine",
        () -> BlockEntityType.Builder.of(EngineTile::new,BLOCK.get()).build(null));
    static final Supplier<ReactionWheelBlock> WHEEL = BLOCKS.register("reaction_wheel", ReactionWheelBlock::new);
    static final Supplier<BlockEntityType<ReactionWheelTile>> WHEEL_TILE = TYPES.register("reaction_wheel",
        () -> BlockEntityType.Builder.of(ReactionWheelTile::new, WHEEL.get()).build(null));
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("sableorbits");
    private static final Supplier<net.minecraft.world.item.BlockItem> WHEEL_ITEM = ITEMS.registerSimpleBlockItem("reaction_wheel", WHEEL);
    static void register(IEventBus bus) {
        BLOCKS.register(bus);
        TYPES.register(bus);
        ITEMS.register(bus);
        bus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.REDSTONE_BLOCKS) event.accept(WHEEL_ITEM.get());
        });
        bus.addListener((RegisterCapabilitiesEvent e) -> e.registerBlockEntity(Capabilities.FluidHandler.BLOCK, TILE.get(),
            (be, side) -> be.inlet(side) ? be : null));
    }
    static List<BlockPos> cells(RocketPart engine, BlockPos inlet, Direction nozzle) {
        int width = Math.max(1,(int)Math.ceil(Math.max(engine.topRadius(),engine.bottomRadius())));
        int length = Math.max(1,(int)Math.ceil(engine.height()));
        var u = nozzle.getAxis().isVertical() ? Direction.EAST : nozzle.getClockWise();
        var v = nozzle.getAxis().isVertical() ? Direction.SOUTH : Direction.UP;
        var cells = new ArrayList<BlockPos>();
        for (int h=0; h<length; h++)
            for (int i=0; i<width; i++)
                for (int j=0; j<width; j++)
                    cells.add(inlet.relative(nozzle,h).relative(u,i-width/2).relative(v,j-width/2));
        return cells;
    }
    static BlockPos inletAtFace(RocketPart engine, BlockPos contact, Direction face, Direction nozzle) {
        // Put the nearest face of the occupied volume against the clicked surface,
        // including when the inlet is at the far end or halfway along an edge.
        int inward = 0;
        for (var cell : cells(engine,BlockPos.ZERO,nozzle)) {
            inward = Math.max(inward,-(cell.getX()*face.getStepX() + cell.getY()*face.getStepY() + cell.getZ()*face.getStepZ()));
        }
        return contact.relative(face,inward);
    }
    static void place(PlayerInteractEvent.RightClickBlock e) {
        var item = e.getItemStack();
        if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString().equals("hbm:solid_fuel")
                && e.getLevel().getBlockEntity(e.getPos()) instanceof EngineTile tile && tile.inlet(e.getFace())) {
            if (e.getLevel().isClientSide || tile.loadSolid()) {
                if (!e.getLevel().isClientSide) item.consume(1,e.getEntity());
                e.setCanceled(true);
                e.setCancellationResult(InteractionResult.sidedSuccess(e.getLevel().isClientSide));
            }
            return;
        }
        var part = RocketParts.get(item);
        if (part == null || part.type() != PartType.THRUSTER) return;
        var player = e.getEntity();
        var level = e.getLevel();
        var ctx = new BlockPlaceContext(new UseOnContext(player,e.getHand(),e.getHitVec()));
        var view = player.getLookAngle();
        var localView = new org.joml.Vector3d(view.x,view.y,view.z);
        var ship = dev.ryanhcode.sable.Sable.HELPER.getContaining(level,e.getPos());
        if (ship != null) ship.logicalPose().orientation().transformInverse(localView);
        var look = Direction.getNearest(localView.x,localView.y,localView.z);
        var nozzle = player.isShiftKeyDown() ? look.getOpposite() : look;
        var first = inletAtFace(part,ctx.getClickedPos(),ctx.getClickedFace(),nozzle);
        var cells = cells(part,first,nozzle);
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.FAIL);
        if (cells.stream().anyMatch(pos -> !level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || !level.getBlockState(pos).canBeReplaced(ctx) || !player.mayUseItemAt(pos,ctx.getClickedFace(),item)
                || !level.mayInteract(player,pos))) return;
        if (level instanceof ServerLevel) {
            var state = BLOCK.get().defaultBlockState().setValue(EngineBlock.FACING,nozzle);
            for (var cell : cells) {
                var skin = cell.equals(first) ? 0 : (cell.relative(nozzle).equals(first) || !cells.contains(cell.relative(nozzle)) ? 2 : 1);
                level.setBlock(cell,state.setValue(EngineBlock.SKIN,skin),3);
                var tile = (EngineTile)level.getBlockEntity(cell);
                tile.initialize(part,first.subtract(cell));
                level.sendBlockUpdated(cell,tile.getBlockState(),tile.getBlockState(),Block.UPDATE_CLIENTS);
            }
            ((EngineTile)level.getBlockEntity(first)).refreshFluidPorts();
            level.updateNeighborsAt(first,BLOCK.get());
            level.getBlockState(first).updateNeighbourShapes(level,first,Block.UPDATE_ALL);
            item.consume(1,player);
        }
        e.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
    }
}


