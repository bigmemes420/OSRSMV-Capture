package com.osmm.capture;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.runelite.client.util.Text;

/**
 * "Anonymise player names", for one recording (and its lead-in): each player's name becomes a code, "Player " and six
 * upper-case hex digits from HMAC-SHA256 of the name under the key ({@link NameKey}), so the same player always gets
 * the same code and the name can't be read back from it without the key. Names are compared as RuneLite compares
 * them: tags removed, case ignored, and spaces, non-breaking spaces, underscores and hyphens the same.
 *
 * <p>If two different names in one recording come to the same six digits, the later one gets eight (or more), so a
 * code never stands for two players in a file. Game messages have the names in them replaced too ({@link #message}).
 *
 * <p>{@link #off()} leaves everything as it is. Client thread only (one Mac, not thread-safe).
 */
final class PlayerNames
{
	static final String PREFIX = "Player ";
	static final int DIGITS = 6;
	private static final String ALGORITHM = "HmacSHA256";
	private static final Pattern TAG = Pattern.compile("<[^>]*>");   // as Text.removeTags
	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	private static final PlayerNames OFF = new PlayerNames();

	private final Mac mac;                                         // null when off
	private final Map<String, String> codes = new HashMap<>();     // name as compared -> code
	private final Map<String, String> names = new HashMap<>();     // code -> name as compared

	private PlayerNames()
	{
		mac = null;
	}

	PlayerNames(byte[] key)
	{
		try
		{
			mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(key, ALGORITHM));
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException(e);   // (every Java has HmacSHA256)
		}
	}

	/** Names as they are: the option is off. */
	static PlayerNames off()
	{
		return OFF;
	}

	boolean enabled()
	{
		return mac != null;
	}

	/** A player's name as it goes in the file: its code, or (off, or no name) the name as it is. */
	String player(String name)
	{
		if (mac == null || name == null)
		{
			return name;
		}
		String key = normalise(name);
		return key.isEmpty() ? name : codeOf(key);
	}

	/**
	 * A game message as it goes in the file: each player's name in it replaced by its code. The names looked for are
	 * those coded so far in this recording and others (the players in the scene, yours included). A name counts only
	 * as a whole word (not inside a longer word or a tag), whatever its case and with spaces, non-breaking spaces,
	 * underscores or hyphens between its words; the longer of two names that overlap wins. Tags around a name (a
	 * colour, an icon) are kept.
	 */
	String message(String text, Iterable<String> others)
	{
		if (mac == null || text == null || text.isEmpty())
		{
			return text;
		}
		// the text folded as names are (one char for one, so places match), with tags blanked out
		char[] folded = new char[text.length()];
		for (int i = 0; i < folded.length; i++)
		{
			folded[i] = fold(text.charAt(i));
		}
		Matcher m = TAG.matcher(text);
		while (m.find())
		{
			for (int i = m.start(); i < m.end(); i++)
			{
				folded[i] = '\0';
			}
		}
		String haystack = new String(folded);

		Set<String> wanted = new LinkedHashSet<>(codes.keySet());
		if (others != null)
		{
			for (String o : others)
			{
				if (o != null)
				{
					String k = normalise(o);
					if (!k.isEmpty())
					{
						wanted.add(k);
					}
				}
			}
		}
		List<String> byLength = new ArrayList<>(wanted);
		byLength.sort((a, b) -> b.length() - a.length());

		String[] at = new String[folded.length];   // the name found starting at each place
		boolean[] taken = new boolean[folded.length];
		boolean any = false;
		for (String name : byLength)
		{
			for (int i = haystack.indexOf(name); i >= 0; i = haystack.indexOf(name, i + 1))
			{
				int end = i + name.length();
				if (!wordEdge(folded, i - 1) || !wordEdge(folded, end) || anyTaken(taken, i, end))
				{
					continue;
				}
				for (int j = i; j < end; j++)
				{
					taken[j] = true;
				}
				at[i] = name;
				any = true;
			}
		}
		if (!any)
		{
			return text;
		}
		StringBuilder out = new StringBuilder(text.length() + 16);
		for (int i = 0; i < folded.length; )
		{
			if (at[i] != null)
			{
				out.append(codeOf(at[i]));
				i += at[i].length();
			}
			else
			{
				out.append(text.charAt(i++));
			}
		}
		return out.toString();
	}

	/** A name as names are compared: tags removed, lower case, spaces/non-breaking spaces/underscores/hyphens as spaces. */
	static String normalise(String name)
	{
		String s = Text.removeTags(name);
		StringBuilder b = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++)
		{
			b.append(fold(s.charAt(i)));
		}
		return b.toString().trim();
	}

	private static char fold(char c)
	{
		return c == '\u00A0' || c == '_' || c == '-' ? ' ' : Character.toLowerCase(c);
	}

	/** The edge of a word: outside the text, or not a letter or digit (a space, punctuation, a blanked tag). */
	private static boolean wordEdge(char[] folded, int i)
	{
		return i < 0 || i >= folded.length || !Character.isLetterOrDigit(folded[i]);
	}

	private static boolean anyTaken(boolean[] taken, int from, int to)
	{
		for (int i = from; i < to; i++)
		{
			if (taken[i])
			{
				return true;
			}
		}
		return false;
	}

	private String codeOf(String key)
	{
		String code = codes.get(key);
		if (code != null)
		{
			return code;
		}
		byte[] h = mac.doFinal(key.getBytes(StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder(h.length * 2);
		for (byte b : h)
		{
			hex.append(HEX[(b >> 4) & 15]).append(HEX[b & 15]);
		}
		// six digits, or more for a name whose six (or eight...) another name in this recording already has
		for (int n = DIGITS; n <= hex.length() && code == null; n += 2)
		{
			String c = PREFIX + hex.substring(0, n);
			if (!names.containsKey(c))
			{
				code = c;
			}
		}
		if (code == null)
		{
			code = PREFIX + hex + "-" + codes.size();   // (never: the whole 256 bits the same)
		}
		codes.put(key, code);
		names.put(code, key);
		return code;
	}
}
