package com.github.tartaricacid.touhoulittlemaid.compat.l2backpack;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidRequestItemEvent;
import com.github.tartaricacid.touhoulittlemaid.compat.curios.CuriosCompat;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.Lists;
import dev.xkmc.l2backpack.content.capability.PickupModeCap;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.event.CurioChangeEvent;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * L2背包事件处理器 - 整合所有功能
 * - 缓存管理：缓存女仆的背包槽位列表
 * - 拾取处理：根据背包模式处理物品拾取
 * - 请求物品处理：根据过滤器从背包提取物品
 * - 背包装备/卸下：检测背包装备变化并更新缓存
 */
public class L2BackpackHandlers {
    // 缓存：女仆 -> 背包列表（女仆物品栏单独处理，不进入缓存）
    private static final WeakHashMap<EntityMaid, List<BackpackSlot>> CACHE = new WeakHashMap<>();

    // ==================== 缓存管理 ====================

    /**
     * 获取女仆的背包槽位列表，使用缓存避免频繁构建
     */
    private static List<BackpackSlot> getBackpacks(EntityMaid maid) {
        List<BackpackSlot> list = CACHE.get(maid);
        if (list == null) {
            list = buildBackpackList(maid);
            CACHE.put(maid, list);
        }
        return list;
    }

    /**
     * 构建女仆的背包槽位列表，遍历Curios槽位并识别背包
     * 排序：空间背包(dims) > 普通背包(normals)
     */
    private static List<BackpackSlot> buildBackpackList(EntityMaid maid) {
        List<BackpackSlot> list = Lists.newArrayList();
        // Curios未加载时返回空列表
        if (!CuriosCompat.isLoadedOrEnable()) {
            return list;
        }

        List<BackpackSlot> dims = Lists.newArrayList();
        List<BackpackSlot> normals = Lists.newArrayList();

        // 遍历Curios槽位，识别L2背包
        CuriosApi.getCuriosInventory(maid).ifPresent(handler -> {
            handler.getCurios().forEach((slotType, stacksHandler) -> {
                IDynamicStackHandler stacks = stacksHandler.getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack stack = stacks.getStackInSlot(i);
                    if (L2BackpackCompat.isDimensionalStorage(stack)) {
                        dims.add(new BackpackSlot(maid, slotType, i, true));
                    } else if (L2BackpackCompat.isBackpack(stack)) {
                        normals.add(new BackpackSlot(maid, slotType, i, false));
                    }
                }
            });
        });

