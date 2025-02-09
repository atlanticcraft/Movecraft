package net.countercraft.movecraft.compat.v1_17_R1;

import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.Rotation;
import net.countercraft.movecraft.WorldHandler;
import net.countercraft.movecraft.craft.Craft;
import net.countercraft.movecraft.utils.CollectionUtils;
import net.countercraft.movecraft.utils.MathUtils;
import net.minecraft.core.BaseBlockPosition;
import net.minecraft.core.BlockPosition;
import net.minecraft.server.level.LightEngineThreaded;
import net.minecraft.server.level.WorldServer;
import net.minecraft.server.network.PlayerConnection;
import net.minecraft.world.level.NextTickListEntry;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.TileEntity;
import net.minecraft.world.level.block.state.IBlockData;
import net.minecraft.world.level.chunk.Chunk;
import net.minecraft.world.level.chunk.ChunkSection;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.v1_17_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_17_R1.block.data.CraftBlockData;
import org.bukkit.craftbukkit.v1_17_R1.util.CraftMagicNumbers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

public class IWorldHandler extends WorldHandler {
    private static final EnumBlockRotation ROTATION[];
    static {
        ROTATION = new EnumBlockRotation[3];
        ROTATION[Rotation.NONE.ordinal()] = EnumBlockRotation.a;
        ROTATION[Rotation.CLOCKWISE.ordinal()] = EnumBlockRotation.b;
        ROTATION[Rotation.ANTICLOCKWISE.ordinal()] = EnumBlockRotation.d;

    }
    private final NextTickProvider tickProvider = new NextTickProvider();
    private final HashMap<WorldServer, List<TileEntity>> bMap = new HashMap<>();
    private MethodHandle internalTeleportMH;

