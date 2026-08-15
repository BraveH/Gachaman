package com.gachaman.service;

import com.gachaman.data.*;
import com.gachaman.model.*;
import com.google.gson.*;
import java.util.*;
import javax.annotation.*;
import javax.inject.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.gameval.*;

/**
 * The weapon CATEGORY taxonomy: what the wheel may name alongside a style, and
 * whether the weapon in hand at a kill satisfies it.
 *
 * <p>Category level only, deliberately. The varbit reports a category and
 * nothing finer, so a scimitar and a longsword are the same thing to this
 * service and always will be — the wheel cannot tell them apart, so it must
 * never claim to.
 *
 * <h2>The resolution chain</h2>
 *
 * <pre>
 *   VarbitID.COMBAT_WEAPON_CATEGORY (an int, 6 bits, 0..63)
 *     -&gt; the client's own DB table 78, category int -&gt; DBROW ID
 *     -&gt; weapon-types.json, keyed on that dbrow -&gt; a {@link WeaponType}
 * </pre>
 *
 * <p><b>Keyed on dbrow ids, never on the raw varbit int.</b> The legacy
 * WeaponType int table that other plugins copy around is stale from 22 onward,
 * so anything built on it quietly names the wrong category for every weapon
 * added since. The dbrow ids are Jagex's own and are spelled as
 * {@code DBTableID.CombatInterfaceWeaponCategory.Row} constants in
 * {@code com.gachaman.tools.WeaponTypes}, where the compiler still checks every
 * name against the live API — the same arrangement {@code AttackAnims} uses for
 * the animation ids, and for the same reason: numbers in a resource are
 * unchecked, names in code are not.
 *
 * <h2>The safety property</h2>
 *
 * <p>An unrecognised category earns NO BONUS, and that is the whole design.
 * There is no default arm, no array index, no clamp into range, and no remap of
 * an unknown id onto a similar one. Every step of the chain returns null on a
 * miss and the miss propagates: a player holding a category this build has
 * never heard of simply does not get the multiplier that kill. The bonus is
 * additive to the player's fortunes and never subtractive, so failing to pay it
 * is a non-event, while paying it for the wrong weapon would make the
 * preference meaningless.
 *
 * <h2>Which thread</h2>
 *
 * <p>{@link #byCategory(int)} and {@link #satisfies(String, int, int)} touch the
 * client to read the category table, so call them from the CLIENT thread — the
 * kill path already is one. {@link #byKey}, {@link #displayName}, {@link #pool}
 * and {@link #roll} touch nothing but the loaded resource and are safe from
 * anywhere, which covers everything a panel needs; a panel that genuinely wants
 * to name the weapon in hand should read
 * {@code VarbitID.COMBAT_WEAPON_CATEGORY} on the client thread and bring the int
 * here. There is deliberately no convenience method that reads it for you: the
 * bonus is per-kill precisely so it cannot be decided by a late read of whatever
 * happens to be equipped, and a getter named for the present tense is an
 * invitation to make exactly that mistake.
 */
@Slf4j
@Singleton
public class WeaponTypeService {
	/**
	 * The one pseudo-type: casting from the autocast slot. It is not a weapon
	 * category and has no dbrow — it is {@code COM_MODE == 4}, the same signal
	 * {@link StyleTracker#resolve} reads to judge an attack MAGIC regardless of
	 * what is in hand. Modelled as a type anyway so magic's pool is not just the
	 * powered staves, and so a caller asking "does this kill satisfy the
	 * preference" gets one answer from one place.
	 */
	public static final String SPELL_CAST_KEY = "spell_cast";

	/** The dbrow of a type that has none. Real rows start in the 3900s. */
	public static final int NO_DBROW = -1;

	/**
	 * The autocast slot. Powered staves do NOT use it (they fight from com mode
	 * 0/1/3), which is exactly why they are their own category and this is its
	 * own pseudo-type.
	 */
	private static final int AUTOCAST = 4;

