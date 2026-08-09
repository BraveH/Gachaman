package com.gachaman.persist;

import com.gachaman.model.ActiveTask;
import com.gachaman.model.AttackStyle;
import com.gachaman.model.ContractRecord;
import com.gachaman.model.DossierSummary;
import com.gachaman.model.GachaState;
import com.gachaman.model.OwnedCard;
import com.gachaman.model.PatronRecord;
import com.gachaman.model.TaskDifficulty;
import com.gachaman.model.TaskOffer;
import com.gachaman.model.Variant;
import com.gachaman.service.PatronMark;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class StateCodecTest
{
	private final StateCodec codec = new StateCodec(new Gson());

	@Test
	public void roundTripPreservesState()
	{
		GachaState state = GachaState.fresh(42);
		List<OwnedCard> cards = new ArrayList<>();
		cards.add(new OwnedCard("u1", 1333, null, Variant.NORMAL, 123L, "chest:BATTERED", 0));
		cards.add(new OwnedCard("u2", -1, "dragon", Variant.HOLOGRAM, 456L, "chest:ORNATE", 7));
		state = state.withOwnedCards(cards).withGc(12345).withTaint(3).withAllowedStyle("MAGIC")
			.withStardust(5).withStardustBlessArmed(true)
			.withFreeCompactors(1).withFreeExtenders(1).withStarterVouchersGranted(true)
			.withDeedFragments(7).withFragmentDeedForged(true);

		GachaState decoded = codec.decode(codec.encode(state));
		Assert.assertNotNull(decoded);
		Assert.assertEquals(12345, decoded.getGc());
		Assert.assertEquals(3, decoded.getTaint());
		Assert.assertEquals("MAGIC", decoded.getAllowedStyle());
		Assert.assertEquals(2, decoded.getOwnedCards().size());
		Assert.assertEquals("dragon", decoded.getOwnedCards().get(1).getTierKey());
		Assert.assertTrue(decoded.getOwnedCards().get(1).isHologram());
		Assert.assertEquals(0, decoded.getOwnedCards().get(0).getKillsServed());
		Assert.assertEquals("the Service Record must survive gzip + SHA-256",
			7, decoded.getOwnedCards().get(1).getKillsServed());
		Assert.assertEquals(3, decoded.getDeededSlots().size()); // weapon + body + ammo
		Assert.assertEquals(5, decoded.getStardust());
		Assert.assertTrue(decoded.isStardustBlessArmed());
		Assert.assertEquals(1, decoded.getFreeCompactors());
		Assert.assertEquals(1, decoded.getFreeExtenders());
		Assert.assertTrue(decoded.isStarterVouchersGranted());
		Assert.assertEquals(7, decoded.getDeedFragments());
		Assert.assertTrue(decoded.isFragmentDeedForged());
	}

	@Test
	public void legacyOwnedCardDefaultsToZeroService()
	{
		// a card acquired before the Service Record existed: Gson leaves the
		// absent primitive at 0, which is the TRUTH for it — no service was ever
		// recorded. normalized() cannot help here (it backfills null COLLECTIONS
		// only, and it never descends into ownedCards), so the safe-default
		// choice is the whole migration story.
		OwnedCard legacy = new Gson().fromJson(
			"{\"uuid\":\"u\",\"cardId\":1333,\"variant\":\"NORMAL\"}", OwnedCard.class);
		Assert.assertEquals(0, legacy.getKillsServed());
	}

	@Test
	public void legacyPayloadNormalizesNewFields()
	{
		// a pre-feature save: none of the early-game fields exist in the JSON
		GachaState legacy = new Gson().fromJson("{\"gc\":5,\"totalTasksCompleted\":2}", GachaState.class);
		Assert.assertNull(legacy.getFirstsClaimed());
		GachaState normalized = legacy.normalized();
		Assert.assertEquals(5, normalized.getGc());
		Assert.assertNotNull(normalized.getFirstsClaimed());
		Assert.assertNotNull(normalized.getSpeciesDiscovered());
		Assert.assertNotNull(normalized.getSlotBestTierRank());
		Assert.assertNotNull(normalized.getTimeline());
		Assert.assertNotNull(normalized.getOwnedCards());
		Assert.assertNotNull(normalized.getDeededSlots());
		Assert.assertNotNull(normalized.getQueuedThemedChests());
		Assert.assertEquals(0, normalized.getStardust());
		Assert.assertFalse(normalized.isStardustBlessArmed());
		Assert.assertEquals(0, normalized.getFreeCompactors());
		Assert.assertEquals(0, normalized.getFreeExtenders());
		Assert.assertFalse("false flag is the migration trigger", normalized.isStarterVouchersGranted());
		Assert.assertEquals(0, normalized.getDeedFragments());
		Assert.assertFalse(normalized.isFragmentDeedForged());
	}

	@Test
	public void legacyPayloadWithoutPatronsNormalizesToEmpty()
	{
		// The Patron's Mark ledger did not exist when this save was written, so
		// zero shared contracts is not a lossy default — it is the only honest
		// one. It is also inert: an empty map means no top patron, no tier and
		// no mark, no Patrons tab, and nothing anywhere gates on the field.
		GachaState legacy = new Gson().fromJson("{\"gc\":5}", GachaState.class);
		Assert.assertNull(legacy.getPatrons());
		GachaState normalized = legacy.normalized();
		Assert.assertNotNull(normalized.getPatrons());
		Assert.assertTrue(normalized.getPatrons().isEmpty());
		Assert.assertNull(PatronMark.topKey(normalized.getPatrons()));
	}

	@Test
	public void aPreRenameLedgerIsDroppedRatherThanFailingTheWholeSave()
	{
		// the field was renamed rather than re-typed on purpose: the old
		// "partnerContracts" held a Map<String,Integer>, and a bare number where
		// Gson now expects an object throws and takes the ENTIRE save with it,
		// not just this one ledger. Under the new name Gson drops the old key as
		// unknown, which costs a cosmetic tally and nothing else.
		GachaState legacy = new Gson().fromJson(
			"{\"gc\":5,\"partnerContracts\":{\"Zezima\":3}}", GachaState.class);
		Assert.assertEquals(5, legacy.getGc());
		Assert.assertNull(legacy.getPatrons());
		Assert.assertTrue(legacy.normalized().getPatrons().isEmpty());
	}

	@Test
	public void patronsSurviveARoundTrip()
	{
		Map<String, PatronRecord> patrons = new LinkedHashMap<>();
		patrons.put("00112233445566aa", new PatronRecord("Zezima", 3, 1_700_000_000_000L));
		patrons.put("00112233445566bb", new PatronRecord("B0aty", 41, 1_700_000_005_000L));
		GachaState state = GachaState.fresh(42).withPatrons(patrons);

		GachaState decoded = codec.decode(codec.encode(state));
		Assert.assertNotNull(decoded);
		Map<String, PatronRecord> back = decoded.getPatrons();
		Assert.assertEquals(2, back.size());
		Assert.assertEquals(3, back.get("00112233445566aa").getCount());
		Assert.assertEquals("the label is persisted, not re-derived — a partner who"
			+ " never joins again still draws with a name",
			"B0aty", back.get("00112233445566bb").getName());
		Assert.assertEquals(1_700_000_005_000L,
			back.get("00112233445566bb").getLastSharedAt());
		// Gson rebuilds this as a LinkedTreeMap, so pin that the reader still
		// answers correctly against whatever map type comes back off disk
		Assert.assertEquals("00112233445566bb", PatronMark.topKey(back));
		Assert.assertEquals("a received key is normalized before lookup",
			41, PatronMark.countFor(back, "00112233445566BB"));
	}

	@Test
	public void legacyPayloadWithoutAContractLogNormalizesToEmpty()
	{
		// the Dossier did not exist when this save was written. An empty log is
		// inert: the totals fold to zero and the tab shows its empty note, so no
		// history is invented for a player who has none.
		GachaState legacy = new Gson().fromJson("{\"gc\":5}", GachaState.class);
		Assert.assertNull(legacy.getContractLog());
		GachaState normalized = legacy.normalized();
		Assert.assertNotNull(normalized.getContractLog());
		Assert.assertTrue(normalized.getContractLog().isEmpty());
		Assert.assertEquals(0, DossierSummary.of(normalized.getContractLog()).getContracts());
	}

	@Test
	public void contractLogSurvivesTheCodec()
	{
		GachaState state = GachaState.fresh(42).withContractLog(Arrays.asList(
			new ContractRecord(1700000000000L, "Goblin", "EASY", 15, 400, 60_000, "MELEE",
				0, null, false, false),
			new ContractRecord(1700000060000L, "Blue dragon", "HARD", 25, 3200, 900_000, "MAGIC",
				2, "Party of 3", true, true)));

		GachaState decoded = codec.decode(codec.encode(state));
		Assert.assertNotNull(decoded);
		List<ContractRecord> log = decoded.getContractLog();
		Assert.assertEquals(2, log.size());
		Assert.assertEquals("Goblin", log.get(0).getMonsterName());
		Assert.assertTrue(log.get(0).isClean());
		ContractRecord second = log.get(1);
		Assert.assertEquals(1700000060000L, second.getAt());
		Assert.assertEquals("HARD", second.getDifficulty());
		Assert.assertEquals(3200, second.getGc());
		Assert.assertEquals("MAGIC", second.getStyle());
		Assert.assertEquals("Party of 3", second.getParty());
		Assert.assertTrue(second.isCarried());
		Assert.assertTrue(second.isRedemption());
		Assert.assertEquals("the clean verdict is derived from a COUNT that must survive",
			2, second.getTaintedKills());
		Assert.assertFalse(second.isClean());
	}

	@Test
	public void aRecordFiledByAFutureBuildStillLoads()
	{
		// difficulty and style are persisted as plain strings, not enums: Gson maps
		// an unknown ENUM constant to null and would silently erase the filed fact,
		// whereas a string keeps it and leaves the renderer to fall back
		GachaState legacy = new Gson().fromJson(
			"{\"contractLog\":[{\"at\":5,\"monsterName\":\"Goblin\",\"difficulty\":\"NIGHTMARE\","
				+ "\"kills\":3,\"gc\":90,\"style\":\"DRAGONFIRE\"}]}", GachaState.class);
		ContractRecord record = legacy.normalized().getContractLog().get(0);
		Assert.assertEquals("NIGHTMARE", record.getDifficulty());
		Assert.assertEquals("DRAGONFIRE", record.getStyle());
		Assert.assertTrue("an absent taintedKills is zero, which reads clean", record.isClean());
	}

	@Test
	public void activeTaskPartyStylesSurviveTheCodec()
	{
		// partyStyles is the first enum collection nested inside activeTask, so
		// no persisted task has ever been round-tripped before this
		GachaState state = GachaState.fresh(42).withActiveTask(ActiveTask.builder()
			.difficulty(TaskDifficulty.EASY)
			.monsterName("Goblin")
			.killsRequired(10)
			.completionGc(1000)
			.partyLabel("Party of 3")
			.partyStyles(Arrays.asList(AttackStyle.MELEE, null, AttackStyle.MAGIC))
			.build());

		GachaState decoded = codec.decode(codec.encode(state));
		Assert.assertNotNull(decoded);
		List<AttackStyle> styles = decoded.getActiveTask().getPartyStyles();
		// Gson writes null ARRAY elements regardless of serializeNulls, so the
		// hole survives the trip — the null skip in distinctStyles is
		// load-bearing on the persistence path, not just the wire
		Assert.assertEquals(Arrays.asList(AttackStyle.MELEE, null, AttackStyle.MAGIC), styles);
	}

	@Test
	public void legacyActiveTaskDecodesNullPartyStyles()
	{
		// a shared contract signed before the snapshot existed
		GachaState legacy = new Gson().fromJson(
			"{\"gc\":5,\"activeTask\":{\"monsterName\":\"Goblin\",\"killsRequired\":10,"
				+ "\"partyLabel\":\"Party of 2\"}}", GachaState.class);
		Assert.assertNull(legacy.getActiveTask().getPartyStyles());
		// normalized() backfills TOP-LEVEL collections only; it never descends
		// into activeTask, so the null must stay a null the readers tolerate
		Assert.assertNull(legacy.normalized().getActiveTask().getPartyStyles());
	}

	@Test
	public void partyProposalIdSurvivesTheCodec()
	{
		// the ONLY handle that can reunite a restarted client with its own contract:
		// member ids are drawn fresh every RuneLite session, so nothing else on the
		// wire still identifies this party after a restart
		GachaState state = GachaState.fresh(42).withActiveTask(ActiveTask.builder()
			.difficulty(TaskDifficulty.EASY)
			.monsterName("Goblin")
			.killsRequired(10)
			.partyLabel("Party of 3")
			.partyProposalId(-8_070_450_532_247_928_833L)
			.build());

		GachaState decoded = codec.decode(codec.encode(state));
		Assert.assertNotNull(decoded);
		Assert.assertEquals("a 64-bit id must not be rebuilt through a double",
			Long.valueOf(-8_070_450_532_247_928_833L),
			decoded.getActiveTask().getPartyProposalId());
	}

	@Test
	public void aProposalIdOfZeroIsNotMistakenForALegacyContract()
	{
		// exactly why the field is BOXED: 0 is a perfectly legal proposal id, and a
		// primitive would make a live party indistinguishable from a save written
		// before the field existed — which is settled by the carry clause on sight
		GachaState state = GachaState.fresh(42).withActiveTask(ActiveTask.builder()
			.monsterName("Goblin")
			.partyLabel("Party of 2")
			.partyProposalId(0L)
			.build());

		GachaState decoded = codec.decode(codec.encode(state));
		Assert.assertNotNull(decoded);
		Assert.assertEquals(Long.valueOf(0L), decoded.getActiveTask().getPartyProposalId());
	}

	@Test
	public void legacyPartyContractDecodesANullProposalId()
	{
		// a shared contract signed before the id was persisted. It cannot be
		// rejoined by anyone, so the null must survive intact for
		// PartyRollService.resurrectPartyContract to spot it and convert — and
		// normalized() must not invent a value, since it backfills null COLLECTIONS
		// only and never descends into activeTask
		GachaState legacy = new Gson().fromJson(
			"{\"gc\":5,\"activeTask\":{\"monsterName\":\"Goblin\",\"killsRequired\":10,"
				+ "\"partyLabel\":\"Party of 2\"}}", GachaState.class);
		Assert.assertTrue(legacy.getActiveTask().isParty());
		Assert.assertNull(legacy.getActiveTask().getPartyProposalId());
		Assert.assertNull(legacy.normalized().getActiveTask().getPartyProposalId());
	}

	@Test
	public void aSoloContractCarriesNoProposalId()
	{
		// the solo accept path signs with null, so a solo task can never be
		// resurrected into a party session by a stray id left on the builder
		GachaState state = GachaState.fresh(42).withActiveTask(ActiveTask.builder()
			.monsterName("Goblin")
			.killsRequired(10)
			.build());

		GachaState decoded = codec.decode(codec.encode(state));
		Assert.assertNotNull(decoded);
		Assert.assertFalse(decoded.getActiveTask().isParty());
		Assert.assertNull(decoded.getActiveTask().getPartyProposalId());
	}

	@Test
	public void unknownStyleNameInAPersistedTaskBecomesNull()
	{
		// Gson's enum adapter maps an unrecognised name to null rather than
		// throwing, so a save from a build with more styles still loads
		GachaState legacy = new Gson().fromJson(
			"{\"activeTask\":{\"monsterName\":\"Goblin\",\"partyLabel\":\"Party of 2\","
				+ "\"partyStyles\":[\"MELEE\",\"DRAGONFIRE\"]}}", GachaState.class);
		Assert.assertEquals(Arrays.asList(AttackStyle.MELEE, null),
			legacy.getActiveTask().getPartyStyles());
	}

	@Test
	public void pendingPartyOffersRoundTrip()
	{
		// The whole party-deadlock defect rests on this asymmetry: the party FLAG
		// survives the save blob while the vote session that gives it meaning is
		// transient. Pin the persisted half so nobody "fixes" the orphan by
		// assuming the flag decays on its own.
		GachaState state = GachaState.fresh(42).withPendingOffers(Arrays.asList(
			new TaskOffer(TaskDifficulty.EASY, "Goblin", 2, 15, 7, 400,
				new ArrayList<>(), false, true),
			new TaskOffer(TaskDifficulty.HARD, "Blue dragon", 111, 25, 30, 3200,
				new ArrayList<>(), true, true)));

		GachaState decoded = codec.decode(codec.encode(state));
		Assert.assertNotNull(decoded);
		Assert.assertEquals(2, decoded.getPendingOffers().size());
		Assert.assertTrue(decoded.getPendingOffers().get(0).isPartyRoll());
		Assert.assertTrue(decoded.getPendingOffers().get(1).isPartyRoll());
		Assert.assertEquals("Blue dragon", decoded.getPendingOffers().get(1).getMonsterName());
	}

	@Test
	public void demotedOffersRoundTripAsPersonal()
	{
		// the recovery sweep's result has to be durable, or the board re-orphans
		// itself on the next login
		GachaState state = GachaState.fresh(42).withPendingOffers(Arrays.asList(
			new TaskOffer(TaskDifficulty.EASY, "Goblin", 2, 15, 7, 400,
				new ArrayList<>(), false, false)));

		GachaState decoded = codec.decode(codec.encode(state));
		Assert.assertNotNull(decoded);
		Assert.assertFalse(decoded.getPendingOffers().get(0).isPartyRoll());
		Assert.assertEquals(15, decoded.getPendingOffers().get(0).getKillsRequired());
	}

	@Test
	public void legacyOfferWithoutPartyFlagLoadsPersonal()
	{
		// a save from before the flag existed: Gson leaves the absent key false,
		// which is the safe side — an old offer must never demand a vote session
		GachaState legacy = new Gson().fromJson(
			"{\"pendingOffers\":[{\"monsterName\":\"Goblin\",\"killsRequired\":15}]}",
			GachaState.class);
		Assert.assertFalse(legacy.getPendingOffers().get(0).isPartyRoll());
		// normalized() only backfills NULL collections; it never inspects offers
		Assert.assertFalse(legacy.normalized().getPendingOffers().get(0).isPartyRoll());
	}

	@Test
	public void tamperedPayloadRefusesToLoad()
	{
		GachaState state = GachaState.fresh(3);
		String blob = codec.encode(state);
		// flip a chunk of the base64 body
		char[] chars = blob.toCharArray();
		int mid = chars.length / 2;
		chars[mid] = chars[mid] == 'A' ? 'B' : 'A';
		GachaState decoded = codec.decode(new String(chars));
		Assert.assertNull(decoded);
	}

	@Test
	public void sha256IsStable()
	{
		Assert.assertEquals(StateCodec.sha256("gachaman"), StateCodec.sha256("gachaman"));
		Assert.assertNotEquals(StateCodec.sha256("a"), StateCodec.sha256("b"));
	}

	@Test
	public void garbageReturnsNullNotThrow()
	{
		Assert.assertNull(codec.decode(null));
		Assert.assertNull(codec.decode(""));
		Assert.assertNull(codec.decode("not-base64!!"));
	}
}
