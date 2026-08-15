package com.gachaman.data;

import com.google.gson.*;
import java.util.*;
import lombok.*;

/**
 * The loadout board's shape, read from loadout-board.json: where each GearSlot
 * sits and which native equipment silhouette fills it while it is empty.
 *
 * <p><b>One table, two views.</b> The sidebar's LoadoutTab lays the eleven
 * sockets out with a GridBagLayout and wants a (column, row) cell; the in-game
 * LoadoutOverlay paints them and wants a column and a pixel y. Both used to
 * carry their own hand-written copy of the same arrangement — eleven entries
 * each, plus an eleven-arm switch for the sprite ids — so the board had three
 * places to disagree with itself about where the ring goes. This is the one
 * declaration both now read.
 *
 * <p>{@code row} and {@code y} are the same position in the two units rather
 * than one derived from the other. The resource costs nothing — the Plugin Hub
 * counts only src/main/java — so spelling both out keeps the layout in exactly
 * one place instead of splitting it between a table and a formula.
 *
 * <p>Authored by {@code com.gachaman.tools.LoadoutBoard}, where the sprite ids
 * are {@code SpriteID.EQUIPMENT_SLOT_*} constants the compiler still checks, and
 * pinned to that declaration by {@code LoadoutBoardResourceTest} — the same
 * arrangement weapon-types.json and attack-anims.json use, and for the same
 * reason: a mistyped sprite id in raw JSON fails silently forever.
 */
public final class BoardLayout {
	private BoardLayout() {
	}

	/** One socket. Gson writes the fields; Lombok supplies the readers. */
	@Getter
	public static final class Socket {
		/** Column index into the board's three-column layout: 0 left, 1 mid, 2 right. */
		private int col;
		/** Row index, for the sidebar's GridBagLayout. */
		private int row;
		/** Pixel y from the top of the in-game board. */
		private int y;
		/** {@code SpriteID.EQUIPMENT_SLOT_*} for the empty-socket silhouette. */
		private int sprite;
	}

	/**
	 * The file's shape. It also carries a {@code note} key explaining how it is
	 * authored, deliberately not declared — Gson ignores keys it has no field
	 * for, and a resource nobody can leave a comment in should be allowed one.
	 */
	private static final class BoardFile {
		Map<String, Socket> sockets;
	}

	/**
	 * The socket table by {@code GearSlot.name()}, empty only when the resource
	 * failed to load.
	 *
	 * @param gson the CLIENT's injected Gson — never a fresh one, per DataJson
	 */
	public static Map<String, Socket> load(Gson gson) {
		BoardFile file = DataJson.load(gson, "loadout-board", BoardFile.class, new BoardFile());
		return file.sockets == null ? Collections.emptyMap() : file.sockets;
	}
}
