package com.gachaman.tools;

import com.gachaman.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import net.runelite.api.SpriteID;

/**
 * Authors {@code loadout-board.json}: where each of the eleven GearSlot sockets
 * sits on the in-game loadout board, and which native equipment silhouette fills
 * it while it is empty.
 *
 * <p>Both tables are pure data — a column, a y offset and a sprite id per slot —
 * and the overlay that reads them used to spell all three out in code: eleven
 * {@code socketRects.put(...)} calls and an eleven-arm switch returning
 * {@code SpriteID.EQUIPMENT_SLOT_*}. That is 415 tokens of the Plugin Hub's
 * budget spent on a lookup table, and {@code src/main/resources} is not counted
 * at all, so the table belongs here.
 *
 * <p>The sprite ids stay spelled as {@link SpriteID} constants for exactly the
 * reason {@link WeaponTypes} keeps its dbrows as {@code DBTableID} constants:
 * the compiler checks a name and nothing checks a number, and a mistyped sprite
 * id does not fail loudly — it draws the wrong silhouette, or none, forever.
 * {@code LoadoutBoardResourceTest} pins the shipped resource back to this
 * declaration so the two cannot drift.
 *
 * <p><b>The column is an index, not an x.</b> The three columns are derived from
 * {@code BOARD_W} and {@code SOCKET} in the overlay, so writing absolute x
 * values here would silently desynchronise the board the day either constant
 * moved. The y offsets are absolute because nothing derives them.
 *
 * <p>Regenerate by running this class's {@code main} on the test classpath, or
 * {@code ./gradlew loadoutBoard}.
 */
public final class LoadoutBoard {
	private LoadoutBoard() {
	}

	private static final String NOTE = "Authored by com.gachaman.tools.LoadoutBoard from"
		+ " SpriteID.EQUIPMENT_SLOT_* constants. Do not hand-edit:"
		+ " LoadoutBoardResourceTest pins this file to those constants. 'col' is an"
		+ " index into the overlay's three-column layout (0 left, 1 middle, 2 right),"
		+ " not an absolute x — the columns are derived from BOARD_W and SOCKET.";

	/**
	 * One socket: its grid cell, its pixel y on the overlay, and its silhouette.
	 *
	 * <p>{@code row} and {@code y} are the SAME position measured for the two
	 * views of this board — the sidebar's GridBagLayout wants a row index, the
	 * in-game overlay wants pixels. Today {@code y == 28 + 44 * row} exactly, and
	 * both are written out rather than one derived from the other: the resource
	 * costs nothing (src/main/resources is not counted against the Hub's budget)
	 * and a formula in the overlay would be a second place for the layout to live.
	 * {@code LoadoutBoardResourceTest} pins the relationship so the two views
	 * cannot silently disagree about where a socket is.
	 */
	private static Map<String, Object> socket(int col, int row, int y, int sprite) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("col", col);
		entry.put("row", row);
		entry.put("y", y);
		entry.put("sprite", sprite);
		return entry;
	}

	/** The overlay's y for the top row, and the gap between rows. */
	public static final int TOP = 28;
	public static final int PITCH = 44;

	private static Map<String, Object> socket(int col, int row, int sprite) {
		return socket(col, row, TOP + PITCH * row, sprite);
	}

	/**
	 * The classic equipment-tab arrangement, in the reading order the eye takes
	 * down the board rather than GearSlot's declaration order — both readers key
	 * by slot name, so the order here is documentation only.
	 *
	 * <p>One table, two views. The sidebar's LoadoutTab and the in-game
	 * LoadoutOverlay drew the same eleven sockets in the same arrangement from two
	 * separate hand-written tables, which is one edit away from a board that
	 * disagrees with itself about where the ring goes.
	 */
	public static Map<String, Object> sockets() {
		Map<String, Object> sockets = new LinkedHashMap<>();
		sockets.put(GearSlot.HEAD.name(), socket(1, 0, SpriteID.EQUIPMENT_SLOT_HEAD));
		sockets.put(GearSlot.CAPE.name(), socket(0, 1, SpriteID.EQUIPMENT_SLOT_CAPE));
		sockets.put(GearSlot.AMULET.name(), socket(1, 1, SpriteID.EQUIPMENT_SLOT_NECK));
		sockets.put(GearSlot.AMMO.name(), socket(2, 1, SpriteID.EQUIPMENT_SLOT_AMMUNITION));
		sockets.put(GearSlot.WEAPON.name(), socket(0, 2, SpriteID.EQUIPMENT_SLOT_WEAPON));
		sockets.put(GearSlot.BODY.name(), socket(1, 2, SpriteID.EQUIPMENT_SLOT_TORSO));
		sockets.put(GearSlot.SHIELD.name(), socket(2, 2, SpriteID.EQUIPMENT_SLOT_SHIELD));
		sockets.put(GearSlot.LEGS.name(), socket(1, 3, SpriteID.EQUIPMENT_SLOT_LEGS));
		sockets.put(GearSlot.HANDS.name(), socket(0, 4, SpriteID.EQUIPMENT_SLOT_HANDS));
		sockets.put(GearSlot.FEET.name(), socket(1, 4, SpriteID.EQUIPMENT_SLOT_FEET));
		sockets.put(GearSlot.RING.name(), socket(2, 4, SpriteID.EQUIPMENT_SLOT_RING));
		return sockets;
	}

	public static void main(String[] args) throws IOException {
		Map<String, Object> file = new LinkedHashMap<>();
		file.put("note", NOTE);
		file.put("sockets", sockets());

		File out = new File("src/main/resources/com/gachaman/data/loadout-board.json");
		try (Writer w = Files.newBufferedWriter(out.toPath(), StandardCharsets.UTF_8)) {
			new GsonBuilder().setPrettyPrinting().create().toJson(file, w);
		}
		System.out.println("wrote " + out + " (" + sockets().size() + " sockets)");
	}
}
