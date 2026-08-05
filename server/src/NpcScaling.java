import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class NpcScaling
{
    public static final int SCALE_UNIT = 1000;

    private static final boolean DEFAULT_ENABLED = true;
    private static final double DEFAULT_PER_PLAYER = 0.5;
    private static final double DEFAULT_MAX_MULTIPLIER = 8.0;

    private static volatile boolean enabled = DEFAULT_ENABLED;
    private static volatile double healthPerExtraPlayer = DEFAULT_PER_PLAYER;
    private static volatile double maxMultiplier = DEFAULT_MAX_MULTIPLIER;

    private NpcScaling()
    {
    }

    public static boolean isEnabled()
    {
        return enabled;
    }

    public static double perExtraPlayer()
    {
        return healthPerExtraPlayer;
    }

    public static double maxMultiplier()
    {
        return maxMultiplier;
    }

    public static int scaleMilliFor(int playerCount)
    {
        return scaleMilliFor(
                playerCount,
                (int) Math.round(healthPerExtraPlayer * SCALE_UNIT),
                (int) Math.round(maxMultiplier * SCALE_UNIT));
    }

    public static int scaleMilliFor(int playerCount, int stepMilli, int maxMilli)
    {
        if (!enabled || playerCount <= 1)
        {
            return SCALE_UNIT;
        }

        long scale = SCALE_UNIT + (long) Math.max(0, stepMilli) * (playerCount - 1L);
        scale = Math.min(scale, Math.max(SCALE_UNIT, maxMilli));
        scale = Math.max(SCALE_UNIT, scale);
        return (int) Math.min(Integer.MAX_VALUE, scale);
    }

    public static void setEnabled(boolean value)
    {
        enabled = value;
        save();
    }

    public static void setPerExtraPlayer(double value)
    {
        healthPerExtraPlayer = clamp(value, 0.0, 100.0);
        save();
    }

    public static void setMaxMultiplier(double value)
    {
        maxMultiplier = clamp(value, 1.0, 100.0);
        save();
    }

    private static double clamp(double value, double low, double high)
    {
        if (value < low)
        {
            return low;
        }

        if (value > high)
        {
            return high;
        }

        return value;
    }

    public static String describe()
    {
        return String.format("enabled=%s healthPerExtraPlayer=%.2f maxMultiplier=%.2f",
                enabled ? "true" : "false",
                healthPerExtraPlayer,
                maxMultiplier);
    }

    public static String preview(int playerCount)
    {
        return String.format("%d player(s) -> x%.2f",
                playerCount,
                scaleMilliFor(playerCount) / (double) SCALE_UNIT);
    }

    public static void load()
    {
        Path path = WitcherServer.npcScalingPath();

        if (!Files.exists(path))
        {
            save();
            WitcherServer.dbg("Created %s with default NPC health scaling (%s)\n", path, describe());
            return;
        }

        try
        {
            String content = Files.readString(path, StandardCharsets.UTF_8);

            Boolean parsedEnabled = readBoolean(content, "enabled");
            Double parsedPerPlayer = readDouble(content, "healthPerExtraPlayer");
            Double parsedMax = readDouble(content, "maxMultiplier");

            enabled = parsedEnabled == null ? DEFAULT_ENABLED : parsedEnabled;
            healthPerExtraPlayer = parsedPerPlayer == null
                    ? DEFAULT_PER_PLAYER
                    : clamp(parsedPerPlayer, 0.0, 100.0);
            maxMultiplier = parsedMax == null
                    ? DEFAULT_MAX_MULTIPLIER
                    : clamp(parsedMax, 1.0, 100.0);

            WitcherServer.dbg("Loaded NPC health scaling from %s (%s)\n", path, describe());
        }
        catch (IOException e)
        {
            WitcherServer.dbg("Failed to read %s: %s\n", path, e.getMessage());
        }
    }

    public static void save()
    {
        Path path = WitcherServer.npcScalingPath();

        try
        {
            Files.createDirectories(path.getParent());

            String json = "{\n"
                    + "  \"enabled\": " + (enabled ? "true" : "false") + ",\n"
                    + "  \"healthPerExtraPlayer\": " + trimNumber(healthPerExtraPlayer) + ",\n"
                    + "  \"maxMultiplier\": " + trimNumber(maxMultiplier) + "\n"
                    + "}\n";

            Files.writeString(path, json, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            WitcherServer.dbg("Failed to write NPC scaling config to %s: %s\n", path, e.getMessage());
        }
    }

    private static String trimNumber(double value)
    {
        String text = String.format("%.4f", value);

        while (text.contains(".") && (text.endsWith("0") || text.endsWith(".")))
        {
            boolean wasDot = text.endsWith(".");
            text = text.substring(0, text.length() - 1);

            if (wasDot)
            {
                break;
            }
        }

        return text.isEmpty() ? "0" : text;
    }

    private static String rawValue(String content, String key)
    {
        int at = content.indexOf("\"" + key + "\"");

        if (at < 0)
        {
            return null;
        }

        int colon = content.indexOf(':', at);

        if (colon < 0)
        {
            return null;
        }

        int end = colon + 1;

        while (end < content.length() && content.charAt(end) != ',' && content.charAt(end) != '}')
        {
            end++;
        }

        return content.substring(colon + 1, end).trim();
    }

    private static Boolean readBoolean(String content, String key)
    {
        String raw = rawValue(content, key);

        if (raw == null)
        {
            return null;
        }

        if (raw.equalsIgnoreCase("true") || raw.equals("1"))
        {
            return Boolean.TRUE;
        }

        if (raw.equalsIgnoreCase("false") || raw.equals("0"))
        {
            return Boolean.FALSE;
        }

        return null;
    }

    private static Double readDouble(String content, String key)
    {
        String raw = rawValue(content, key);

        if (raw == null || raw.isEmpty())
        {
            return null;
        }

        try
        {
            return Double.parseDouble(raw);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
