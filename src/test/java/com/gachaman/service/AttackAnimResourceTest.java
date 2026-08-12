package com.gachaman.service;

import com.gachaman.tools.*;
import com.google.gson.*;
import com.google.gson.reflect.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import org.junit.*;

/**
 * The shipped attack-anims.json must still be exactly what {@link AttackAnims}
 * declares.
 *
 * <p>This is the seam that makes moving the ids into a resource safe. In code
 * they were {@code AnimationID} constants and the compiler caught a rename; as
 * raw numbers in JSON nothing would. So the names stay in AttackAnims, where
 * they are still compiled against the live API, and this test pins the resource
 * to them.
 *
 * <p>What it prevents: a RuneLite rename silently dropping a teleport from
 * neverJudge, so teleporting mid-fight scores as a melee attack against a magic
 * contract and taints the kill. That failure is invisible until a player loses
 * a reward to it.
 *
 * <p>If this fails, run {@code ./gradlew attackAnims} and commit the result.
 */
public class AttackAnimResourceTest
{
	private static Map<String, Set<Integer>> shipped() throws Exception
	{
		try (InputStream in = AttackAnimResourceTest.class.getResourceAsStream(
			"/com/gachaman/data/attack-anims.json"))
		{
			Assert.assertNotNull("attack-anims.json is not on the classpath", in);
			return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
				new TypeToken<Map<String, Set<Integer>>>()
				{
				}.getType());
		}
	}

	@Test
	public void theResourceMatchesTheCompiledAnimationIds() throws Exception
	{
		Map<String, Set<Integer>> shipped = shipped();
		Map<String, Set<Integer>> declared = AttackAnims.groups();
		Assert.assertEquals("group names drifted", declared.keySet(), shipped.keySet());
		for (Map.Entry<String, Set<Integer>> entry : declared.entrySet())
		{
			Assert.assertEquals("stale attack-anims.json — run ./gradlew attackAnims ("
				+ entry.getKey() + ")", entry.getValue(), shipped.get(entry.getKey()));
		}
	}

	@Test
	public void everyMagicUtilityAnimationIsAlsoUnjudgeable() throws Exception
	{
		// a spell that pays Magic XP but is still judged as an attack would let an
		// alch mid-fight score against a melee contract
		Map<String, Set<Integer>> shipped = shipped();
		Assert.assertTrue("magicUtility must be a subset of neverJudge",
			shipped.get("neverJudge").containsAll(shipped.get("magicUtility")));
	}

	@Test
	public void noAnimationIsBothAnAttackAndNotAnAttack() throws Exception
	{
		Map<String, Set<Integer>> shipped = shipped();
		for (Integer id : shipped.get("offensiveMagic"))
		{
			Assert.assertFalse("animation " + id + " is classified both ways",
				shipped.get("neverJudge").contains(id));
		}
	}

	@Test
	public void noGroupIsEmpty() throws Exception
	{
		// the whole point of the fallback is that a missing resource degrades
		// rather than crashes — but an EMPTY shipped group is a silent regression
		for (Map.Entry<String, Set<Integer>> entry : shipped().entrySet())
		{
			Assert.assertFalse(entry.getKey() + " is empty", entry.getValue().isEmpty());
		}
	}
}
