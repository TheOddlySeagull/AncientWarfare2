package net.shadowmage.ancientwarfare.automation.tile.worksite;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.block.BlockFlower;
import net.minecraft.block.BlockSapling;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemDye;
import net.minecraft.item.ItemShears;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.EnumPlantType;
import net.minecraftforge.common.IPlantable;
import net.minecraftforge.common.IShearable;
import net.minecraftforge.common.util.Constants;
import net.shadowmage.ancientwarfare.automation.tile.TreeFinder;
import net.shadowmage.ancientwarfare.core.block.BlockRotationHandler.RelativeSide;
import net.shadowmage.ancientwarfare.core.interop.ModAccessors;
import net.shadowmage.ancientwarfare.core.inventory.ItemSlotFilter;
import net.shadowmage.ancientwarfare.core.network.NetworkHandler;
import net.shadowmage.ancientwarfare.core.util.InventoryTools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class WorkSiteTreeFarm extends TileWorksiteUserBlocks {

    private static final TreeFinder TREE = new TreeFinder(17), LEAF = new TreeFinder(4);
    private boolean hasShears;
    private int saplingCount;
    private int bonemealCount;
    private final Set<BlockPos> blocksToShear;
    private final Set<BlockPos> blocksToChop;
    private final Set<BlockPos> blocksToPlant;
    private final Set<BlockPos> blocksToFertilize;

    public WorkSiteTreeFarm() {

        blocksToChop = new HashSet<BlockPos>();
        blocksToPlant = new HashSet<BlockPos>();
        blocksToFertilize = new HashSet<BlockPos>();
        blocksToShear = new HashSet<BlockPos>();

        InventoryTools.IndexHelper helper = new InventoryTools.IndexHelper();
        int[] topIndices = helper.getIndiceArrayForSpread(TOP_LENGTH);
        int[] frontIndices = helper.getIndiceArrayForSpread(FRONT_LENGTH);
        int[] bottomIndices = helper.getIndiceArrayForSpread(BOTTOM_LENGTH);
        this.inventory.setAccessibleSideDefault(RelativeSide.TOP, RelativeSide.TOP, topIndices);
        this.inventory.setAccessibleSideDefault(RelativeSide.FRONT, RelativeSide.FRONT, frontIndices);//saplings
        this.inventory.setAccessibleSideDefault(RelativeSide.BOTTOM, RelativeSide.BOTTOM, bottomIndices);//bonemeal and shears
        ItemSlotFilter filter = new ItemSlotFilter() {
            @Override
            public boolean apply(ItemStack stack) {
                return stack == null || isSapling(stack);
            }
        };
        this.inventory.setFilterForSlots(filter, frontIndices);
        filter = new ItemSlotFilter() {
            @Override
            public boolean apply(ItemStack stack) {
                return stack == null || isBonemeal(stack) || stack.getItem() instanceof ItemShears;
            }
        };
        this.inventory.setFilterForSlots(filter, bottomIndices);
    }

    private boolean isSapling(ItemStack stack) {
        return isFarmable(Block.getBlockFromItem(stack.getItem()));
    }

    @Override
    protected boolean isFarmable(Block block, int x, int y, int z){
        if(super.isFarmable(block, x, y, z)){
            if(block instanceof BlockSapling){
                return true;
            }
            if(!(block instanceof IShearable) && !(block instanceof BlockFlower)){
                return ((IPlantable) block).getPlantType(world, x, y, z) == EnumPlantType.Plains;
            }
        }
        return false;
    }

    @Override
    public void onBoundsAdjusted() {
        validateCollection(blocksToFertilize);
        validateCollection(blocksToChop);
        validateCollection(blocksToPlant);
        if(!hasShears){
            blocksToShear.clear();
        }
        markDirty();
    }

    @Override
    protected void countResources() {
        hasShears = false;
        saplingCount = 0;
        bonemealCount = 0;
        ItemStack stack;
        for (int i = TOP_LENGTH; i < getSizeInventory(); i++) {
            stack = getStackInSlot(i);
            if (stack == null) {
                continue;
            }
            if (i < TOP_LENGTH + FRONT_LENGTH){
                if(isSapling(stack))
                    saplingCount += stack.getCount();
            } else if (isBonemeal(stack)) {
                bonemealCount += stack.getCount();
            } else if(stack.getItem() instanceof ItemShears){
                hasShears = true;
            }
        }
    }

    @Override
    protected boolean processWork() {
        BlockPos position;
        if(hasShears && !blocksToShear.isEmpty()){
            Iterator<BlockPos> it = blocksToShear.iterator();
            while (it.hasNext() && (position = it.next()) != null) {
                it.remove();
                Block block = world.getBlock(position.x, position.y, position.z);
                if (block instanceof IShearable) {
                    ItemStack stack;
                    for (int i = TOP_LENGTH + FRONT_LENGTH; i < getSizeInventory(); i++) {
                        stack = getStackInSlot(i);
                        if(stack!=null && stack.getItem() instanceof ItemShears){
                            if(((IShearable) block).isShearable(stack, world, position.x, position.y, position.z)){
                                ArrayList<ItemStack> drops = ((IShearable) block).onSheared(stack, world, position.x, position.y, position.z, getFortune());
                                int[] combinedIndices = inventory.getRawIndicesCombined(RelativeSide.TOP, RelativeSide.FRONT);
                                for(ItemStack drop : drops){
                                    if(drop!=null) {
                                        drop = InventoryTools.mergeItemStack(inventory, drop, combinedIndices);
                                        InventoryTools.dropItemInWorld(world, drop, position.x, position.y, position.z);
                                    }
                                }
                                world.setBlockToAir(position.x, position.y, position.z);
                                ModAccessors.ENVIROMINE.schedulePhysUpdate(world, position.x, position.y, position.z, true, "Normal");
                                return true;
                            }
                        }
                    }
                }
            }
        } else if (!blocksToChop.isEmpty()) {
            Iterator<BlockPos> it = blocksToChop.iterator();
            while (it.hasNext() && (position = it.next()) != null) {
                it.remove();
                if(harvestBlock(position.x, position.y, position.z, RelativeSide.TOP)){
                    addLeavesAround(position);
                    return true;
                }
            }
        } else if (saplingCount > 0 && !blocksToPlant.isEmpty()) {
            ItemStack stack = null;
            for (int i = TOP_LENGTH; i < TOP_LENGTH + FRONT_LENGTH; i++) {
                stack = getStackInSlot(i);
                if (stack != null && isSapling(stack)) {
                    break;
                } else {
                    stack = null;
                }
            }
            if (stack != null)//e.g. a sapling stack is present
            {
                Iterator<BlockPos> it = blocksToPlant.iterator();
                while (it.hasNext() && (position = it.next()) != null) {
                    it.remove();
                    if (isUnwantedPlant(world.getBlock(position.x, position.y, position.z))) {
                        world.setBlock(position.x, position.y, position.z, Blocks.air);
                        ModAccessors.ENVIROMINE.schedulePhysUpdate(world, position.x, position.y, position.z, true, "Normal");
                    }
                    if (canReplace(position.x, position.y, position.z) && tryPlace(stack, position.x, position.y, position.z, EnumFacing.UP)) {
                        saplingCount--;
                        return true;
                    }
                }
            }
        } else if (bonemealCount > 0 && !blocksToFertilize.isEmpty()) {
            Iterator<BlockPos> it = blocksToFertilize.iterator();
            while (it.hasNext() && (position = it.next()) != null) {
                it.remove();
                Block block = world.getBlock(position.x, position.y, position.z);
                if (isFarmable(block, position.x, position.y, position.z)) {
                    ItemStack stack;
                    for (int i = TOP_LENGTH + FRONT_LENGTH; i < getSizeInventory(); i++) {
                        stack = getStackInSlot(i);
                        if (stack != null && isBonemeal(stack)) {
                            if(ItemDye.applyBonemeal(stack, world, position.x, position.y, position.z, getOwnerAsPlayer())){
                                bonemealCount--;
                                if (stack.getCount() <= 0) {
                                    setInventorySlotContents(i, null);
                                }
                            }
                            block = world.getBlock(position.x, position.y, position.z);
                            if (isFarmable(block, position.x, position.y, position.z)) {
                                blocksToFertilize.add(position);//possible concurrent access exception?
                                //technically, it would be, except by the time it hits this inner block, it is already
                                //done iterating, as it will immediately hit the following break statement, and break
                                //out of the iterating loop before the next element would have been iterated over
                            } else if (block.getMaterial() == Material.wood) {
                                addTreeBlocks(block, position);
                            }
                            return true;
                        }
                    }
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    protected int[] getIndicesForPickup(){
        return inventory.getRawIndicesCombined(RelativeSide.BOTTOM, RelativeSide.FRONT, RelativeSide.TOP);
    }

    private void addTreeBlocks(Block block, BlockPos base) {
        world.profiler.startSection("TreeFinder");
        int chops = blocksToChop.size();
        TREE.findAttachedTreeBlocks(block, world, base, blocksToChop);
        if(blocksToChop.size() != chops){
            addLeavesAround(base);
            markDirty();
        }
        world.profiler.endSection();
    }

    private void addLeavesAround(BlockPos base){
        if(hasShears) {
            addLeaves(base, -1, (byte) 0);
            addLeaves(base, +1, (byte) 1);
            addLeaves(base, -1, (byte) 2);
        }
    }

    @Override
    public WorkType getWorkType() {
        return WorkType.FORESTRY;
    }

    @Override
    public boolean onBlockClicked(EntityPlayer player) {
        if (!player.world.isRemote) {
            NetworkHandler.INSTANCE.openGui(player, NetworkHandler.GUI_WORKSITE_TREE_FARM, xCoord, yCoord, zCoord);
        }
        return true;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (!blocksToChop.isEmpty()) {
            NBTTagList chopList = new NBTTagList();
            for (BlockPos position : blocksToChop) {
                chopList.appendTag(position.writeToNBT(new NBTTagCompound()));
            }
            tag.setTag("targetList", chopList);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        blocksToChop.clear();
        if (tag.hasKey("targetList")) {
            NBTTagList chopList = tag.getTagList("targetList", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < chopList.tagCount(); i++) {
                blocksToChop.add(new BlockPos(chopList.getCompoundTagAt(i)));
            }
        }
    }

    @Override
    protected void scanBlockPosition(BlockPos pos) {
        Block block;
        if (canReplace(pos.x, pos.y, pos.z)) {
            block = world.getBlock(pos.x, pos.y - 1, pos.z);
            if (block == Blocks.dirt || block == Blocks.grass) {
                blocksToPlant.add(pos.copy());
            }
        } else {
            block = world.getBlock(pos.x, pos.y, pos.z);
            if (isFarmable(block, pos.x, pos.y, pos.z)) {
                blocksToFertilize.add(pos.copy());
            } else if (block.getMaterial() == Material.wood) {
                addTreeBlocks(block, pos);
            } else if (isUnwantedPlant(block)) {
                blocksToPlant.add(pos.copy());
            }
        }
    }
    
    private boolean isUnwantedPlant(Block block) {
        return block instanceof BlockBush && !(block instanceof BlockSapling);
    }

    private void addLeaves(BlockPos position, int offset, byte xOrYOrZ){
        BlockPos pos = position;
        if(xOrYOrZ == 0){
            pos = pos.offset(offset, 0, 0);
        }else if(xOrYOrZ == 1){
            pos = pos.offset(0, offset, 0);
        }else if(xOrYOrZ == 2){
            pos = pos.offset(0, 0, offset);
        }
        Block block = world.getBlock(pos.x, pos.y, pos.z);
        if(block instanceof IShearable){
            LEAF.findAttachedTreeBlocks(block, world, pos, blocksToShear);
        }
        if(offset<0){
            addLeaves(position, -offset, xOrYOrZ);
        }
    }

    @Override
    protected boolean hasWorksiteWork() {
        return (hasShears && !blocksToShear.isEmpty()) || !blocksToChop.isEmpty() || (bonemealCount > 0 && !blocksToFertilize.isEmpty()) || (saplingCount > 0 && !blocksToPlant.isEmpty());
    }
}
