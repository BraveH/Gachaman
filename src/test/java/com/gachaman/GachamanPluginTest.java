package com.gachaman;

import net.runelite.client.*;
import net.runelite.client.externalplugins.*;

public class GachamanPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GachamanPlugin.class);
		RuneLite.main(args);
	}
}
