package com.github.tartaricacid.touhoulittlemaid.compat.l2backpack;

import com.google.common.collect.Maps;
import net.minecraft.Util;
import net.minecraftforge.common.MinecraftForge;

import java.util.Map;

public class L2BackpackCuriosCompat {
    private static final int DEFAULT_PRIORITY = 100;
    private static final Map<String, Integer> SLOT_PRIORITY = Util.make(Maps.newHashMap(), map -> {
        map.put("back", 0);
        map.put("trinkets", 1);
    });

    public static void init() {
        MinecraftForge.EVENT_BUS.register(new L2BackpackHandlers());
    }

    public static int getSlotPriority(String slotType) {
        return SLOT_PRIORITY.getOrDefault(slotType, DEFAULT_PRIORITY);
    }
}