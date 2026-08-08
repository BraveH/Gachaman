package com.gachaman.service;

import java.util.List;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * The starter grant used to hand every profile all six ironman sets and park a
 * Hardcore ironman platebody in the body slot. Only the set matching the
 * account type varbit is wearable, so only that set may be granted.
 */
public class IronmanGearTest
{
	@Test
	public void normalAccountsGetNoIdentityArmour()
	{
		Assert.assertTrue(IronmanGear.cardNames(IronmanGear.NORMAL).isEmpty());
		Assert.assertNull(IronmanGear.bodyCardName(IronmanGear.NORMAL));
	}

	@Test
	public void eachAccountTypeMapsToItsOwnSet()
	{
		Assert.assertEquals("Ironman platebody", IronmanGear.bodyCardName(1));
		Assert.assertEquals("Ultimate ironman platebody", IronmanGear.bodyCardName(2));
		Assert.assertEquals("Hardcore ironman platebody", IronmanGear.bodyCardName(3));
		Assert.assertEquals("Group ironman platebody", IronmanGear.bodyCardName(4));
		Assert.assertEquals("Hardcore group ironman platebody", IronmanGear.bodyCardName(5));
		Assert.assertEquals("Unranked group ironman platebody", IronmanGear.bodyCardName(6));
	}

	@Test
	public void everySetIsAHelmABodyAndLegsAndSetsNeverOverlap()
	{
		int total = 0;
		for (int type = 1; type <= 6; type++)
		{
			List<String> set = IronmanGear.cardNames(type);
			Assert.assertEquals("account type " + type, 3, set.size());
			Assert.assertTrue(set.get(0).endsWith(" helm"));
			Assert.assertTrue(set.get(1).endsWith(" platebody"));
			Assert.assertTrue(set.get(2).endsWith(" platelegs"));
			total += set.size();
		}
		// allCardNames is the revoke denylist — a name shared between two sets
		// would silently exempt itself, so the union must lose nothing
		Assert.assertEquals(total, IronmanGear.allCardNames().size());
	}

	@Test
	public void anUnknownAccountTypeGrantsNothingRatherThanTheWrongSet()
	{
		Assert.assertTrue(IronmanGear.cardNames(7).isEmpty());
		Assert.assertTrue(IronmanGear.cardNames(-1).isEmpty());
		Assert.assertNull(IronmanGear.bodyCardName(99));
	}

	@Test
	public void theDenylistCoversEverySetSoForeignCopiesCanBeRevoked()
	{
		Set<String> all = IronmanGear.allCardNames();
		for (int type = 1; type <= 6; type++)
		{
			Assert.assertTrue("set " + type + " missing from the denylist",
				all.containsAll(IronmanGear.cardNames(type)));
		}
	}

	/** A null client (headless, or logged out) must read as normal, not crash. */
	@Test
	public void aMissingClientReadsAsNormal()
	{
		Assert.assertEquals(IronmanGear.NORMAL, IronmanGear.accountType(null));
	}
}
