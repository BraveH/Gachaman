package com.gachaman.service;

import com.gachaman.data.BossTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Parses boss kill-count chat lines and queues a themed chest when a
 * configured milestone is crossed and unclaimed.
 */
@Slf4j
@Singleton
public class BossKcService
{
	// The counter noun varies by activity ("kill count", "chest count", ...) and is NOT
	// part of the captured name — Barrows' "Your Barrows chest count is: 5" captures
	// "Barrows", so bosses.json chatName must exclude the noun.
	static final Pattern KC_PATTERN =
		Pattern.compile("Your (.+?) (?:kill|chest|harvest|completion) count is:? ([\\d,]+)");

	private final GachaStateService stateService;
	private final BossTable bossTable;
	private final CeremonyBus ceremonyBus;

	@Inject
	public BossKcService(GachaStateService stateService, BossTable bossTable, CeremonyBus ceremonyBus)
	{
		this.stateService = stateService;
		this.bossTable = bossTable;
		this.ceremonyBus = ceremonyBus;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		Matcher matcher = KC_PATTERN.matcher(message);
		if (!matcher.find())
		{
			return;
		}
		String chatName = matcher.group(1);
		int kc;
		try
		{
			kc = Integer.parseInt(matcher.group(2).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return;
		}
		handleKc(chatName, kc);
	}

	void handleKc(String chatName, int kc)
	{
		if (stateService.get() == null)
		{
			return;
		}
		for (BossTable.Boss boss : bossTable.getBosses())
		{
			if (!boss.getChatName().equalsIgnoreCase(chatName))
			{
				continue;
			}
			for (int milestone : boss.getKcMilestones())
			{
				if (kc >= milestone)
				{
					awardMilestone(boss, milestone);
				}
			}
			return;
		}
	}

	/**
	 * Claims are keyed by boss NAME. They used to be keyed by set tag, but many
	 * bosses share a tag — all three Dagannoth Kings are "dagannoth" — so Rex
	 * crossing 25 KC silently consumed Prime's and Supreme's milestone chests too.
	 */
	private void awardMilestone(BossTable.Boss boss, int milestone)
	{
		String claimKey = boss.getBossName() + ":" + milestone;
		String legacyKey = boss.getSetTag() + ":" + milestone;
		var state = stateService.get();
		if (state == null || state.getBossKcClaims().contains(claimKey))
		{
			return;
		}
		if (state.getBossKcClaims().contains(legacyKey))
		{
			// A save written before the rekey holds one tag-keyed claim standing in
			// for what may be several bosses. Let this boss absorb it — silently,
			// because that chest was already paid out — so its tag-mates can still
			// earn their own instead of being locked out forever.
			stateService.mutate(s -> {
				Set<String> claims = new HashSet<>(s.getBossKcClaims());
				claims.remove(legacyKey);
				claims.add(claimKey);
				return s.withBossKcClaims(claims);
			});
			return;
		}
		stateService.mutate(s -> {
			Set<String> claims = new HashSet<>(s.getBossKcClaims());
			claims.add(claimKey);
			var queued = new ArrayList<>(s.getQueuedThemedChests());
			queued.add(boss.getSetTag());
			return s.withBossKcClaims(claims).withQueuedThemedChests(queued);
		});
		ceremonyBus.submit(CeremonyBus.Type.FANFARE, new CeremonyBus.Fanfare(
			CeremonyBus.Fanfare.Size.MEDIUM,
			boss.getBossName() + " " + milestone + " KC!",
			"A themed chest has been added to your shop.", null));
		log.debug("Boss KC milestone: {} {}", boss.getBossName(), milestone);
	}
}
