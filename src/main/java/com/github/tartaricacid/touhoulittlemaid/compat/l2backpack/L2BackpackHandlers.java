package com.github.tartaricacid.touhoulittlemaid.compat.l2backpack;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidPickupEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.MaidRequestItemEvent;
import com.github.tartaricacid.touhoulittlemaid.compat.curios.CuriosCompat;
import com.github.tartaricacid.touhoulittlemaid.compat.sbackpack.curios.MaidBackpackCache;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import dev.xkmc.l2backpack.content.backpack.BackpackItem;
import dev.xkmc.l2backpack.content.capability.PickupModeCap;
import dev.xkmc.l2backpack.content.capability.PickupTrace;
import dev.xkmc.l2backpack.content.remote.worldchest.WorldChestItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * L2背包事件处理器 - 整合所有功能
 * - 缓存管理：缓存女仆的背包槽位列表
 * - 拾取处理：根据背包模式处理物品拾取
 * - 请求物品处理：根据过滤器从背包提取物品
 * - 背包装备/卸下：检测背包装备变化并更新缓存
 */
public class L2BackpackHandlers {

	/**
	 * Equipment slots and curios
	 */
	private static List<BackpackSlot> getBackpacks(EntityMaid maid) {
		List<ItemStack> list = new ArrayList<>();
		for (EquipmentSlot e : EquipmentSlot.values()) {
			var stack = maid.getItemBySlot(e);
			if (stack.getItem() instanceof WorldChestItem || stack.getItem() instanceof BackpackItem) {
				list.add(stack);
			}
		}
		if (CuriosCompat.isLoadedOrEnable()) {
			var opt = CuriosApi.getCuriosInventory(maid).resolve();
			if (opt.isPresent()) {
				var slots = opt.get().findCurios(stack -> stack.getItem() instanceof WorldChestItem || stack.getItem() instanceof BackpackItem);
				for (var e : slots) {
					list.add(e.stack());
				}
			}
		}
		List<BackpackSlot> ans = new ArrayList<>();
		for (var stack : list) {
			if (stack.getItem() instanceof WorldChestItem c) {
				BackpackSlot.fromDimensional(maid, c, stack, ans);
			} else if (stack.getItem() instanceof BackpackItem b) {
				BackpackSlot.fromBackpack(maid, b, stack, ans);
			}
		}
		return ans;
	}

	// ==================== 拾取事件 ====================

	/**
	 * 监听女仆拾取物品事件，处理物品进入背包
	 * 优先级：高，确保在其他处理器之前执行
	 */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public void onMaidPickup(MaidPickupEvent.ItemResultPre event) {
		EntityMaid maid = event.getMaid();
		ItemEntity itemEntity = event.getEntityItem();
		boolean simulate = event.isSimulate();
		if (!tryPickup(maid, itemEntity, simulate)) return;
		event.setCanPickup(true);
		event.setCanceled(true);
	}


	private boolean tryPickup(EntityMaid maid, ItemEntity itemEntity, boolean simulate) {
		if (!itemEntity.isAlive() || itemEntity.hasPickUpDelay())
			return false;
		if (!(maid.level instanceof ServerLevel serverLevel)) return false;
		ItemStack itemStack = itemEntity.getItem();
		if (!EntityMaid.canInsertItem(itemStack)) {
			return false;
		}

		int originCount = itemStack.getCount();
		List<BackpackSlot> backpacks = getBackpacks(maid);

		// 没有背包时不处理
		if (backpacks.isEmpty()) return false;

		// 使用L2背包原生的拾取机制
		// 创建PickupTrace来追踪拾取过程
		PickupTrace trace = new PickupTrace(simulate, serverLevel);

		// 遍历每个背包，调用其doPickup方法
		int totalPicked = 0;
		ItemStack remaining = itemStack.copy();
		for (BackpackSlot bp : backpacks) {
			PickupModeCap cap = bp.pickup();
			if (cap == null) continue;
			int picked = cap.doPickup(remaining, trace);
			totalPicked += picked;
			if (remaining.isEmpty()) break;
		}

		if (originCount == remaining.getCount()) {
			return false;
		}
		if (!simulate) {
			itemEntity.setItem(remaining);
			// 最后触发拾取动画和音效，更新实体物品数量
			// 以及触发 MaidPickupEvent.ItemResultPost 事件
			handlePickupEffects(maid, itemEntity, itemStack, originCount);
		}
		return true;
	}