        // 按优先级排序：空间背包优先，然后普通背包
        dims.sort(Comparator.comparingInt(a -> a.priority));
        normals.sort(Comparator.comparingInt(a -> a.priority));
        list.addAll(dims);
        list.addAll(normals);
        return list;
    }

    /**
     * 清除指定女仆的缓存
     */
    private static void invalidate(EntityMaid maid) {
        CACHE.remove(maid);
    }

    // ==================== 背包装备/卸下事件 ====================

    /**
     * 监听Curios槽位变化，当背包装备/卸下时清除缓存
     */
    @SubscribeEvent
    public void onCurioChange(CurioChangeEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid)) return;

        ItemStack from = event.getFrom();
        ItemStack to = event.getTo();

        // 检查前后是否为背包
        boolean wasBackpack = L2BackpackCompat.isBackpack(from) || L2BackpackCompat.isDimensionalStorage(from);
        boolean isBackpack = L2BackpackCompat.isBackpack(to) || L2BackpackCompat.isDimensionalStorage(to);

        // 背包状态变化时清除缓存
        if (wasBackpack != isBackpack) {
            invalidate(maid);
        }
    }

    // ==================== 拾取事件 ====================

    /**
     * 监听女仆拾取物品事件，处理物品进入背包
     * 优先级：高，确保在其他处理器之前执行
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onMaidPickup(MaidPickupEvent.ItemResultPre event) {
        EntityMaid maid = event.getMaid();
        ItemEntity itemEntity = event.getEntityItem();
        boolean simulate = event.isSimulate();

        // 只在服务端处理有效实体
        if (maid.level.isClientSide || !itemEntity.isAlive()) {
            return;
        }

        ItemStack stack = itemEntity.getItem();
        // 检查物品是否允许被女仆拾取
        if (!EntityMaid.canInsertItem(stack)) {
            return;
        }

        int originalCount = stack.getCount();
        List<BackpackSlot> backpacks = getBackpacks(maid);

        // 没有背包时不处理
        if (backpacks.isEmpty()) {
            return;
        }

        // 使用L2背包原生的拾取机制
        // 创建PickupTrace来追踪拾取过程
        if (!(maid.level instanceof ServerLevel serverLevel)) return;
        dev.xkmc.l2backpack.content.capability.PickupTrace trace = new dev.xkmc.l2backpack.content.capability.PickupTrace(simulate, serverLevel);
        
        // 遍历每个背包，调用其doPickup方法
        int totalPicked = 0;
        ItemStack remaining = stack.copy();
        
        for (BackpackSlot bp : backpacks) {
            ItemStack bpStack = bp.getStack();
            if (bpStack.isEmpty()) continue;
            
            // 获取背包的PickupModeCap
            var cap = bpStack.getCapability(PickupModeCap.TOKEN).resolve();
            if (cap.isEmpty()) continue;
            
            PickupModeCap pickupCap = cap.get();
            int picked = pickupCap.doPickup(remaining, trace);
            totalPicked += picked;
            
            if (remaining.isEmpty()) break;
        }
        
        // 如果有物品未被拾取，放入女仆物品栏
        if (!remaining.isEmpty()) {
            IItemHandler maidInv = maid.getAvailableInv(false);
            remaining = ItemHandlerHelper.insertItemStacked(maidInv, remaining, simulate);
        }

        // 没有成功放入任何物品时返回
        if (remaining.getCount() == originalCount) {
            return;
        }

        // 设置拾取成功
        event.setCanPickup(true);
        event.setCanceled(true);

        // 非模拟模式下执行实际拾取
        if (!simulate) {
            int picked = originalCount - remaining.getCount();
            maid.take(itemEntity, picked);
            maid.tryPlayMaidPickupSound();
            // 发送拾取后事件
            MinecraftForge.EVENT_BUS.post(new MaidPickupEvent.ItemResultPost(maid, new ItemStack(itemEntity.getItem().getItem(), picked)));

            // 更新或移除物品实体
            if (remaining.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setItem(remaining);
            }
        }
    }

    // ==================== 请求物品事件 ====================

    /**
     * 监听女仆请求物品事件，从背包提取物品
     */
    @SubscribeEvent
    public void onMaidRequest(MaidRequestItemEvent event) {
        EntityMaid maid = event.getMaid();
        Predicate<ItemStack> filter = event.getItemFilter();
        int maxCount = event.getMaxCount();

        List<BackpackSlot> backpacks = getBackpacks(maid);
        if (backpacks.isEmpty()) return;

        IItemHandler maidInv = maid.getAvailableInv(false);

        // 遍历背包提取物品
        for (BackpackSlot bp : backpacks) {
            ItemStack extracted = bp.extract(filter, maxCount);
            if (extracted.isEmpty()) continue;

            // 尝试放入女仆物品栏
            ItemStack remaining = ItemHandlerHelper.insertItemStacked(maidInv, extracted, false);
            int inserted = extracted.getCount() - remaining.getCount();

            if (inserted > 0) {
                // 放回没放下的部分
                if (!remaining.isEmpty()) {
                    bp.insert(remaining, false);
                }
                event.setRequestedItem(extracted.copyWithCount(inserted));
                event.setCanceled(true);
                return;
            }
        }
    }

    // ==================== 背包物品栏接口 ====================

    /**
     * 获取女仆的L2背包虚拟物品栏，用于直接读取背包内物品而不提取
     * 返回的 IItemHandler 是只读的，用于集成到女仆的物品栏系统中
     */
    public static IItemHandler getBackpackInventory(EntityMaid maid) {
        List<BackpackSlot> backpacks = getBackpacks(maid);
        if (backpacks.isEmpty()) {
            return null;
        }
        return new L2BackpackInventoryHandler(maid, backpacks);
    }

    /**
     * 尝试将物品插入L2背包，遵循背包的拾取模式（NONE/STACKING/ALL）
     * 返回未能插入的物品
     */
    public static ItemStack insertToBackpack(EntityMaid maid, ItemStack stack, boolean simulate) {
        List<BackpackSlot> backpacks = getBackpacks(maid);
        if (backpacks.isEmpty()) {
            return stack;
        }

        // 使用L2背包原生的拾取机制
        if (!(maid.level instanceof ServerLevel serverLevel)) return stack;
        dev.xkmc.l2backpack.content.capability.PickupTrace trace = new dev.xkmc.l2backpack.content.capability.PickupTrace(simulate, serverLevel);
        
        ItemStack remaining = stack.copy();
        
        for (BackpackSlot bp : backpacks) {
            ItemStack bpStack = bp.getStack();
            if (bpStack.isEmpty()) continue;
            
            var cap = bpStack.getCapability(PickupModeCap.TOKEN).resolve();
            if (cap.isEmpty()) continue;
            
            PickupModeCap pickupCap = cap.get();
            pickupCap.doPickup(remaining, trace);
            
            if (remaining.isEmpty()) break;
        }

        return remaining;
    }

    /**
     * L2背包虚拟物品栏处理器
     * 将多个背包的内容合并为一个虚拟的物品栏，供女仆直接使用
     */
    private static class L2BackpackInventoryHandler implements IItemHandler {
        private final EntityMaid maid;
        private final List<BackpackSlot> backpacks;

        L2BackpackInventoryHandler(EntityMaid maid, List<BackpackSlot> backpacks) {
            this.maid = maid;
            this.backpacks = backpacks;
        }

        @Override
        public int getSlots() {
            // 计算总槽位数：所有背包的容量之和
            int total = 0;
            for (BackpackSlot bp : backpacks) {
                total += bp.getCapacity();
            }
            return total;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            // 根据槽位索引找到对应的背包和内部槽位
            for (BackpackSlot bp : backpacks) {
                int capacity = bp.getCapacity();
                if (slot < capacity) {
                    return bp.getItemInSlot(slot);
                }
                slot -= capacity;
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            // 背包只读，不支持插入
            return stack;
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            // 根据槽位索引找到对应的背包并提取物品
            for (BackpackSlot bp : backpacks) {
                int capacity = bp.getCapacity();
                if (slot < capacity) {
                    return bp.extractItem(slot, amount, simulate);
                }
                slot -= capacity;
            }
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return true;
        }
    }

    // ==================== 内部类：背包槽位 ====================

    /**
     * 背包槽位封装 - 统一普通背包和空间背包的操作
     * isDimensional: true=空间背包 false=普通背包
     */
    private static class BackpackSlot {
        private final EntityMaid maid;
        private final String slotType;
        private final int slotIndex;
        private final int priority;
        private final boolean isDimensional;

        BackpackSlot(EntityMaid maid, String slotType, int slotIndex, boolean isDimensional) {
            this.maid = maid;
            this.slotType = slotType;
            this.slotIndex = slotIndex;
            this.isDimensional = isDimensional;
            this.priority = L2BackpackCuriosCompat.getSlotPriority(slotType);
        }

        /**
         * 获取背包容量
         */
        int getCapacity() {
            ItemStack stack = getStack();
            if (stack.isEmpty()) return 0;

            if (isDimensional) {
                var container = getStorageContainer(stack);
                return container != null ? container.container.getContainerSize() : 0;
            } else {
                var cap = stack.getCapability(ForgeCapabilities.ITEM_HANDLER);
                return cap.map(IItemHandler::getSlots).orElse(0);
            }
        }

        /**
         * 获取指定槽位的物品
         */
        ItemStack getItemInSlot(int index) {
            ItemStack stack = getStack();
            if (stack.isEmpty()) return ItemStack.EMPTY;

            if (isDimensional) {
                var container = getStorageContainer(stack);
                if (container == null) return ItemStack.EMPTY;
                return container.container.getItem(index);
            } else {
                var cap = stack.getCapability(ForgeCapabilities.ITEM_HANDLER);
                return cap.map(handler -> handler.getStackInSlot(index)).orElse(ItemStack.EMPTY);
            }
        }

        /**
         * 从指定槽位提取物品
         */
        ItemStack extractItem(int index, int amount, boolean simulate) {
            ItemStack stack = getStack();
            if (stack.isEmpty()) return ItemStack.EMPTY;

            if (isDimensional) {
                var container = getStorageContainer(stack);
                if (container == null) return ItemStack.EMPTY;
                ItemStack existing = container.container.getItem(index);
                if (existing.isEmpty()) return ItemStack.EMPTY;

                int toExtract = Math.min(amount, existing.getCount());
                ItemStack result = existing.copyWithCount(toExtract);
                if (!simulate) {
                    existing.shrink(toExtract);
                    container.container.setItem(index, existing);
                }
                return result;
            } else {
                var cap = stack.getCapability(ForgeCapabilities.ITEM_HANDLER);
                return cap.map(handler -> handler.extractItem(index, amount, simulate)).orElse(ItemStack.EMPTY);
            }
        }

        /**
         * 获取槽位中的物品
         */
        private ItemStack getStack() {
            var inv = CuriosApi.getCuriosInventory(maid);
            return inv.map(handler -> handler.getStacksHandler(slotType)
                    .map(stacksHandler -> {
                        IDynamicStackHandler stacks = stacksHandler.getStacks();
                        if (slotIndex >= stacks.getSlots()) return ItemStack.EMPTY;
                        ItemStack stack = stacks.getStackInSlot(slotIndex);
                        if (isDimensional && L2BackpackCompat.isDimensionalStorage(stack)) return stack;
                        if (!isDimensional && L2BackpackCompat.isBackpack(stack)) return stack;
                        return ItemStack.EMPTY;
                    }).orElse(ItemStack.EMPTY)
            ).orElse(ItemStack.EMPTY);
        }

        /**
         * 检���背包内是否包含指定物品
         */
        boolean contains(ItemStack target) {
            if (target.isEmpty()) return false;
            ItemStack stack = getStack();
            if (stack.isEmpty()) return false;

            if (isDimensional) {
                // 空间背包：直接检查容器内容
                var container = getStorageContainer(stack);
                if (container == null) return false;
                for (int i = 0; i < container.container.getContainerSize(); i++) {
                    ItemStack s = container.container.getItem(i);
                    if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, target)) return true;
                }
                return false;
            } else {
                // 普通背包：通过 ITEM_HANDLER 能力检查
                var cap = stack.getCapability(ForgeCapabilities.ITEM_HANDLER);
                return cap.map(handler -> {
                    for (int i = 0; i < handler.getSlots(); i++) {
                        ItemStack s = handler.getStackInSlot(i);
                        if (!s.isEmpty() && ItemStack.isSameItemSameTags(s, target)) return true;
                    }
                    return false;
                }).orElse(false);
            }
        }

        /**
         * 插入物品到背包
         */
        ItemStack insert(ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack backpack = getStack();
            if (backpack.isEmpty()) return stack;

            if (isDimensional) {
                // 空间背包：通过容器插入
                var container = getStorageContainer(backpack);
                if (container == null) return stack;
                return insertIntoContainer(container.container, stack, simulate);
            } else {
                // 普通背包：通过 ITEM_HANDLER 插入
                var cap = backpack.getCapability(ForgeCapabilities.ITEM_HANDLER);
                return cap.map(handler -> ItemHandlerHelper.insertItemStacked(handler, stack, simulate))
                        .orElse(stack);
            }
        }

        /**
         * 从背包提取物品
         */
        ItemStack extract(Predicate<ItemStack> filter, int maxCount) {
            ItemStack backpack = getStack();
            if (backpack.isEmpty()) return ItemStack.EMPTY;

            if (isDimensional) {
                // 空间背包：从容器提取
                var container = getStorageContainer(backpack);
                if (container == null) return ItemStack.EMPTY;
                for (int i = 0; i < container.container.getContainerSize(); i++) {
                    ItemStack s = container.container.getItem(i);
                    if (!s.isEmpty() && filter.test(s)) {
                        int maxStack = s.getMaxStackSize();
                        int extract = maxCount == -1 ? maxStack : Math.min(maxCount, maxStack);
                        extract = Math.min(extract, s.getCount());
                        ItemStack result = s.copyWithCount(extract);
                        container.container.removeItem(i, extract);
                        return result;
                    }
                }
                return ItemStack.EMPTY;
            } else {
                // 普通背包：从 ITEM_HANDLER 提取
                var cap = backpack.getCapability(ForgeCapabilities.ITEM_HANDLER);
                return cap.map(handler -> {
                    for (int i = 0; i < handler.getSlots(); i++) {
                        ItemStack s = handler.getStackInSlot(i);
                        if (!s.isEmpty() && filter.test(s)) {
                            int maxStack = s.getMaxStackSize();
                            int extract = maxCount == -1 ? maxStack : Math.min(maxCount, maxStack);
                            extract = Math.min(extract, s.getCount());
                            return handler.extractItem(i, extract, false);
                        }
                    }
                    return ItemStack.EMPTY;
                }).orElse(ItemStack.EMPTY);
            }
        }

        /**
         * 获取空间背包的存储容器
         */
        private dev.xkmc.l2backpack.content.remote.common.StorageContainer getStorageContainer(ItemStack stack) {
            if (!(stack.getItem() instanceof dev.xkmc.l2backpack.content.remote.worldchest.WorldChestItem item)) return null;
            if (!(maid.level instanceof ServerLevel serverLevel)) return null;
            try {
                var storage = dev.xkmc.l2backpack.content.remote.common.WorldStorage.get(serverLevel);
                var tag = stack.getOrCreateTag();
                if (tag.contains("owner_id")) {
                    UUID ownerId = tag.getUUID("owner_id");
                    int color = item.color.getId();
                    var opt = storage.getStorageWithoutPassword(ownerId, color);
                    if (opt.isPresent()) return opt.get();
                    long password = tag.getLong("password");
                    opt = storage.getOrCreateStorage(ownerId, color, password, null, null, 0);
                    return opt.orElse(null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }

        /**
         * 插入物品到容器（空间背包内部）
         * 先尝试堆叠到相同物品，再放入空槽
         */
        private ItemStack insertIntoContainer(Container container, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            ItemStack remaining = stack.copy();

            // 第一轮：尝试堆叠到相同物品
            for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
                ItemStack slot = container.getItem(i);
                if (!slot.isEmpty() && ItemStack.isSameItemSameTags(slot, remaining) && slot.getCount() < slot.getMaxStackSize()) {
                    int canAdd = Math.min(slot.getMaxStackSize() - slot.getCount(), remaining.getCount());
                    if (!simulate) {
                        slot.grow(canAdd);
                        container.setItem(i, slot);
                    }
                    remaining.shrink(canAdd);
                }
            }

            // 第二轮：放入空槽
            for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
                if (container.getItem(i).isEmpty()) {
                    int maxStack = container.getMaxStackSize();
                    int toPut = Math.min(remaining.getCount(), maxStack);
                    if (!simulate) {
                        container.setItem(i, remaining.copyWithCount(toPut));
                    }
                    remaining.shrink(toPut);
                }
            }
            return remaining;
        }
    }
}