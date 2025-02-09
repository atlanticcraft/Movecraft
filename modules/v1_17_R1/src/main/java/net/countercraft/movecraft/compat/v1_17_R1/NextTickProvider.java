package net.countercraft.movecraft.compat.v1_17_R1;


import net.minecraft.core.BlockPosition;
import net.minecraft.server.level.WorldServer;
import net.minecraft.world.level.NextTickListEntry;
import net.minecraft.world.level.TickListServer;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.*;

public class NextTickProvider {
	private Map<WorldServer, AbstractMap.SimpleImmutableEntry<Set<NextTickListEntry>,Collection<NextTickListEntry>>> tickMap = new HashMap<>();

    private boolean isRegistered(@NotNull WorldServer world){
        return tickMap.containsKey(world);
    }

    @SuppressWarnings("unchecked")
    private void registerWorld(@NotNull WorldServer world){
        final Collection<NextTickListEntry> W = new ArrayList<>();
        final Set<NextTickListEntry> nextTickList = new HashSet<>();
        TickListServer<Block> blockTickListServer = world.getBlockTickList();
        try {
            Field gField = TickListServer.class.getDeclaredField("g");
            gField.setAccessible(true);
            W.addAll((Collection<NextTickListEntry>) gField.get(blockTickListServer));
            Field ntlField = TickListServer.class.getDeclaredField("e");
            ntlField.setAccessible(true);
            nextTickList.addAll((Set<NextTickListEntry>) ntlField.get(blockTickListServer));
        } catch (IllegalAccessException | IllegalArgumentException | NoSuchFieldException | SecurityException e1) {
            e1.printStackTrace();
        }
        tickMap.put(world, new AbstractMap.SimpleImmutableEntry<>(nextTickList,W));
    }

    @Nullable
    public NextTickListEntry getNextTick(@NotNull WorldServer world, @NotNull BlockPosition position) {
        if (!isRegistered(world))
            registerWorld(world);
        AbstractMap.SimpleImmutableEntry<Set<NextTickListEntry>, Collection<NextTickListEntry>> listPair = tickMap.get(world);
        if(listPair.getKey().contains(fakeEntry(position))) {
            for (Iterator<NextTickListEntry> iterator = listPair.getKey().iterator(); iterator.hasNext(); ) {
                NextTickListEntry listEntry = iterator.next();
                if (position.equals(listEntry.a)) {
                    iterator.remove();
                    return listEntry;
                }
            }
        }
        for(Iterator<NextTickListEntry> iterator = listPair.getValue().iterator(); iterator.hasNext();) {
            NextTickListEntry listEntry = iterator.next();
            if (position.equals(listEntry.a)) {
                iterator.remove();
                return listEntry;
            }
        }
        return null;

    }
    @NotNull
    public Object fakeEntry(@NotNull BlockPosition position){
        return new Object(){
            @Override
            public int hashCode() {
                return position.hashCode();
            }
        };
    }
}
