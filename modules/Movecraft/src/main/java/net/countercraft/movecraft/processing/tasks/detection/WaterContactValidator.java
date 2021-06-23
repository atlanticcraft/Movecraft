package net.countercraft.movecraft.processing.tasks.detection;

import net.countercraft.movecraft.MovecraftLocation;
import net.countercraft.movecraft.craft.CraftType;
import net.countercraft.movecraft.localisation.I18nSupport;
import net.countercraft.movecraft.processing.MovecraftWorld;
import net.countercraft.movecraft.processing.Result;
import net.countercraft.movecraft.processing.TaskPredicate;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Deque;
import java.util.Map;

public class WaterContactValidator implements TaskPredicate<Map<Material, Deque<MovecraftLocation>>> {
    @Override
    public Result validate(@NotNull Map<Material, Deque<MovecraftLocation>> materialDequeMap, @NotNull CraftType type, @NotNull MovecraftWorld world, @Nullable CommandSender player) {
        return type.getRequireWaterContact() && !materialDequeMap.containsKey(Material.WATER) ? Result.failWithMessage(I18nSupport.getInternationalisedString("Detection - Failed - Water contact required but not found")) : Result.succeed();
    }
}