	/**
	 * One weapon category.
	 *
	 * <p>{@code offerIn} is the set of styles whose wheel may name this category
	 * — the hybrid staves are in MELEE <b>and</b> MAGIC on purpose, and that is
	 * what rescues magic from a two-item pool. Which mode such a staff is
	 * actually being used in is never resolved and never needs to be: the bonus
	 * pays only on a COMPLIANT kill, and the style lock already guarantees
	 * compliance, because a kill landed in the wrong style is tainted and pays
	 * zero before any multiplier is reached.
	 *
	 * <p>{@code offerable} false means the wheel may never name it, with the
	 * reason recorded in {@code reason} — a category can be excluded for being
	 * unwinnable, ambiguous, or empty of live items, and which one it was is
	 * worth keeping next to the exclusion.
	 */
	@Value
	public static class WeaponType {
		/** Stable internal id, persisted in {@code GachaState.preferredWeaponType}. */
		String key;
		/**
		 * The player-facing name. <b>Render this, never the key</b> — the key for
		 * category 0 is "unarmed", and the owner's rule is that player-facing
		 * text says "no weapon equipped", because the game reports that same
		 * category for every non-weapon held item and calling it "unarmed" would
		 * be a lie about what the player is holding.
		 */
		String displayName;
		/** {@code DBTableID.CombatInterfaceWeaponCategory.Row} id, or {@link #NO_DBROW}. */
		int dbrow;
		Set<AttackStyle> offerIn;
		boolean offerable;
		/** Why the wheel will not name it; null when it will. */
		String reason;
	}

	/**
	 * The shape of weapon-types.json. The file also carries a {@code note} key
	 * explaining how it is authored — deliberately not declared here, since Gson
	 * ignores keys it has no field for, and a resource nobody can leave a
	 * comment in should at least be allowed to carry one.
	 */
	private static class TypesFile {
		List<WeaponType> types;
	}

	private final Client client;
	private final GachaRng rng;
	private final List<WeaponType> types;
	/**
	 * The same types indexed two ways. Short names on purpose: every character
	 * of a private member is charged against the Plugin Hub's token budget at
	 * each use, and the budget is this repo's binding constraint — the type of
	 * each map already says what it is keyed on, and the javadoc says the rest.
	 *
	 * <p>{@code byRow} holds only the entries with a real dbrow, so the
	 * SPELL_CAST pseudo-type is absent from it by construction rather than by a
	 * check at lookup time. {@code keyed} holds every entry, pseudo-type
	 * included, because a persisted preference names it by key.
	 */
	private final Map<Integer, WeaponType> byRow;
	private final Map<String, WeaponType> keyed;

	/**
	 * @param rng the INJECTED singleton, never a seeded instance. The wheel's
	 *            weapon roll must not draw from the party's seeded RNG: the
	 *            party path builds its own {@code new GachaRng(seed)} and passes
	 *            it down as an argument so every client deals the same board
	 *            from one seed, and an extra draw taken from that stream by one
	 *            client and not another shifts every subsequent draw and deals
	 *            two different boards. Taking the RNG by constructor injection
	 *            rather than as a method parameter is what makes that mistake
	 *            impossible rather than merely discouraged — the seeded instance
	 *            has no route in here.
	 */
	@Inject
	WeaponTypeService(Client client, Gson gson, GachaRng rng) {
		this.client = client;
		this.rng = rng;
		TypesFile file = DataJson.load(gson, "weapon-types", TypesFile.class, new TypesFile());
		List<WeaponType> loaded = new ArrayList<>();
		Map<Integer, WeaponType> rows = new HashMap<>();
		Map<String, WeaponType> keys = new HashMap<>();
		for (WeaponType raw : file.types == null ? Collections.<WeaponType>emptyList() : file.types) {
			if (raw == null || raw.key == null)
				continue;
			// Every row is REBUILT rather than used as Gson handed it over, with
			// a non-null, immutable, null-free style set. Gson writes the final
			// fields directly, so an absent offerIn arrives as null and an
			// unrecognised style name arrives as a null ELEMENT — and the null
			// element is the nastier of the two, because contains() on a set
			// holding one still works while every enumeration over it trips.
			//
			// This was a normalised() helper with exactly one caller until the
			// token budget got tight; its signature and call site were the only
			// thing the separation bought. The argument order below is
			// load-bearing and must stay as it is: com.gachaman.tools.WeaponTypes
			// calls the same @Value all-args constructor to author the resource,
			// and WeaponTypeResourceTest compares the two by equals().
			Set<AttackStyle> styles = EnumSet.noneOf(AttackStyle.class);
			if (raw.offerIn != null) {
				for (AttackStyle style : raw.offerIn) {
					if (style != null) {
						styles.add(style);
					}
				}
			}
			WeaponType type = new WeaponType(raw.key, raw.displayName, raw.dbrow,
				Collections.unmodifiableSet(styles), raw.offerable, raw.reason);
			loaded.add(type);
			keys.put(type.key, type);
			if (type.dbrow != NO_DBROW) {
				rows.put(type.dbrow, type);
			}
		}
		this.types = Collections.unmodifiableList(loaded);
		this.byRow = Collections.unmodifiableMap(rows);
		this.keyed = Collections.unmodifiableMap(keys);
	}