	private void handlePickupEffects(EntityMaid maid, ItemEntity itemEntity, ItemStack remaining, int originCount) {
		int pickedCount = originCount - remaining.getCount();
		maid.take(itemEntity, pickedCount);
		maid.tryPlayMaidPickupSound();

		ItemStack pickedStack = new ItemStack(itemEntity.getItem().getItem(), pickedCount);
		MinecraftForge.EVENT_BUS.post(new MaidPickupEvent.ItemResultPost(maid, pickedStack));

		if (remaining.isEmpty()) {
			itemEntity.discard();
		} else {
			itemEntity.setItem(remaining);
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
			ItemStack simExtract = bp.extractItem(filter, maxCount, true);
			if (simExtract.isEmpty()) continue;
			ItemStack remaining = ItemHandlerHelper.insertItemStacked(maidInv, simExtract, true);
			int simInsert = simExtract.getCount() - remaining.getCount();
			if (simInsert == 0) continue;
			ItemStack actualExtract = bp.extractItem(filter, maxCount, false);
			ItemHandlerHelper.insertItemStacked(maidInv, actualExtract, false);
		}
	}

	// ==================== 背包物品栏接口 ====================

	@Nullable
	public static IItemHandlerModifiable getBackpackInventory(EntityMaid maid) {
		List<BackpackSlot> backpacks = getBackpacks(maid);
		if (backpacks.isEmpty()) {
			return null;
		}
		IItemHandlerModifiable[] ans = new IItemHandlerModifiable[backpacks.size()];
		for (int i = 0; i < backpacks.size(); i++) {
			ans[i] = backpacks.get(i).inv;
		}
		return new CombinedInvWrapper(ans);
	}

	/**
	 * 尝试将物品插入L2背包，遵循背包的拾取模式（NONE/STACKING/ALL）
	 * 返回未能插入的物品
	 */
	public static ItemStack insertToBackpack(EntityMaid maid, ItemStack stack, boolean simulate) {
		List<BackpackSlot> list = getBackpacks(maid);
		if (list.isEmpty()) {
			return stack;
		}
		// 使用L2背包原生的拾取机制
		if (!(maid.level instanceof ServerLevel serverLevel)) return stack;
		PickupTrace trace = new PickupTrace(simulate, serverLevel);
		ItemStack remaining = stack.copy();
		for (BackpackSlot bp : list) {
			PickupModeCap cap = bp.pickup();
			if (cap == null) continue;
			cap.doPickup(remaining, trace);
			if (remaining.isEmpty()) break;
		}
		return remaining;
	}

	public record BackpackSlot(
			EntityMaid maid,
			ItemStack stack,
			IItemHandlerModifiable inv,
			@Nullable PickupModeCap pickup
	) {

		public static void fromBackpack(EntityMaid maid, BackpackItem b, ItemStack stack, List<BackpackSlot> list) {
			var sp = maid.getOwner() instanceof ServerPlayer p ? p : null;
			var cap = b.getInvCap(stack, sp);
			if (cap instanceof IItemHandlerModifiable m) {
				var pickup = stack.getCapability(PickupModeCap.TOKEN).resolve().orElse(null);
				list.add(new BackpackSlot(maid, stack, m, pickup));
			}
		}

		public static void fromDimensional(EntityMaid maid, WorldChestItem c, ItemStack stack, List<BackpackSlot> list) {
			var sp = maid.getOwner() instanceof ServerPlayer p ? p : null;
			if (sp == null) return;
			var cap = c.getInvCap(stack, sp);
			if (cap instanceof IItemHandlerModifiable m) {
				var pickup = stack.getCapability(PickupModeCap.TOKEN).resolve().orElse(null);
				list.add(new BackpackSlot(maid, stack, m, pickup));
			}
		}

		public ItemStack extractItem(Predicate<ItemStack> filter, int maxCount, boolean simulate) {
			int n = inv.getSlots();
			for (int i = 0; i < n; i++) {
				if (filter.test(inv.getStackInSlot(i))) {
					var ans = inv.extractItem(i, maxCount, simulate);
					if (!ans.isEmpty()) return ans;
				}
			}
			return ItemStack.EMPTY;
		}

	}

}