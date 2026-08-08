package com.gachaman.service;

import org.junit.Assert;
import org.junit.Test;

/**
 * The strip used to be recorded as settled the moment it was armed, so a logout
 * inside the tick budget left tutorial gear worn forever with nothing to retry
 * it. The plugin now clears its resume marker only when isStripComplete() says
 * a pass genuinely found nothing left to take off — these guard that contract.
 *
 * <p>A null Client is safe here: every path exercised below bails on the
 * ticksLeft check before the client is touched.
 */
public class UnequipServiceTest
{
	@Test
	public void freshServiceIsIdleAndIncomplete()
	{
		UnequipService service = new UnequipService(null);
		Assert.assertFalse(service.isArmed());
		Assert.assertFalse(service.isStripComplete());
	}

	@Test
	public void armingDoesNotCountAsCompleting()
	{
		UnequipService service = new UnequipService(null);
		service.arm();
		Assert.assertTrue(service.isArmed());
		// the whole point: armed is not done, or an interrupted strip never resumes
		Assert.assertFalse(service.isStripComplete());
	}

	@Test
	public void logoutMidStripLeavesItIncomplete()
	{
		UnequipService service = new UnequipService(null);
		service.arm();
		service.cancel(); // what a LOGIN_SCREEN transition does
		Assert.assertFalse(service.isArmed());
		Assert.assertFalse("a cancelled strip must resume next login",
			service.isStripComplete());
	}

	@Test
	public void tickOnAnUnarmedServiceNeitherWorksNorCompletes()
	{
		UnequipService service = new UnequipService(null);
		Assert.assertFalse(service.tick());
		Assert.assertFalse(service.isStripComplete());
	}
}
