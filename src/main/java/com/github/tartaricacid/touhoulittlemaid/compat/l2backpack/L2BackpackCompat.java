package com.github.tartaricacid.touhoulittlemaid.compat.l2backpack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.registry.CompatRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;

public class L2BackpackCompat {
    private static boolean IS_LOADED = false;

    public static void init() {
        IS_LOADED = true;
        if (ModList.get().isLoaded(CompatRegistry.CURIOS)) {
            L2BackpackCuriosCompat.init();
        }
    }

    public static boolean isLoaded() {
        return IS_LOADED;
    }

    public static boolean isBackpack(ItemStack stack) {
        if (isLoaded()) {
            return L2BackpackCompatInner.isBackpack(stack);
        }
        return false;
    }

    public static boolean isDimensionalStorage(ItemStack stack) {
        if (isLoaded()) {
            return L2BackpackCompatInner.isDimensionalStorage(stack);
        }
        return false;
    }

    /**
     * 获取女仆的L2背包虚拟物品栏，用于直接读取背包内物品而不提取
     */
    public static IItemHandler getBackpackInventory(EntityMaid maid) {
        if (!isLoaded()) {
            return null;
        }
        return L2BackpackHandlers.getBackpackInventory(maid);
    }
}