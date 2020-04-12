package com.openrsc.server.model.container;

import com.openrsc.server.constants.ItemId;
import com.openrsc.server.database.GameDatabase;
import com.openrsc.server.database.GameDatabaseException;
import com.openrsc.server.external.ItemDefinition;
import com.openrsc.server.model.entity.player.Player;
import com.openrsc.server.net.rsc.ActionSender;
import com.openrsc.server.util.rsc.DataConversions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.*;


public class Bank {
	/**
	 * The asynchronous logger.
	 */
	private static final Logger LOGGER = LogManager.getLogger();

	// TODO: Use an ItemContainer rather than a list here.
	private List<Item> list = Collections.synchronizedList(new ArrayList<>());
	private final Player player;
	private final BankPreset[] bankPresets;

	public Bank(final Player player) {
		this.player = player;
		this.bankPresets = new BankPreset[BankPreset.PRESET_COUNT];
		for (int i = 0; i < bankPresets.length; ++i)
			bankPresets[i] = new BankPreset(player);
	}

	/**
	 * Attempts to add the item to the player's Bank.
	 * Updates the database.
	 * @param itemToAdd
	 * @return BOOLEAN flag if successful
	 */
	public boolean add(Item itemToAdd) {
		return add(itemToAdd, false); // Items are not automatically new when adding to the bank.
	}
	public boolean add(Item itemToAdd, boolean newItem) {
		synchronized(list) {
			try {
				// Check bounds of amount
				if (itemToAdd.getAmount() <= 0) {
					return false;
				}

				// Determine if there's already a spot in the bank for this item
				Item existingStack = null;
				int index = -1;

				for (Item bankItem : list) {
					++index;
					// Check for matching catalog ID's
					if (bankItem.getCatalogId() != itemToAdd.getCatalogId())
						continue;

					// Make sure the existing stack has room for more
					if (bankItem.getAmount() == Integer.MAX_VALUE)
						continue;

					// An existing stack has been found, exit the loop
					existingStack = bankItem;
					break;
				}

				System.out.println(itemToAdd.getItemId());

				if (existingStack == null) { /**We need to add a new item to the list*/
					// Make sure they have room in the bank
					if (list.size() >= player.getBankSize())
						return false;

					//Update the database and make sure the item ID is set
					player.getWorld().getServer().getDatabase().bankAddToPlayer(player, itemToAdd, list.size(), newItem);

					list.add(itemToAdd);

					//Update the client
					ActionSender.updateBankItem(player, list.size() - 1, itemToAdd.getCatalogId(), itemToAdd.getAmount());
				} else { /**There is an existing stack that can be added to*/
					// Check if the existing stack has enough room to hold the amount
					int remainingSize = Integer.MAX_VALUE - existingStack.getAmount();
					if (remainingSize >= itemToAdd.getAmount()) { /**The existing stack can hold the entire new stack*/
						// Change the existing stack amount
						existingStack.changeAmount(player.getWorld().getServer().getDatabase(), itemToAdd.getAmount());

						// Update the client
						ActionSender.updateBankItem(player, index, existingStack.getCatalogId(), existingStack.getAmount());
					} else { /**The existing stack will overflow*/

						// Determine how much is left over
						itemToAdd.getItemStatus().setAmount(itemToAdd.getAmount() - remainingSize);
						itemToAdd.setItemId(player.getWorld().getServer().getDatabase(), Item.ITEM_ID_UNASSIGNED);

						// Update the database and assign a new item ID
						player.getWorld().getServer().getDatabase().bankAddToPlayer(player, itemToAdd, list.size(), true);

						list.add(itemToAdd);

						// Update the existing stack amount to max value
						existingStack.setAmount(player.getWorld().getServer().getDatabase(), Integer.MAX_VALUE);

						// Update the client
						ActionSender.updateBankItem(player, index, existingStack.getCatalogId(), Integer.MAX_VALUE);
						ActionSender.updateBankItem(player, list.size()-1, itemToAdd.getCatalogId(), itemToAdd.getAmount());
					}
				}
			} catch (GameDatabaseException ex) {
				LOGGER.error(ex.getMessage());
				return false;
			}
			return true;
		}
	}

	public boolean canHold(ArrayList<Item> items) {
		synchronized(list) {
			return (getPlayer().getBankSize() - list.size()) >= getRequiredSlots(items);
		}
	}

	public boolean canHold(Item item) {
		synchronized(list) {
			return (getPlayer().getBankSize() - list.size()) >= getRequiredSlots(item);
		}
	}

	public boolean contains(Item i) {
		synchronized(list) {
			return list.contains(i);
		}
	}

