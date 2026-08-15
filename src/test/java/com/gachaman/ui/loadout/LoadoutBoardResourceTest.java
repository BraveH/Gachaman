package com.gachaman.ui.loadout;

import com.gachaman.model.*;
import com.gachaman.tools.*;
import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;

/**
 * The shipped loadout-board.json must still be exactly what {@link LoadoutBoard}
 * declares, and it must still name every {@link GearSlot}.
 *
 * <p>This is the seam that makes moving the board's layout and its silhouette
 * sprite ids into a resource safe. In code the sprites are
 * {@code SpriteID.EQUIPMENT_SLOT_*} constants and the compiler catches a rename;
 * as raw numbers in JSON nothing would — and a wrong sprite id does not fail
 * loudly, it draws the wrong silhouette in an empty socket, or none at all, with
 * no error anywhere. A missing SLOT is worse still: that socket vanishes from
 * the board entirely, so the player cannot assign, unassign or claim a deed in
 * it, and nothing says why.
 *
 * <p>If the first test fails, run {@code ./gradlew loadoutBoard} and commit the
 * result.
 */
public class LoadoutBoardResourceTest
{
	private static class Socket
	{
		int col;
		int row;
		int y;
		int sprite;
	}

	private static class ShippedFile
	{
		String note;
		Map<String, Socket> sockets;
	}

	private static ShippedFile shipped() throws IOException
	{
		try (InputStream in = LoadoutBoardResourceTest.class.getResourceAsStream(
			"/com/gachaman/data/loadout-board.json"))
		{
			Assert.assertNotNull("loadout-board.json is not on the classpath", in);
			return new Gson().fromJson(
				new InputStreamReader(in, StandardCharsets.UTF_8), ShippedFile.class);
		}
	}

	@Test
	public void theShippedFileMatchesTheDeclaration() throws IOException
	{
		ShippedFile file = shipped();
		Assert.assertNotNull("the resource carries no sockets object", file.sockets);

		Map<String, Object> declared = LoadoutBoard.sockets();
		Assert.assertEquals("socket count drifted from com.gachaman.tools.LoadoutBoard",
			declared.size(), file.sockets.size());

		for (Map.Entry<String, Object> entry : declared.entrySet())
		{
			String slot = entry.getKey();
			@SuppressWarnings("unchecked")
			Map<String, Object> want = (Map<String, Object>) entry.getValue();
			Socket got = file.sockets.get(slot);
			Assert.assertNotNull(slot + " is missing from the shipped resource", got);
			Assert.assertEquals(slot + " column drifted",
				((Number) want.get("col")).intValue(), got.col);
			Assert.assertEquals(slot + " row drifted",
				((Number) want.get("row")).intValue(), got.row);
			Assert.assertEquals(slot + " y drifted",
				((Number) want.get("y")).intValue(), got.y);
			Assert.assertEquals(slot + " silhouette sprite drifted — run ./gradlew loadoutBoard",
				((Number) want.get("sprite")).intValue(), got.sprite);
		}
	}

	/**
	 * The two units the same position is written in must agree.
	 *
	 * <p>{@code row} drives the sidebar's GridBagLayout and {@code y} drives the
	 * in-game overlay's pixels, and they describe ONE board. Writing both out (a
	 * resource is free; the Hub counts only src/main/java) keeps the layout in one
	 * place instead of splitting it between a table and a formula — but only while
	 * something checks they still line up, which is this.
	 */
	@Test
	public void theRowAndPixelViewsOfEachSocketAgree() throws IOException
	{
		for (Map.Entry<String, Socket> entry : shipped().sockets.entrySet())
		{
			Socket socket = entry.getValue();
			Assert.assertEquals(entry.getKey() + ": row " + socket.row + " and y " + socket.y
					+ " place the socket in two different rows",
				LoadoutBoard.TOP + LoadoutBoard.PITCH * socket.row, socket.y);
		}
	}

