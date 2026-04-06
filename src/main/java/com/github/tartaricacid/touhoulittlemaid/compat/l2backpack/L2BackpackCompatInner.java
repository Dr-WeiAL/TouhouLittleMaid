package com.github.tartaricacid.touhoulittlemaid.compat.l2backpack;

import dev.xkmc.l2backpack.content.backpack.BackpackItem;
import dev.xkmc.l2backpack.content.remote.worldchest.WorldChestItem;
import net.minecraft.world.item.ItemStack;

public class L2BackpackCompatInner {
    static boolean isBackpack(ItemStack stack) {
        return stack.getItem() instanceof BackpackItem;
    }

    static boolean isDimensionalStorage(ItemStack stack) {
        return stack.getItem() instanceof WorldChestItem;
    }
}