	public int countId(int catalogID) {
		synchronized(list) {
			int ret = 0;
			for (Item i : list) {
				if (i.getCatalogId() == catalogID) {
					ret += i.getAmount();
				}
			}
			return ret;
		}
	}

	public boolean full() {
		synchronized(list) {
			return list.size() >= getPlayer().getBankSize();
		}
	}

	public Item get(int index) {
		synchronized(list) {
			if (index < 0 || index >= list.size()) {
				return null;
			}
			return list.get(index);
		}
	}

	public Item get(Item item) {
		synchronized(list) {
			for (Item i : list) {
				if (item.equals(i)) {
					return i;
				}
			}
			return null;
		}
	}

	public int getFirstIndexById(int id) {
		synchronized(list) {
			for (int index = 0; index < list.size(); index++) {
				if (list.get(index).getCatalogId() == id) {
					return index;
				}
			}
			return -1;
		}
	}

	public List<Item> getItems() {
		// TODO: This should be made private and all calls converted to use API on ItemContainer. This could stay public, IF we copy the list to a new list before returning.
		synchronized(list) {
			return list;
		}
	}

	public int getRequiredSlots(Item item) {
		synchronized(list) {
			//Check if there's a stack that can be added to
			for (Item bankItem : list) {
				//Check for matching catalogID
				if (bankItem.getCatalogId() != item.getCatalogId())
					continue;

				//Make sure there's room in the stack
				if (bankItem.getAmount() == Integer.MAX_VALUE)
					continue;

				//Check if all of the stack can fit in the existing stack
				int remainingSize = Integer.MAX_VALUE - bankItem.getAmount();
				return remainingSize < item.getAmount() ? 1 : 0;
			}

			//No existing stack was found
			return 1;
		}
	}

	public int getRequiredSlots(List<Item> items) {
		synchronized(list) {
			int requiredSlots = 0;
			for (Item item : items) {
				requiredSlots += getRequiredSlots(item);
			}
			return requiredSlots;
		}
	}

	public boolean hasItemId(int id) {
		synchronized(list) {
			for (Item i : list) {
				if (i.getCatalogId() == id)
					return true;
			}

			return false;
		}
	}

	public int searchBankSlots(int catalogID) {
		synchronized (list) {
			for (int i = 0; i < list.size(); ++i) {
				if (list.get(i).getCatalogId() == catalogID)
					return i;
			}
			return -1;
		}
	}

	public ListIterator<Item> iterator() {
		synchronized(list) {
			return list.listIterator();
		}
	}

	public void remove(int bankSlot) {
		synchronized(list) {
			Item item = get(bankSlot);
			if (item == null) {
				return;
			}
			remove(item.getCatalogId(), item.getAmount());
		}
	}

	public int remove(int catalogID, int amount) {
		synchronized(list) {
			try {
				ListIterator<Item> iterator = list.listIterator();
				Item bankItem = null;
				for (int index=0; iterator.hasNext(); ++index) {
					bankItem = iterator.next();

					//Match the catalog ID
					if (bankItem.getCatalogId() != catalogID)
						continue;

					//Check that there's enough in the stack
					if (bankItem.getAmount() < amount)
						return -1;

					//Check if there's exactly enough or if we need to split the stack
					if (bankItem.getAmount() == amount) {/**Exactly enough*/
						iterator.remove();

						//Update the DB
						player.getWorld().getServer().getDatabase().bankRemoveFromPlayer(player, bankItem);

						//Update the client
						if (true) //need a parameter for flagging to update the client or not
							ActionSender.updateBankItem(player, index, 0, 0);
					} else { /**Need to split the stack*/
						bankItem.changeAmount(player.getWorld().getServer().getDatabase(), -amount);

						if (true)//Need a new parameter for the function that flags if should update the client
							ActionSender.updateBankItem(player, index, bankItem.getCatalogId(), bankItem.getAmount());
					}

					return index;
				}
			} catch (GameDatabaseException ex) {
				LOGGER.error(ex.getMessage());
			}
			return -1;
		}
	}

	public int remove(Item item) {
		return remove(item.getCatalogId(), item.getAmount());
	}

	public int size() {
		synchronized(list) {
			return list.size();
		}
	}

	public boolean swap(int slot, int to) {
		synchronized(list) {
			if (slot <= 0 && to <= 0 && to == slot) {
				return false;
			}
			int idx = list.size() - 1;
			if (to > idx) {
				return false;
			}
			Item item = get(slot);
			Item item2 = get(to);
			if (item != null && item2 != null) {
				list.set(slot, item2);
				list.set(to, item);
				return true;
			}
			return false;
		}
	}