	/**
	 * The exact pixel rows the overlay drew before the layout moved into a
	 * resource, spelled out rather than derived. The formula above and this list
	 * are checked against each other on purpose: a formula alone would happily
	 * validate a board that had been slid ten pixels down as a whole.
	 */
	@Test
	public void theBoardStillSitsWhereItAlwaysDid() throws IOException
	{
		Map<String, Socket> sockets = shipped().sockets;
		Assert.assertEquals(28, sockets.get("HEAD").y);
		Assert.assertEquals(72, sockets.get("CAPE").y);
		Assert.assertEquals(116, sockets.get("WEAPON").y);
		Assert.assertEquals(160, sockets.get("LEGS").y);
		Assert.assertEquals(204, sockets.get("RING").y);
		// and the three columns, at the corners of the arrangement
		Assert.assertEquals(1, sockets.get("HEAD").col);
		Assert.assertEquals(0, sockets.get("CAPE").col);
		Assert.assertEquals(2, sockets.get("AMMO").col);
	}

	/**
	 * Every GearSlot has a socket. The overlay skips a slot the resource does not
	 * name, so a slot added to the enum without a matching entry here would
	 * silently disappear from the board rather than fail.
	 */
	@Test
	public void everyGearSlotHasASocket() throws IOException
	{
		ShippedFile file = shipped();
		List<String> missing = new ArrayList<>();
		for (GearSlot slot : GearSlot.values())
		{
			if (file.sockets == null || !file.sockets.containsKey(slot.name()))
			{
				missing.add(slot.name());
			}
		}
		Assert.assertEquals("a GearSlot with no socket vanishes from the loadout board",
			Collections.emptyList(), missing);
	}

	/**
	 * The columns are an index into the overlay's three-column layout, and the y
	 * offsets have to land inside the board. Both are pinned because the overlay
	 * reads them straight into a Rectangle: an out-of-range column is skipped
	 * (invisible socket) and an out-of-range y draws off the panel.
	 */
	@Test
	public void everySocketLandsOnTheBoard() throws IOException
	{
		ShippedFile file = shipped();
		for (Map.Entry<String, Socket> entry : file.sockets.entrySet())
		{
			Socket socket = entry.getValue();
			Assert.assertTrue(entry.getKey() + " column " + socket.col + " is not 0, 1 or 2",
				socket.col >= 0 && socket.col <= 2);
			Assert.assertTrue(entry.getKey() + " y " + socket.y + " falls off the board",
				socket.y >= 0 && socket.y + 36 <= LoadoutOverlay.BOARD_H);
		}
	}

	/** A resource nobody can leave a comment in should at least carry a note. */
	@Test
	public void theFileExplainsItself() throws IOException
	{
		Assert.assertNotNull("loadout-board.json lost its note", shipped().note);
	}

	/**
	 * The shared binding must still carry every field the resource does. Gson
	 * binds by field NAME and silently leaves an unmatched one at its default, so
	 * a rename in BoardLayout.Socket would zero every column, row, y or sprite on
	 * both views of the board with nothing failing anywhere — reflection is
	 * forbidden in src/main/java, not here, and this is exactly the silent drift
	 * it is good at catching.
	 */
	@Test
	public void theSharedBindingStillMatchesTheResource() throws Exception
	{
		Set<String> fields = new TreeSet<>();
		for (Field field : com.gachaman.data.BoardLayout.Socket.class.getDeclaredFields())
		{
			fields.add(field.getName());
		}
		Assert.assertEquals("BoardLayout.Socket no longer matches loadout-board.json",
			new TreeSet<>(Arrays.asList("col", "row", "sprite", "y")), fields);
	}

	/**
	 * Both views of the board read the one table. A reader that quietly went back
	 * to a private hand-written copy would pass every test above while drifting
	 * from the other view, which is the failure this consolidation removed.
	 */
	@Test
	public void bothViewsReadTheSharedTable() throws IOException
	{
		Path root = com.gachaman.SourceHygieneTest.sourceRoot();
		Assert.assertNotNull(root);
		for (String file : Arrays.asList("com/gachaman/ui/loadout/LoadoutOverlay.java",
			"com/gachaman/ui/panel/LoadoutTab.java"))
		{
			String source = new String(Files.readAllBytes(root.resolve(file)),
				StandardCharsets.UTF_8);
			Assert.assertTrue(file + " must lay its sockets out from BoardLayout.load(gson)",
				source.contains("BoardLayout.load(gson)"));
		}
	}
}