	/**
	 * Category int -&gt; dbrow id, read once from the client's own DB table.
	 * Null until a COMPLETE read succeeds, which is the point: see
	 * {@link #categoryMap()}.
	 *
	 * <p>Volatile because it is published from whichever thread happens to build
	 * it first and read from the other. Today the only builder is the kill path
	 * on the client thread, but the guard is kept rather than relaxed: this field
	 * previously also backed a {@code currentForDisplay()} convenience that was
	 * dropped precisely because DBTable access from the Swing EDT is unverified,
	 * and anything that reintroduces an EDT reader must not have to rediscover
	 * why the publication needs to be safe. The race itself is
	 * benign — both would build the same map and one would win — but publishing a
	 * HashMap through a non-volatile field can hand the other thread a
	 * half-constructed one, and this is the same guard {@code CardDatabase} puts
	 * on the maps it builds once and reads everywhere.
	 */
	private volatile Map<Integer, Integer> cache;

	/** So an incomplete table is reported once, not once per kill. */
	private boolean warned;

	/**
	 * The client's category-to-dbrow table, or an empty map while it cannot be
	 * read at all.
	 *
	 * <p>The read is CACHED only when every row yielded a category int, and USED
	 * either way. Both halves of that matter, and they guard opposite failures:
	 *
	 * <ul>
	 * <li>Caching a partial read would freeze it. At login the cache is still
	 * loading and a row's field can come back null; a partial map cached there
	 * would leave those categories unresolvable for the rest of the session, and
	 * the player would see the bonus pay for some weapons and silently not for
	 * others with nothing to explain it.
	 * <li>DISCARDING a partial read would be worse, because it is total rather
	 * than partial. If some row in table 78 legitimately carries no value in this
	 * column, an exact-match requirement would never be met, the map would never
	 * cache, and the bonus would never pay for anybody — one debug line the only
	 * trace. Nothing here can verify that every row carries one, so this does not
	 * bet the feature on it.
	 * </ul>
	 *
	 * <p>So an incomplete read is returned unmemoised: a transient one heals on
	 * the next call and then caches for good, and a permanent one costs a walk of
	 * three dozen rows per lookup and still pays out for every category that does
	 * resolve. Warned at WARN and once only — debug is off for the player who
	 * would be reporting "the weapon bonus never pays", and a per-kill warning
	 * would drown their log instead of helping it.
	 */
	private Map<Integer, Integer> categoryMap() {
		// Read twice rather than into a local, which is safe here only because the
		// field is MONOTONIC: it is written exactly once, from null to a complete
		// unmodifiable map, and the write happens only on the path where the read
		// above saw null. So a non-null first read cannot be followed by a null
		// second one. Two threads racing can each write, but they write maps of
		// identical content, so which one a reader lands on is not observable.
		// Do not copy this shortening to a field that can ever be reset to null.
		if (cache != null)
			return cache;
		if (client == null)
			return Collections.emptyMap();
		try {
			List<Integer> rows = client.getDBTableRows(DBTableID.CombatInterfaceWeaponCategory.ID);
			if (rows == null || rows.isEmpty()) {
				return Collections.emptyMap(); // cache not loaded yet — normal, try again later
			}
			Map<Integer, Integer> map = new HashMap<>();
			for (Integer row : rows) {
				if (row == null)
					continue;
				Object[] field = client.getDBTableField(row,
					DBTableID.CombatInterfaceWeaponCategory.COL_ID, 0);
				if (field == null || field.length == 0 || !(field[0] instanceof Number))
					continue;
				map.put(((Number) field[0]).intValue(), row);
			}
			if (map.size() != rows.size()) {
				if (!warned) {
					warned = true;
					log.warn("weapon category table incomplete ({} of {} rows readable) —"
						+ " using it without caching; the preferred weapon bonus will not"
						+ " pay for the categories it is missing", map.size(), rows.size());
				}
				return Collections.unmodifiableMap(map);
			}
			cache = Collections.unmodifiableMap(map);
			return cache;
		}
		catch (Exception e) {
			log.debug("weapon category table unreadable", e);
			return Collections.emptyMap();
		}
	}

	/** Every type in the taxonomy, offerable or not, in resource order. */
	public List<WeaponType> all() {
		return types;
	}