	public boolean insert(int slot, int to) {
		synchronized(list) {
			if (slot <= 0 && to <= 0 && to == slot) {
				return false;
			}
			int idx = list.size() - 1;
			if (to > idx) {
				return false;
			}
			// we reset the item in the from slot
			Item from = list.get(slot);
			Item[] array = list.toArray(new Item[list.size()]);
			if (slot >= array.length || from == null || to >= array.length) {
				return false;
			}
			array[slot] = null;
			// find which direction to shift in
			if (slot > to) {
				int shiftFrom = to;
				int shiftTo = slot;
				for (int i = (to + 1); i < slot; i++) {
					if (array[i] == null) {
						shiftTo = i;
						break;
					}
				}
				Item[] slice = new Item[shiftTo - shiftFrom];
				System.arraycopy(array, shiftFrom, slice, 0, slice.length);
				System.arraycopy(slice, 0, array, shiftFrom + 1, slice.length);
			} else {
				int sliceStart = slot + 1;
				int sliceEnd = to;
				for (int i = (sliceEnd - 1); i >= sliceStart; i--) {
					if (array[i] == null) {
						sliceStart = i;
						break;
					}
				}
				Item[] slice = new Item[sliceEnd - sliceStart + 1];
				System.arraycopy(array, sliceStart, slice, 0, slice.length);
				System.arraycopy(slice, 0, array, sliceStart - 1, slice.length);
			}
			// now fill in the target slot
			array[to] = from;
			list = new ArrayList<Item>(Arrays.asList(array));
			return true;
		}
	}

	public void setTab(int int1) {
		// TODO Auto-generated method stub

	}

	public boolean withdrawItemToInventory(final Integer catalogID, final Integer requestedAmount, final Boolean wantsNotes) {
		// Adjusts the requested amount if they ask for more than they have
		int adjustedRequestedAmount;

		// Will hold the amount actually withdrawn with this one method call
		int withdrawAmount;

		// Flag for if the item is withdrawn as a note
		boolean withdrawNoted = wantsNotes;

		synchronized (list) {
			synchronized (player.getCarriedItems().getInventory().getItems()) {
				// Check if the bank is empty
				if (list.isEmpty())
					return false;

				// Bounds checks on amount
				if (requestedAmount < 1)
					return false;

				// Cap the max requestedAmount
				int idCount = countId(catalogID);

				adjustedRequestedAmount = Math.min(idCount, requestedAmount);

				// Make sure they actually have the item in the bank
				if (adjustedRequestedAmount <= 0)
					return false;

				// Find bank slot that contains the requested catalogID
				Item withdrawItem = null, iteratedItem = null;
				ListIterator<Item> bankIterator = list.listIterator(list.size());

				for (int index = list.size() - 1; bankIterator.hasPrevious(); --index) {
					iteratedItem = bankIterator.previous();

					if (iteratedItem.getCatalogId() == catalogID) {
						withdrawItem = iteratedItem;
						break;
					}
				}

				// Double check the item was found
				if (withdrawItem == null)
					return false;

				// Check the item definition
				ItemDefinition withdrawDef = withdrawItem.getDef(player.getWorld());
				if (withdrawDef == null)
					return false;

				// Don't allow notes for stackables
				if (wantsNotes && withdrawDef.isStackable())
					withdrawNoted = false;

				// Logic for if they have two stacks of the same catalogID
				withdrawAmount = Math.min(withdrawItem.getAmount(), adjustedRequestedAmount);

				// Limit non-stackables to a withdraw of 1
				if (!withdrawDef.isStackable() && !withdrawNoted)
					withdrawAmount = 1;

				// Make sure they have enough space in their inventory
				if (!player.getCarriedItems().getInventory().canHold(new Item(withdrawItem.getCatalogId(), withdrawAmount, withdrawNoted, withdrawItem.getItemId()))) {
					player.message("You don't have room to hold everything!");
					return false;
				}

				// Determine if we need to split the stack
				if (withdrawItem.getAmount() > withdrawAmount) { /**The stack is being split*/
					withdrawItem = new Item(withdrawItem.getCatalogId(), withdrawAmount, withdrawNoted, withdrawItem.getItemId());
				} else { /**The stack is not being split*/
					if (withdrawNoted) {
						try{withdrawItem.setNoted(player.getWorld().getServer().getDatabase(), true);}
						catch (GameDatabaseException ex) { LOGGER.error(ex.getMessage()); return false;}
					}
				}

				// Attempt to remove the item from the bank
				if (remove(withdrawItem.getCatalogId(), withdrawAmount) == -1)
					return false;

				if (!player.getCarriedItems().getInventory().add(withdrawItem, true, false)) {
					// The withdraw failed. Re-add the items to the bank
					add(withdrawItem);
					return false;
				}

				//Check if we need to withdraw again to meet the request
				if (withdrawAmount < adjustedRequestedAmount)
					return withdrawItemToInventory(withdrawItem.getCatalogId(), adjustedRequestedAmount - withdrawAmount, wantsNotes);
				else {
					//Update the client
					ActionSender.sendInventory(player);
					return true;
				}
			}
		}
	}