    public IWorldHandler() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Method teleport = null;
        try {
            teleport = PlayerConnection.class.getDeclaredMethod("a", double.class, double.class, double.class, float.class, float.class, Set.class);
            teleport.setAccessible(true);
            internalTeleportMH = lookup.unreflect(teleport);

        } catch (NoSuchMethodException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void rotateCraft(@NotNull Craft craft, @NotNull MovecraftLocation originPoint, @NotNull Rotation rotation) {
        //*******************************************
        //*      Step one: Convert to Positions     *
        //*******************************************
        HashMap<BlockPosition, BlockPosition> rotatedPositions = new HashMap<>();
        Rotation counterRotation = rotation == Rotation.CLOCKWISE ? Rotation.ANTICLOCKWISE : Rotation.CLOCKWISE;
        for(MovecraftLocation newLocation : craft.getHitBox()){
            rotatedPositions.put(locationToPosition(MathUtils.rotateVec(counterRotation, newLocation.subtract(originPoint)).add(originPoint)),locationToPosition(newLocation));
        }
        //*******************************************
        //*         Step two: Get the tiles         *
        //*******************************************
        WorldServer nativeWorld = ((CraftWorld) craft.getWorld()).getHandle();
        List<TileHolder> tiles = new ArrayList<>();
        //get the tiles
        for(BlockPosition position : rotatedPositions.keySet()){
            //TileEntity tile = nativeWorld.removeTileEntity(position);
            TileEntity tile = removeTileEntity(nativeWorld,position);
            if(tile == null)
                continue;
            //tile.a(ROTATION[rotation.ordinal()]);
            //get the nextTick to move with the tile
            tiles.add(new TileHolder(tile, tickProvider.getNextTick(nativeWorld, position), position));
        }

        //*******************************************
        //*   Step three: Translate all the blocks  *
        //*******************************************
        // blockedByWater=false means an ocean-going vessel
        //TODO: Simplify
        //TODO: go by chunks
        //TODO: Don't move unnecessary blocks
        //get the blocks and rotate them
        HashMap<BlockPosition, IBlockData> blockData = new HashMap<>();
        for(BlockPosition position : rotatedPositions.keySet()){

            blockData.put(position,nativeWorld.getType(position).a(ROTATION[rotation.ordinal()]));
        }
        HashMap<BlockPosition, IBlockData> redstoneComponents = new HashMap<>();
        //create the new block
        for(Map.Entry<BlockPosition,IBlockData> entry : blockData.entrySet()) {
            final BlockPosition newPosition = rotatedPositions.get(entry.getKey());
            final IBlockData iBlockData = entry.getValue();
            if (nativeWorld.getTileEntity(newPosition) != null) {
                removeTileEntity(nativeWorld, newPosition);
            }
            if (isRedstoneComponent(iBlockData))
                redstoneComponents.put(newPosition, iBlockData);

            setBlockFast(nativeWorld, newPosition, iBlockData);
        }


        //*******************************************
        //*    Step four: replace all the tiles     *
        //*******************************************
        //TODO: go by chunks
        for(TileHolder tileHolder : tiles){
            moveTileEntity(nativeWorld, rotatedPositions.get(tileHolder.getTilePosition()),tileHolder.getTile());
            if(tileHolder.getNextTick()==null)
                continue;
            final long currentTime = nativeWorld.E.getTime();
            nativeWorld.getBlockTickList().a(rotatedPositions.get(tileHolder.getNextTick().a), tileHolder.getNextTick().b(), (int) (tileHolder.getNextTick().b - currentTime), tileHolder.getNextTick().c);
        }

        //*******************************************
        //*   Step five: Destroy the leftovers      *
        //*******************************************
        //TODO: add support for pass-through
        Collection<BlockPosition> deletePositions =  CollectionUtils.filter(rotatedPositions.keySet(),rotatedPositions.values());
        for(BlockPosition position : deletePositions){
            setBlockFast(nativeWorld, position, Blocks.a.getBlockData());
        }

        //*******************************************
        //*   Step six: Process fire spread         *
        //*******************************************
        for (BlockPosition position : rotatedPositions.values()) {
            IBlockData type = nativeWorld.getType(position);
            if (!(type.getBlock() instanceof BlockFire)) {
                continue;
            }
            BlockFire fire = (BlockFire) type.getBlock();
            fire.tick(type, nativeWorld, position, nativeWorld.w);
        }

        for (Map.Entry<BlockPosition, IBlockData> entry : redstoneComponents.entrySet()) {
            final BlockPosition position = entry.getKey();
            final IBlockData data = entry.getValue();
            Chunk chunk = nativeWorld.getChunkAtWorldCoords(position);
            ChunkSection chunkSection = chunk.getSections()[position.getY()>>4];
            if (chunkSection == null) {
                // Put a GLASS block to initialize the section. It will be replaced next with the real block.
                chunk.setType(position, Blocks.au.getBlockData(), true);
                chunkSection = chunk.getSections()[position.getY() >> 4];

            }
            chunkSection.setType(position.getX()&15, position.getY()&15, position.getZ()&15, data);
            nativeWorld.update(position, data.getBlock());
        }
    }

    @Override
    public void translateCraft(@NotNull Craft craft, @NotNull MovecraftLocation displacement, @NotNull org.bukkit.World world) {
        //TODO: Add supourt for rotations
        //A craftTranslateCommand should only occur if the craft is moving to a valid position
        //*******************************************
        //*      Step one: Convert to Positions     *
        //*******************************************
        BlockPosition translateVector = locationToPosition(displacement);
        List<BlockPosition> positions = new ArrayList<>();
        for(MovecraftLocation movecraftLocation : craft.getHitBox()) {
            positions.add(moveBlockPosition(locationToPosition(movecraftLocation), translateVector, true));
        }
        WorldServer oldNativeWorld = ((CraftWorld) craft.getWorld()).getHandle();
        WorldServer nativeWorld = ((CraftWorld) world).getHandle();
        //*******************************************
        //*         Step two: Get the tiles         *
        //*******************************************
        List<TileHolder> tiles = getTiles(positions, oldNativeWorld);
        //*******************************************
        //*   Step three: Translate all the blocks  *
        //*******************************************
        // blockedByWater=false means an ocean-going vessel
        //TODO: Simplify
        //TODO: go by chunks
        //TODO: Don't move unnecessary blocks
        //get the blocks and translate the positions
        List<IBlockData> blockData = new ArrayList<>();
        List<BlockPosition> newPositions = new ArrayList<>();
        for(BlockPosition position : positions){
            blockData.add(oldNativeWorld.getType(position));
            newPositions.add(moveBlockPosition(position, translateVector, false));
        }
        //create the new block
        setBlocks(newPositions, blockData, nativeWorld);
        //*******************************************
        //*    Step four: replace all the tiles     *
        //*******************************************
        //TODO: go by chunks
        processTiles(tiles, nativeWorld, translateVector);
        //*******************************************
        //*   Step five: Destroy the leftovers      *
        //*******************************************
        Collection<BlockPosition> deletePositions = oldNativeWorld == nativeWorld ? CollectionUtils.filter(positions,newPositions) : positions;
        setAir(deletePositions, oldNativeWorld);
        //*******************************************
        //*   Step six: Process fire spread         *
        //*******************************************
        processFireSpread(newPositions, nativeWorld);
        //*******************************************
        //*   Step six: Process redstone        *
        //*******************************************
        processRedstone(newPositions, nativeWorld);
    }


    private void setBlocks(List<BlockPosition> newPositions, List<IBlockData> blockData, WorldServer nativeWorld){
        Map<BlockPosition, IBlockData> redstoneComponents = new HashMap<>();
        for(int i = 0; i<newPositions.size(); i++) {
            setBlockFast(nativeWorld, newPositions.get(i), blockData.get(i));
        }
    }

    private List<TileHolder> getTiles(Iterable<BlockPosition> positions, WorldServer nativeWorld){
        List<TileHolder> tiles = new ArrayList<>();
        //get the tiles
        for(BlockPosition position : positions){
            if(nativeWorld.getType(position) == Blocks.a.getBlockData())
                continue;
            //TileEntity tile = nativeWorld.removeTileEntity(position);
            TileEntity tile = removeTileEntity(nativeWorld,position);
            if(tile == null)
                continue;
            //get the nextTick to move with the tile

            //nativeWorld.capturedTileEntities.remove(position);
            //nativeWorld.getChunkAtWorldCoords(position).getTileEntities().remove(position);
            tiles.add(new TileHolder(tile, tickProvider.getNextTick(nativeWorld, position), position));

        }
        return tiles;
    }

    private BlockPosition moveBlockPosition(BlockPosition original, BlockPosition translateVector, boolean opposite) {
        if (opposite)
            return new BlockPosition(original.getX() - translateVector.getX(), original.getY() - translateVector.getY(), original.getZ() - translateVector.getZ());

        return new BlockPosition(original.getX() + translateVector.getX(), original.getY() + translateVector.getY(), original.getZ() + translateVector.getZ());
    }


    private void processTiles(Iterable<TileHolder> tiles, WorldServer world, BlockPosition translateVector){
        for(TileHolder tileHolder : tiles){
            BlockPosition newPos =  moveBlockPosition(tileHolder.getTilePosition(), translateVector, false);
            moveTileEntity(world, newPos,tileHolder.getTile());
            if(tileHolder.getNextTick()==null)
                continue;
            final long currentTime = world.E.getTime();
            world.getBlockTickList().a(newPos, tileHolder.getNextTick().b(), (int) (tileHolder.getNextTick().b - currentTime), tileHolder.getNextTick().c);
        }
    }

    private void processFireSpread(Iterable<BlockPosition> positions, WorldServer world){
        for (BlockPosition position : positions) {
            IBlockData type = world.getType(position);
            if (!(type.getBlock() instanceof BlockFire)) {
                continue;
            }
            BlockFire fire = (BlockFire) type.getBlock();
            fire.tick(type, world, position, world.w);
        }
    }

    private void processRedstone(List<BlockPosition> redstone, WorldServer world) {
        Map<BlockPosition, IBlockData> redstoneComponents = new HashMap<>();
        for (int i = 0 ; i < redstone.size(); i++) {
            BlockPosition pos = redstone.get(i);
            IBlockData data = world.getType(pos);
            if (isRedstoneComponent(data))
                redstoneComponents.put(pos, data);
        }
        for (Map.Entry<BlockPosition, IBlockData> entry : redstoneComponents.entrySet()) {
            final BlockPosition position = entry.getKey();
            final IBlockData data = entry.getValue();
            Chunk chunk = world.getChunkAtWorldCoords(position);
            ChunkSection chunkSection = chunk.getSections()[position.getY()>>4];
            if (chunkSection == null) {
                // Put a GLASS block to initialize the section. It will be replaced next with the real block.
                chunk.setType(position, Blocks.au.getBlockData(), true);
                chunkSection = chunk.getSections()[position.getY() >> 4];

            }
            chunkSection.setType(position.getX()&15, position.getY()&15, position.getZ()&15, data);
            //world.notifyAndUpdatePhysics(position, chunk, data, data, data, 3);
            world.update(position, data.getBlock());
        }
    }

    private void setAir(Iterable<BlockPosition> positions, WorldServer world){
        for(BlockPosition position : positions){
            setBlockFast(world, position, Blocks.a.getBlockData());
        }
    }

    @Nullable
    private TileEntity removeTileEntity(@NotNull WorldServer world, @NotNull BlockPosition position){
        TileEntity tile = world.getTileEntity(position);
        if(tile == null)
            return null;
        //cleanup
        world.capturedTileEntities.remove(position);
        world.getChunkAtWorldCoords(position).getTileEntities().remove(position);
        //if(!Settings.IsPaper)
            //world.tileEntityList.remove(tile);
        //world.tileEntityListTick.remove(tile);
/*
        if(!bMap.containsKey(world)){
            try {
                Field bField = World.class.getDeclaredField("tileEntityListPending");
                bField.setAccessible(true);
                bMap.put(world, (List<TileEntity>) bField.get(world));//TODO bug fix
            } catch (IllegalAccessException | IllegalArgumentException | NoSuchFieldException | SecurityException e1) {
                e1.printStackTrace();
            }
        }
        bMap.get(world).remove(tile);*/
        return tile;
    }

    @NotNull
    private BlockPosition locationToPosition(@NotNull MovecraftLocation loc) {
        return new BlockPosition(loc.getX(), loc.getY(), loc.getZ());
    }

    private void setBlockFast(@NotNull WorldServer world, @NotNull BlockPosition position,@NotNull IBlockData data) {
        Chunk chunk = world.getChunkAtWorldCoords(position);
        ChunkSection chunkSection = chunk.getSections()[position.getY()>>4];
        if (chunkSection == null) {
            // Put a GLASS block to initialize the section. It will be replaced next with the real block.
            chunk.setType(position, Blocks.au.getBlockData(), true);
            chunkSection = chunk.getSections()[position.getY() >> 4];

        }
        chunkSection.setType(position.getX() & 15, position.getY() & 15, position.getZ() & 15, data);
        world.notify(position, data, data, 3);
        world.getChunkProvider().getLightEngine().a(position);
        chunk.markDirty();
    }

    @Override
    public void setBlockFast(@NotNull Location location, @NotNull Material material, Object data){
        setBlockFast(location, Rotation.NONE, material, data);
    }

    @Override
    public void setBlockFast(@NotNull Location location, @NotNull Rotation rotation, @NotNull Material material, Object data) {
        IBlockData blockData;
        if (!(data instanceof BlockData)) {
            blockData = CraftMagicNumbers.getBlock(material).getBlockData();
        } else {
            blockData = ((CraftBlockData) data).getState();
        }

        blockData = blockData.a(ROTATION[rotation.ordinal()]);
        WorldServer world = ((CraftWorld)(location.getWorld())).getHandle();
        BlockPosition blockPosition = locationToPosition(bukkit2MovecraftLoc(location));
        if (world.getTileEntity(blockPosition) != null) {
            removeTileEntity(world, blockPosition);
        }
        setBlockFast(world,blockPosition,blockData);
    }

    @Override
    public void disableShadow(@NotNull Material type) {
        Method method;
        //try {
        Block tempBlock = CraftMagicNumbers.getBlock(type);
        //method = Block.class.getDeclaredMethod("d", int.class);
        //method.setAccessible(true);
        //method.invoke(tempBlock, 0);
        //} catch (NoSuchMethodException | InvocationTargetException | IllegalArgumentException | IllegalAccessException | SecurityException e1) {
        // TODO Auto-generated catch block
        //    e1.printStackTrace();
        //}
    }

    private static MovecraftLocation bukkit2MovecraftLoc(Location l) {
        return new MovecraftLocation(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    private void recalculateLighting(WorldServer world, Collection<BlockPosition> positions) {
        final LightEngineThreaded engine = world.getChunkProvider().getLightEngine();
        for (BlockPosition pos : positions) {
            engine.a(pos);
        }
    }

    private void moveTileEntity(@NotNull WorldServer nativeWorld, @NotNull BlockPosition newPosition, @NotNull TileEntity tile){
        Chunk chunk = nativeWorld.getChunkAtWorldCoords(newPosition);
        //tile.invalidateBlockCache();
        if(nativeWorld.captureBlockStates) {
            //tile.setLocation(nativeWorld, newPosition);
            nativeWorld.capturedTileEntities.put(newPosition, tile);
            return;
        }
        try {
            final Method uMethod = BaseBlockPosition.class.getDeclaredMethod("u", int.class);
            final Method tMethod = BaseBlockPosition.class.getDeclaredMethod("t", int.class);
            final Method sMethod = BaseBlockPosition.class.getDeclaredMethod("s", int.class);
            uMethod.setAccessible(true);
            tMethod.setAccessible(true);
            sMethod.setAccessible(true);
            uMethod.invoke(tile.getPosition(), newPosition.getX());
            tMethod.invoke(tile.getPosition(), newPosition.getY());
            sMethod.invoke(tile.getPosition(), newPosition.getZ());
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            e.printStackTrace();
        }
        chunk.l.put(newPosition, tile);
    }

    private class TileHolder{
        @NotNull private final TileEntity tile;
        @Nullable
        private final NextTickListEntry<Block> nextTick;
        @NotNull private final BlockPosition tilePosition;

        public TileHolder(@NotNull TileEntity tile, @Nullable NextTickListEntry<Block> nextTick, @NotNull BlockPosition tilePosition){
            this.tile = tile;
            this.nextTick = nextTick;
            this.tilePosition = tilePosition;
        }


        @NotNull
        public TileEntity getTile() {
            return tile;
        }

        @Nullable
        public NextTickListEntry<Block> getNextTick() {
            return nextTick;
        }

        @NotNull
        public BlockPosition getTilePosition() {
            return tilePosition;
        }
    }

    private boolean isRedstoneComponent(IBlockData blockData) {
        final Block block = blockData.getBlock();
        return block instanceof BlockRedstoneWire ||
                block instanceof BlockDiodeAbstract ||
                block instanceof BlockButtonAbstract ||
                block instanceof BlockLever;

    }


}

