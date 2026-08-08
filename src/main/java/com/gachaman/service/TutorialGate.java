package com.gachaman.service;

import net.runelite.api.Client;

/**
 * Tutorial Island exclusion: none of Gachaman's locks apply until the
 * tutorial is complete. Varp 281 tracks tutorial progress; 1000 = done.
 */
public final class TutorialGate
{
	public static final int TUTORIAL_PROGRESS_VARP = 281;
	public static final int TUTORIAL_COMPLETE = 1000;

	private TutorialGate()
	{
	}

	public static boolean onTutorial(Client client)
	{
		try
		{
			return client.getVarpValue(TUTORIAL_PROGRESS_VARP) < TUTORIAL_COMPLETE;
		}
		catch (Exception e)
		{
			return false;
		}
	}
}
