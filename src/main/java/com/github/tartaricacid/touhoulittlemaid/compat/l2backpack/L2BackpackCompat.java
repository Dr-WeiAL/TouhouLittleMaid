package com.github.tartaricacid.touhoulittlemaid.compat.l2backpack;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.registry.CompatRegistry;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;

public class L2BackpackCompat {
	private static boolean IS_LOADED = false;

	public static void init() {
		IS_LOADED = true;
		if (ModList.get().isLoaded(CompatRegistry.CURIOS)) {
			MinecraftForge.EVENT_BUS.register(new L2BackpackHandlers());
		}
	}

	public static boolean isLoaded() {
		return IS_LOADED;
	}

	/**
	 * 获取女仆的L2背包虚拟物品栏，用于直接读取背包内物品而不提取
	 */
	@Nullable
	public static IItemHandlerModifiable getBackpackInventory(EntityMaid maid) {
		if (!isLoaded()) {
			return null;
		}
		return L2BackpackHandlers.getBackpackInventory(maid);
	}

}