	/**
	 * The type a raw {@code COMBAT_WEAPON_CATEGORY} value names, or null when
	 * this build cannot name it — an unmapped int, an unknown dbrow, or a
	 * category table that is not readable yet. Null means no bonus, always.
	 */
	@Nullable
	public WeaponType byCategory(int cat) {
		return byCategory(categoryMap(), cat);
	}

	/**
	 * Pure form, taking the table as an argument.
	 *
	 * <p>Split out for the same reason {@code StyleTracker.resolve} is: the
	 * service around it needs a live Client to read the table at all, so the
	 * behaviour that actually matters — that an unrecognised category resolves
	 * to NOTHING — could not otherwise be tested at all.
	 *
	 * @param table the category-int to dbrow mapping, normally {@link #categoryMap()}
	 * @param cat   a raw {@code COMBAT_WEAPON_CATEGORY} value
	 */
	@Nullable
	WeaponType byCategory(Map<Integer, Integer> table, int cat) {
		Integer dbrow = table.get(cat);
		return dbrow == null ? null : byRow.get(dbrow);
	}

	/**
	 * The type a persisted key names, or null when this build no longer knows it
	 * — a preference saved before the taxonomy changed resolves to nothing,
	 * which is no bonus rather than a wrong one.
	 */
	@Nullable
	public WeaponType byKey(String key) {
		return key == null ? null : keyed.get(key);
	}

	/**
	 * The player-facing name for a persisted key, or null when it no longer
	 * resolves (render nothing rather than a raw key). Always the route to text:
	 * see {@link WeaponType#getDisplayName()} for why the key must never be
	 * shown.
	 */
	@Nullable
	public String displayName(String key) {
		WeaponType type = byKey(key);
		return type == null ? null : type.displayName;
	}

	/**
	 * Does a kill landed with this weapon satisfy the preference?
	 *
	 * <p>The caller passes the category and com mode SAMPLED AT THE KILLING
	 * BLOW, never read fresh here — the bonus is per-kill precisely so it cannot
	 * be earned by swapping the preferred weapon in after the fight.
	 *
	 * @param pref {@code GachaState.preferredWeaponType}; null (no preference
	 *             live) is false, never an error
	 * @param cat  the raw {@code COMBAT_WEAPON_CATEGORY} at the killing blow
	 * @param mode the com mode at the killing blow; 4 is the autocast slot
	 */
	public boolean satisfies(String pref, int cat, int mode) {
		return satisfies(categoryMap(), pref, cat, mode);
	}

	/** Pure form, taking the table as an argument. See {@link #byCategory(Map, int)}. */
	boolean satisfies(Map<Integer, Integer> table, String pref, int cat, int mode) {
		WeaponType preferred = byKey(pref);
		if (preferred == null)
			return false;
		// Both comparisons below read pref where they once read preferred.key.
		// That is the same string, not a shortcut: keyed is populated as
		// put(type.key, type), so a hit means the stored key equalled the lookup
		// key. Compared by equals() rather than by == on the two WeaponType
		// objects on purpose — reference identity would hold only because a test
		// pins keys unique, and that is a behavioural dependency this file should
		// not be leaning on from the outside.
		if (SPELL_CAST_KEY.equals(pref)) {
			// the pseudo-type: the autocast slot and nothing else. A powered
			// staff casting from com mode 0/1/3 is NOT this — it is its own
			// category, and has its own entry to be named by
			return mode == AUTOCAST;
		}
		WeaponType inHand = byCategory(table, cat);
		return inHand != null && inHand.key.equals(pref);
	}

	/**
	 * The categories this style's wheel may name, in resource order.
	 *
	 * <p>Order is the resource's, so a seeded roll is reproducible from the
	 * shipped file alone.
	 */
	public List<WeaponType> pool(AttackStyle style) {
		List<WeaponType> pool = new ArrayList<>();
		for (WeaponType type : types) {
			if (type.offerable && type.offerIn.contains(style)) {
				pool.add(type);
			}
		}
		return Collections.unmodifiableList(pool);
	}

	/**
	 * Name a preferred category for this style, uniformly from its pool. Null
	 * only if the pool is empty, which means the resource failed to load — and
	 * no preference is the correct degradation, since the bonus is a bonus.
	 */
	@Nullable
	public WeaponType roll(AttackStyle style) {
		List<WeaponType> pool = pool(style);
		return pool.isEmpty() ? null : rng.pick(pool);
	}
}