	public boolean depositAllFromInventory() {
		synchronized (list) {
			synchronized (player.getCarriedItems().getInventory().getItems()) {
				try {
					for (int index = player.getCarriedItems().getInventory().getItems().size()-1; index >= 0; --index) {
						Item inventoryItem = player.getCarriedItems().getInventory().get(index);
						System.out.println("Depositing " + inventoryItem.getDef(player.getWorld()).getName() + "x"
							+ inventoryItem.getAmount() + " from slot " + index);
						if (!depositItemFromInventory(inventoryItem.getCatalogId(), inventoryItem.getAmount(), false))
							return false;
					}

					ActionSender.sendInventory(player);
				} catch (Exception ex) {
					LOGGER.error(ex.getMessage());
					return false;
				}
				return true;
			}
		}
	}

	public boolean depositItemFromInventory(final int catalogID, int requestedAmount, final Boolean updateClient) {

		synchronized (list) {
			synchronized (player.getCarriedItems().getInventory().getItems()) {

				// Find inventory slot that contains the requested catalogID
				Item depositItem = null, iteratedItem = null;
				List<Item> playerItems = player.getCarriedItems().getInventory().getItems();
				for (int i = playerItems.size(); i-- > 0;) {
					iteratedItem = playerItems.get(i);
					if (iteratedItem.getCatalogId() == catalogID) {
						depositItem = iteratedItem;
						break;
					}
				}

				// Double check there was an item found
				if (depositItem == null || requestedAmount < 1) {
					System.out.println(player.getUsername() + " attempted to deposit an item that is null or < 1 in quantity: " + catalogID);
					return false;
				}

				// Ensure the final deposit does not exceed item quantity.
				int depositAmount = Math.min(depositItem.getAmount(), requestedAmount);

				// Check the item definition
				ItemDefinition depositDef = depositItem.getDef(player.getWorld());
				if (depositDef == null) {
					System.out.println(player.getUsername() + " attempted to deposit an item that has a null def: " + catalogID);
					return false;
				}

				// Limit non-stackables to a withdraw of 1
				if (!depositDef.isStackable() && !depositItem.getNoted())
					depositAmount = 1;

				// Make sure they have enough space in their bank to deposit it
				if (!canHold(new Item(depositItem.getCatalogId(), depositAmount))) {
					player.message("You don't have room for that in your bank");
					return false;
				}

				// We split the stack if some of the stack will be in the inventory,
				// and some of the stack will be in the bank. Effectively, this means one
				// ItemStatus but one of each inventory and bank db entries.
				if (depositDef.isStackable() && depositAmount < depositItem.getAmount()) {
					int itemId = depositItem.getItemId();
					depositItem = new Item(depositItem.getCatalogId(), depositAmount, false, depositItem.getItemId());
					try {
						depositItem.setItemId(player.getWorld().getServer().getDatabase(), itemId);
					}
					catch (GameDatabaseException e) { System.out.println(e);}
				}

				// Player's shouldn't be able to bank notes
				if (depositItem.getNoted()) {
					try{depositItem.setNoted(player.getWorld().getServer().getDatabase(), false);}
					catch (GameDatabaseException ex) { LOGGER.error(ex.getMessage()); return false; }
				}

				// Attempt to remove the item from the inventory
				if (player.getCarriedItems().getInventory().remove(
						depositItem.getCatalogId(), depositAmount, updateClient, false) == -1
					) {
					System.out.println(player.getUsername() + " failed to remove item from inventory: " + catalogID);
					return false;
				}

				if (!add(depositItem, false)) {
					// The deposit failed. Re-add the items to the inventory
					player.getCarriedItems().getInventory().add(depositItem, true, false);
					System.out.println(player.getUsername() + " failed to deposit an item: " + catalogID);
					return false;
				}

				// Check if we need to deposit again to meet the request
				if (depositAmount < requestedAmount)
					return depositItemFromInventory(
						depositItem.getCatalogId(),
						requestedAmount - depositAmount,
						updateClient
					);
				else {
					ActionSender.sendInventory(player);
					return true;
				}
			}
		}
	}

