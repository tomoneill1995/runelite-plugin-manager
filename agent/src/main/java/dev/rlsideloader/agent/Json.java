package dev.rlsideloader.agent;

import java.util.List;

/**
 * Minimal JSON string encoding, to avoid depending on the client's copy of Gson.
 */
final class Json
{
	private Json()
	{
	}

	static String str(String s)
	{
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray())
		{
			switch (c)
			{
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (c < 0x20)
					{
						sb.append(String.format("\\u%04x", (int) c));
					}
					else
					{
						sb.append(c);
					}
			}
		}
		return sb.append('"').toString();
	}

	static String strList(List<String> values)
	{
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < values.size(); i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append(str(values.get(i)));
		}
		return sb.append(']').toString();
	}
}
