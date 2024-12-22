package bigchadguys.sellingbin;

import bigchadguys.sellingbin.block.BinMode;
import bigchadguys.sellingbin.block.SellingBinBlock;
import bigchadguys.sellingbin.data.adapter.Adapters;
import bigchadguys.sellingbin.data.serializable.INbtSerializable;
import bigchadguys.sellingbin.init.ModConfigs;
import bigchadguys.sellingbin.trade.Trade;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.Direction;

import java.util.*;
import java.util.stream.IntStream;

public class SellingBinInventory implements SidedInventory, INbtSerializable<NbtCompound> {

    private final BlockEntity blockEntity;
    private DefaultedList<ItemStack> inventory;
    private int[] availableSlots;
    private int intendedCapacity;

    public SellingBinInventory(BlockEntity blockEntity, int slots) {
        this.blockEntity = blockEntity;
        this.setCapacity(slots);
    }

    public void setCapacity(int size) {
        this.intendedCapacity = size;
        this.availableSlots = IntStream.range(0, size).toArray();
        DefaultedList<ItemStack> oldInventory = this.inventory;
        this.inventory = DefaultedList.ofSize(size, ItemStack.EMPTY);

        if(oldInventory != null) {
            for(int i = 0; i < oldInventory.size(); i++) {
                this.inventory.set(i, oldInventory.get(i));
            }
        }

        this.markDirty();
    }

    @Override
    public int[] getAvailableSlots(Direction side) {
        return this.availableSlots;
    }

    @Override
    public void onOpen(PlayerEntity player) {

    }

    @Override
    public boolean canInsert(int slot, ItemStack stack, Direction dir) {
        return true;
    }

    @Override
    public boolean canExtract(int slot, ItemStack stack, Direction dir) {
        return true;
    }

    @Override
    public int size() {
        return this.inventory.size();
    }

    @Override
    public boolean isEmpty() {
        return this.inventory.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getStack(int slot) {
        return this.inventory.get(slot);
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack stack = Inventories.splitStack(this.inventory, slot, amount);

        if(!stack.isEmpty()) {
            this.markDirty();
        }

        return stack;
    }

    @Override
    public ItemStack removeStack(int slot) {
        return Inventories.removeStack(this.inventory, slot);
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        this.inventory.set(slot, stack);

        if(stack.getCount() > this.getMaxCountPerStack()) {
            stack.setCount(this.getMaxCountPerStack());
        }

        this.markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return this.blockEntity == null || Inventory.canPlayerUse(this.blockEntity, player);
    }

    @Override
    public void clear() {
        this.inventory.clear();
    }

    @Override
    public void markDirty() {
        if(this.blockEntity != null) {
            this.blockEntity.markDirty();
        }
    }

    public void executeTrades() {
        List<ItemStack> rewards = new ArrayList<>();

        for(Trade trade : ModConfigs.SELLING_BIN.getTrades()) {
            while(this.executeTrade(trade)) {
                trade.getOutput().generate().ifPresent(stack -> {
                    rewards.add(stack.copy());
                });
            }
        }

        rewards.removeIf(reward -> {
            int left = reward.getCount();

            for(int slot : this.availableSlots) {
                ItemStack stack = this.getStack(slot);
                if(left <= 0) break;

                if(stack.isEmpty() || ItemStack.canCombine(stack, reward)) {
                    int difference = Math.min(reward.getMaxCount() - stack.getCount(), left);
                    ItemStack copy = reward.copy();
                    copy.setCount(stack.getCount() + difference);
                    this.setStack(slot, copy);
                    left -= difference;
                }
            }

            if(left <= 0) {
                return true;
            }

            reward.setCount(left);
            return false;
        });
    }

    public boolean executeTrade(Trade trade) {
        int matchesLeft = trade.getInput().getCount();
        Map<Integer, ItemStack> matches = new LinkedHashMap<>();

        for(int slot : this.availableSlots) {
            ItemStack stack = this.inventory.get(slot);
            if(!trade.getInput().getFilter().test(stack)) {
                continue;
            }

            int difference = Math.min(stack.getCount(), matchesLeft);
            matchesLeft -= difference;
            matches.put(slot, stack);

            if(matchesLeft <= 0) {
                break;
            }
        }

        if(matchesLeft == 0) {
            matchesLeft = trade.getInput().getCount();

            for(Map.Entry<Integer, ItemStack> entry : matches.entrySet()) {
                int slot = entry.getKey();
                ItemStack stack = entry.getValue();

                int difference = Math.min(stack.getCount(), matchesLeft);
                stack.decrement(difference);
                matchesLeft -= difference;
                this.setStack(slot, stack);
            }

            return true;
        }

        return false;
    }

    @Override
    public Optional<NbtCompound> writeNbt() {
        NbtCompound nbt = new NbtCompound();
        NbtList slots = new NbtList();

        for(int i = 0; i < this.inventory.size(); i++) {
            ItemStack stack = this.inventory.get(i);
            if(stack.isEmpty()) continue;
            int slotIndex = i;

            Adapters.ITEM_STACK.writeNbt(stack).ifPresent(tag -> {
                Adapters.INT.writeNbt(slotIndex).ifPresent(tag2 -> ((NbtCompound)tag).put("slot", tag2));
                slots.add(tag);
            });
        }

        nbt.put("slots", slots);
        return Optional.of(nbt);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        NbtList inventory = nbt.getList("slots", NbtElement.COMPOUND_TYPE);
        this.inventory = null;

        BinMode mode = blockEntity.getCachedState().get(SellingBinBlock.MODE);
        if(mode == BinMode.BLOCK_BOUND)
        {
            this.setCapacity(intendedCapacity);
        } else
        {
            if(mode != BinMode.PLAYER_BOUND)
            {
                SellingBinMod.LOGGER.error("Unsupported BinMode {}. Defaulting to PLAYER_BOUND logic", mode.name());
            }
            this.setCapacity(inventory.size());
        }

        for(int i = 0; i < this.inventory.size(); i++) {
            NbtCompound entry = inventory.getCompound(i);

            Adapters.ITEM_STACK.readNbt(entry).ifPresent(stack -> {
                Adapters.INT.readNbt(entry.get("slot")).ifPresent(slotIndex -> {
                    this.inventory.set(slotIndex, stack);
                });
            });
        }
    }

}
