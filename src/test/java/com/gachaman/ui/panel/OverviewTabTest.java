package com.gachaman.ui.panel;

import com.gachaman.*;
import com.gachaman.service.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import net.runelite.client.callback.*;
import org.junit.*;

/**
 * Two defects in the Overview panel that a compiler cannot see.
 *
 * <p>The first is a number that changes shape with the player's locale; the
 * second is an overload the compiler picks silently and wrongly. Neither shows
 * up on a UK/US developer machine, and neither needs a live client to pin.
 */
public class OverviewTabTest
{
	private Locale savedDisplay;
	private Locale savedFormat;

	/**
	 * {@code Locale.setDefault} is process-global and JUnit 4 shares one JVM
	 * across every test class, so a leaked German default would surface as a
	 * failure in somebody ELSE'S test, order-dependently. Both categories are
	 * saved because setDefault(Locale) overwrites both.
	 */
	@Before
	public void rememberLocale()
	{
		savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
		savedFormat = Locale.getDefault(Locale.Category.FORMAT);
	}

	@After
	public void restoreLocale()
	{
		Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
		Locale.setDefault(Locale.Category.FORMAT, savedFormat);
	}

	@Test
	public void aFractionKeepsItsDecimalPointOnACommaDecimalClient()
	{
		// de, fr, es, pt-BR and much of the EU: default-locale String.format
		// rendered these as "2,5" and "x1,5" while QuantityFormatter alongside
		// them was printing "1,250 GC" — two commas, opposite meanings
		Locale.setDefault(Locale.GERMANY);
		Assert.assertEquals("2.5", OverviewTab.trimDouble(2.5));
		Assert.assertEquals("0.5", OverviewTab.trimDouble(0.5));
		Locale.setDefault(Locale.FRANCE);
		Assert.assertEquals("2.5", OverviewTab.trimDouble(2.5));
	}

	@Test
	public void theDoubleDocketMultiplierIsAReachableFractionalCase()
	{
		// not a hypothetical path: the tuning value really is fractional, so the
		// Double Docket label and its tooltip both hit the format branch
		Assert.assertNotEquals(Tuning.DOUBLE_DOCKET_MULT,
			Math.floor(Tuning.DOUBLE_DOCKET_MULT), 0.0);
		Locale.setDefault(Locale.GERMANY);
		Assert.assertEquals("1.2", OverviewTab.trimDouble(Tuning.DOUBLE_DOCKET_MULT));
	}

	@Test
	public void wholeNumbersStayWholeInEveryLocale()
	{
		Locale.setDefault(Locale.GERMANY);
		Assert.assertEquals("3", OverviewTab.trimDouble(3));
		Assert.assertEquals("0", OverviewTab.trimDouble(0));
		Locale.setDefault(Locale.UK);
		Assert.assertEquals("3", OverviewTab.trimDouble(3));
	}

	@Test
	public void theCommonLocalesSeeExactlyWhatTheyAlreadySaw()
	{
		Locale.setDefault(Locale.UK);
		Assert.assertEquals("2.5", OverviewTab.trimDouble(2.5));
		Locale.setDefault(Locale.US);
		Assert.assertEquals("2.5", OverviewTab.trimDouble(2.5));
	}

	/**
	 * The three facts that together make {@code invokeLater(taskService::presentOffers)}
	 * a permanent per-tick leak, asserted separately so a failure says WHICH one
	 * moved.
	 *
	 * <p>ClientThread declares both overloads; presentOffers returns boolean; and
	 * for an exact method reference JLS 15.12.2.5 prefers the non-void functional
	 * interface. RuneLite re-queues a BooleanSupplier that returns false, which
	 * presentOffers does whenever the board is empty. If any of these three ever
	 * stops holding, the guard below is no longer needed and this test says so.
	 */
	@Test
	public void theOverloadTrapStillExists() throws NoSuchMethodException
	{
		Assert.assertNotNull(ClientThread.class.getMethod("invokeLater", Runnable.class));
		try
		{
			ClientThread.class.getMethod("invokeLater", BooleanSupplier.class);
		}
		catch (NoSuchMethodException e)
		{
			Assert.fail("ClientThread no longer declares invokeLater(BooleanSupplier);"
				+ " the ambiguity this guards against is gone and viewRolledContracts"
				+ " can be inlined again");
		}
		Assert.assertEquals("presentOffers must keep returning boolean for this trap"
			+ " to apply — if it went void the guard is moot",
			boolean.class, TaskService.class.getMethod("presentOffers").getReturnType());
	}

