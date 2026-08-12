package com.gachaman.data;

import com.gachaman.model.*;
import org.junit.*;

/**
 * Which style a weapon actually swings with, read off its offensive bonuses.
 * The First Colours gift promises the player something they can use in the
 * style they just rolled, so a wrong answer here is a broken promise.
 */
public class DominantStyleTest
{
	@Test
	public void meleeWinsOnAnyOfItsThreeAttackTypes()
	{
		// stab, slash and crush are three faces of one style — a weapon that
		// leads on any of them is a melee weapon
		Assert.assertEquals(AttackStyle.MELEE, CardDatabase.dominantStyle(40, 0, 0, 0, 0));
		Assert.assertEquals(AttackStyle.MELEE, CardDatabase.dominantStyle(0, 45, 0, 0, 0));
		Assert.assertEquals(AttackStyle.MELEE, CardDatabase.dominantStyle(0, 0, 55, 0, 0));
		// a warhammer's spread across all three still reads as one style
		Assert.assertEquals(AttackStyle.MELEE, CardDatabase.dominantStyle(10, 20, 65, 5, -4));
	}

	@Test
	public void rangedAndMagicWinOnTheirOwnBonus()
	{
		Assert.assertEquals(AttackStyle.RANGED, CardDatabase.dominantStyle(0, 0, 0, 30, 0));
		Assert.assertEquals(AttackStyle.MAGIC, CardDatabase.dominantStyle(0, 0, 0, 0, 25));
		// a shortbow's small melee stats must not outvote its actual purpose
		Assert.assertEquals(AttackStyle.RANGED, CardDatabase.dominantStyle(2, 3, 1, 30, 0));
		// a staff that can also bash still leads with magic
		Assert.assertEquals(AttackStyle.MAGIC, CardDatabase.dominantStyle(0, 5, 8, 0, 12));
	}

	@Test
	public void tiesAreRefusedRatherThanGuessed()
	{
		// gear that is not unambiguously one style's weapon is not a promise
		// worth making — better no steer at all than the wrong one
		Assert.assertNull(CardDatabase.dominantStyle(20, 0, 0, 20, 0));
		Assert.assertNull(CardDatabase.dominantStyle(0, 0, 0, 15, 15));
		Assert.assertNull(CardDatabase.dominantStyle(0, 9, 0, 0, 9));
		Assert.assertNull(CardDatabase.dominantStyle(7, 7, 7, 7, 7));
	}

	@Test
	public void noOffenceAtAllIsNotAWeapon()
	{
		// pure armour, and the shields/robes that carry a magic PENALTY: a
		// negative or zero best must never be dressed up as a style
		Assert.assertNull(CardDatabase.dominantStyle(0, 0, 0, 0, 0));
		Assert.assertNull(CardDatabase.dominantStyle(-1, -1, -1, -5, -20));
		Assert.assertNull(CardDatabase.dominantStyle(0, 0, 0, 0, -30));
	}

	@Test
	public void aNegativeBonusNeverWins()
	{
		// a melee weapon with the usual magic penalty is still melee, and a
		// staff with a melee penalty is still magic
		Assert.assertEquals(AttackStyle.MELEE, CardDatabase.dominantStyle(0, 32, 0, -2, -12));
		Assert.assertEquals(AttackStyle.MAGIC, CardDatabase.dominantStyle(-5, -5, -5, -5, 3));
		Assert.assertEquals(AttackStyle.RANGED, CardDatabase.dominantStyle(-8, -8, -8, 1, -8));
	}
}