	private static boolean isCert(int itemID) {
		int[] certIds = {
			/* Ores **/
			517, 518, 519, 520, 521,
			/* Bars **/
			528, 529, 530, 531, 532,
			/* Fish **/
			533, 534, 535, 536, 628, 629, 630, 631,
			/* Logs **/
			711, 712, 713,
			/* Misc **/
			1270, 1271, 1272, 1273, 1274, 1275
		};

		return DataConversions.inArray(certIds, itemID);
	}

	private static int uncertedID(int itemID) {

		if (itemID == ItemId.IRON_ORE_CERTIFICATE.id()) {
			return ItemId.IRON_ORE.id();
		} else if (itemID == ItemId.COAL_CERTIFICATE.id()) {
			return ItemId.COAL.id();
		} else if (itemID == ItemId.MITHRIL_ORE_CERTIFICATE.id()) {
			return ItemId.MITHRIL_ORE.id();
		} else if (itemID == ItemId.SILVER_CERTIFICATE.id()) {
			return ItemId.SILVER.id();
		} else if (itemID == ItemId.GOLD_CERTIFICATE.id()) {
			return ItemId.GOLD.id();
		} else if (itemID == ItemId.IRON_BAR_CERTIFICATE.id()) {
			return ItemId.IRON_BAR.id();
		} else if (itemID == ItemId.STEEL_BAR_CERTIFICATE.id()) {
			return ItemId.STEEL_BAR.id();
		} else if (itemID == ItemId.MITHRIL_BAR_CERTIFICATE.id()) {
			return ItemId.MITHRIL_BAR.id();
		} else if (itemID == ItemId.SILVER_BAR_CERTIFICATE.id()) {
			return ItemId.SILVER_BAR.id();
		} else if (itemID == ItemId.GOLD_BAR_CERTIFICATE.id()) {
			return ItemId.GOLD_BAR.id();
		} else if (itemID == ItemId.LOBSTER_CERTIFICATE.id()) {
			return ItemId.LOBSTER.id();
		} else if (itemID == ItemId.RAW_LOBSTER_CERTIFICATE.id()) {
			return ItemId.RAW_LOBSTER.id();
		} else if (itemID == ItemId.SWORDFISH_CERTIFICATE.id()) {
			return ItemId.SWORDFISH.id();
		} else if (itemID == ItemId.RAW_SWORDFISH_CERTIFICATE.id()) {
			return ItemId.RAW_SWORDFISH.id();
		} else if (itemID == ItemId.BASS_CERTIFICATE.id()) {
			return ItemId.BASS.id();
		} else if (itemID == ItemId.RAW_BASS_CERTIFICATE.id()) {
			return ItemId.RAW_BASS.id();
		} else if (itemID == ItemId.SHARK_CERTIFICATE.id()) {
			return ItemId.SHARK.id();
		} else if (itemID == ItemId.RAW_SHARK_CERTIFICATE.id()) {
			return ItemId.RAW_SHARK.id();
		} else if (itemID == ItemId.YEW_LOGS_CERTIFICATE.id()) {
			return ItemId.YEW_LOGS.id();
		} else if (itemID == ItemId.MAPLE_LOGS_CERTIFICATE.id()) {
			return ItemId.MAPLE_LOGS.id();
		} else if (itemID == ItemId.WILLOW_LOGS_CERTIFICATE.id()) {
			return ItemId.WILLOW_LOGS.id();
		} else if (itemID == ItemId.DRAGON_BONE_CERTIFICATE.id()) {
			return ItemId.DRAGON_BONES.id();
		} else if (itemID == ItemId.LIMPWURT_ROOT_CERTIFICATE.id()) {
			return ItemId.LIMPWURT_ROOT.id();
		} else if (itemID == ItemId.PRAYER_POTION_CERTIFICATE.id()) {
			return ItemId.FULL_RESTORE_PRAYER_POTION.id();
		} else if (itemID == ItemId.SUPER_ATTACK_POTION_CERTIFICATE.id()) {
			return ItemId.FULL_SUPER_ATTACK_POTION.id();
		} else if (itemID == ItemId.SUPER_DEFENSE_POTION_CERTIFICATE.id()) {
			return ItemId.FULL_SUPER_DEFENSE_POTION.id();
		} else if (itemID == ItemId.SUPER_STRENGTH_POTION_CERTIFICATE.id()) {
			return ItemId.FULL_SUPER_STRENGTH_POTION.id();
		} else {
			return itemID;
		}
	}

	public Player getPlayer() {
		return player;
	}

	public BankPreset getBankPreset(int slot) { return this.bankPresets[slot]; }
}