	@Test
	public void theViewButtonHopsThroughAVoidTarget()
	{
		// void is the whole point: it is the only return type that makes
		// invokeLater(Runnable) the more specific overload
		Method target;
		try
		{
			target = OverviewTab.class.getDeclaredMethod("viewRolledContracts");
		}
		catch (NoSuchMethodException e)
		{
			throw new AssertionError("viewRolledContracts is gone — the View Rolled"
				+ " Contracts button has probably been pointed straight at"
				+ " taskService::presentOffers again", e);
		}
		Assert.assertEquals(void.class, target.getReturnType());
		Assert.assertEquals(0, target.getParameterCount());
	}

	/**
	 * Reflection can prove the hop target is void but cannot see which target the
	 * button's lambda actually names, so the call site itself is pinned as text.
	 * Ugly, and worth it: reverting to the direct method reference is the exact
	 * edit that reintroduces the bug, and it compiles clean and runs fine until a
	 * click happens to land on an empty board.
	 */
	@Test
	public void theCallSiteNeverNamesTheBooleanReturningMethodDirectly() throws IOException
	{
		String code = codeOnly(overviewSource());

		Assert.assertFalse("invokeLater(taskService::presentOffers) binds to the"
			+ " BooleanSupplier overload and re-queues every tick forever",
			code.contains("invokeLater(taskService::presentOffers)"));
		Assert.assertTrue("the View Rolled Contracts button should hop through the"
			+ " void viewRolledContracts()",
			code.contains("invokeLater(this::viewRolledContracts)"));
		// The sibling call, invokeLater(taskService::rollOffers), is deliberately
		// NOT pinned here. It is safe because rollOffers returns List<TaskOffer>,
		// which is not assignable to boolean, so BooleanSupplier is not applicable
		// — safe by RETURN TYPE, not by spelling. Asserting on its text would turn
		// this test red if someone rewrote it as an equally-correct block lambda,
		// and this file still carries unapplied terser/dedupe findings in exactly
		// that block.
	}

	private static String overviewSource() throws IOException
	{
		Path root = SourceHygieneTest.sourceRoot();
		Assert.assertNotNull("could not locate src/main/java", root);
		Path file = root.resolve("com/gachaman/ui/panel/OverviewTab.java");
		Assert.assertTrue("missing " + file, Files.isRegularFile(file));
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	/**
	 * The source with comments stripped, so a prose mention of a call cannot
	 * stand in for the call itself. This matters here specifically:
	 * {@code viewRolledContracts}' own javadoc QUOTES the bad spelling in order
	 * to explain it, so an unstripped search would match the warning rather than
	 * the code and the test would fail while the code was correct.
	 *
	 * <p>Tracks string and char literals, including backslash escapes, so that a
	 * {@code "https://..."} URL is not mistaken for the start of a line comment
	 * — OverviewTab builds exactly such a literal for the wiki button. Nothing
	 * here needs to handle text blocks: the build targets Java 11.
	 */
	private static String codeOnly(String source)
	{
		final int code = 0;
		final int string = 1;
		final int character = 2;
		final int lineComment = 3;
		final int blockComment = 4;

		StringBuilder out = new StringBuilder(source.length());
		int mode = code;
		for (int i = 0; i < source.length(); i++)
		{
			char c = source.charAt(i);
			char next = i + 1 < source.length() ? source.charAt(i + 1) : '\n';
			if (mode == code)
			{
				if (c == '/' && next == '/')
				{
					mode = lineComment;
					i++;
				}
				else if (c == '/' && next == '*')
				{
					mode = blockComment;
					i++;
				}
				else
				{
					if (c == '"')
					{
						mode = string;
					}
					else if (c == '\'')
					{
						mode = character;
					}
					out.append(c);
				}
			}
			else if (mode == string || mode == character)
			{
				out.append(c);
				if (c == '\\')
				{
					// an escaped quote does not close the literal, and an escaped
					// backslash must not swallow the quote that follows it
					out.append(next);
					i++;
				}
				else if (c == (mode == string ? '"' : '\''))
				{
					mode = code;
				}
			}
			else if (mode == lineComment)
			{
				if (c == '\n')
				{
					// keep the newline so nothing on the next line joins this one
					out.append(c);
					mode = code;
				}
			}
			else if (c == '*' && next == '/')
			{
				mode = code;
				i++;
			}
		}
		return out.toString();
	}

	@Test
	public void theCommentStripperItselfBehaves()
	{
		// the four shapes the assertion above depends on getting right
		Assert.assertEquals("a  b", codeOnly("a /* x */ b"));
		Assert.assertEquals("a \n", codeOnly("a // x\n"));
		Assert.assertEquals("\"http://x\"", codeOnly("\"http://x\""));
		Assert.assertEquals("\"a\\\"//b\"", codeOnly("\"a\\\"//b\""));
	}
}
