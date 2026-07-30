import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BotManager
{
    private static final int BOT_ID_BASE = 900000;
    private static final double WANDER_RADIUS = 25.0;
    private static final double SPEED_PER_TICK = 0.175;
    private static final double ARRIVE_DISTANCE = 0.6;
    private static final long RETARGET_NANOS = 4_000_000_000L;

    private static final class Bot
    {
        final PlayerSession session;
        double offsetX;
        double offsetY;
        double targetOffsetX;
        double targetOffsetY;
        double heading;
        long retargetAt;

        Bot(PlayerSession session, double offsetX, double offsetY)
        {
            this.session = session;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.targetOffsetX = offsetX;
            this.targetOffsetY = offsetY;
        }
    }

    private static void pickWanderTarget(Bot bot, long now)
    {
        final double angle = Math.random() * Math.PI * 2.0;
        final double distance = 3.0 + (Math.random() * (WANDER_RADIUS - 3.0));

        bot.targetOffsetX = Math.cos(angle) * distance;
        bot.targetOffsetY = Math.sin(angle) * distance;
        bot.retargetAt = now + RETARGET_NANOS + (long) (Math.random() * 3_000_000_000L);
    }

    private static final List<Bot> bots = new ArrayList<>();
    private static volatile String anchorUsernameKey = null;
    private static int movementSequence = 1000;
    private static int updateSequence = 1000;

    private BotManager()
    {
    }

    public static synchronized int count()
    {
        return bots.size();
    }

    public static synchronized void clear()
    {
        bots.clear();
        anchorUsernameKey = null;
    }

    public static synchronized String spawn(PlayerSession anchor, int requested)
    {
        bots.clear();

        if (anchor == null)
        {
            return "no player connected to anchor bots to";
        }

        if (!anchor.hasPosition)
        {
            return "anchor player has no position yet; move in game and retry";
        }

        anchorUsernameKey = anchor.username;

        for (int i = 0; i < requested; i++)
        {
            String name = "Bot" + (i + 1);
            PlayerSession session = new PlayerSession(
                    BOT_ID_BASE + i,
                    name,
                    anchor.endpoint,
                    System.nanoTime());

            double spread = (Math.PI * 2.0 * i) / Math.max(1, requested);
            bots.add(new Bot(session, Math.cos(spread) * 15.0, Math.sin(spread) * 15.0));
        }

        return "spawned " + bots.size() + " bots orbiting " + anchor.username;
    }

    public static synchronized List<PlayerSession> sessions()
    {
        List<PlayerSession> out = new ArrayList<>();

        for (Bot bot : bots)
        {
            out.add(bot.session);
        }

        return out;
    }

    public static synchronized void tick(PlayerSession anchor, long now)
    {
        if (bots.isEmpty() || anchor == null || !anchor.hasPosition)
        {
            return;
        }

        List<String> templateA = anchor.update1A.fields;
        List<String> templateB = anchor.update1B.fields;

        if (templateA == null || templateA.size() < 9)
        {
            return;
        }

        movementSequence++;
        updateSequence++;

        for (Bot bot : bots)
        {
            if (now >= bot.retargetAt)
            {
                pickWanderTarget(bot, now);
            }

            double dx = bot.targetOffsetX - bot.offsetX;
            double dy = bot.targetOffsetY - bot.offsetY;
            double distance = Math.sqrt((dx * dx) + (dy * dy));

            if (distance <= ARRIVE_DISTANCE)
            {
                pickWanderTarget(bot, now);
            }
            else
            {
                bot.offsetX += (dx / distance) * SPEED_PER_TICK;
                bot.offsetY += (dy / distance) * SPEED_PER_TICK;
                bot.heading = Math.toDegrees(Math.atan2(dy, dx));
            }

            final double leash = Math.sqrt((bot.offsetX * bot.offsetX) + (bot.offsetY * bot.offsetY));

            if (leash > WANDER_RADIUS)
            {
                bot.offsetX = (bot.offsetX / leash) * WANDER_RADIUS;
                bot.offsetY = (bot.offsetY / leash) * WANDER_RADIUS;
            }

            final double x = anchor.posX + bot.offsetX;
            final double y = anchor.posY + bot.offsetY;
            final double z = anchor.posZ;
            final double heading = bot.heading;

            bot.session.endpoint = anchor.endpoint;
            bot.session.lastSeen = now;
            bot.session.storePosition(x, y, z, anchor.area);

            bot.session.update1A.store(rewrite(templateA, x, y, z, heading, anchor.area));

            if (templateB != null && templateB.size() >= 2)
            {
                bot.session.update1B.store(rewriteSequenceOnly(templateB));
            }

            if (anchor.update2A.fields != null && !anchor.update2A.fields.isEmpty())
            {
                bot.session.update2A.store(anchor.update2A.fields);
            }

            if (anchor.update2B.fields != null && !anchor.update2B.fields.isEmpty())
            {
                bot.session.update2B.store(anchor.update2B.fields);
            }
        }
    }

    public static synchronized List<String> movementFields(PlayerSession botSession)
    {
        List<String> fields = new ArrayList<>();

        fields.add(Integer.toString(movementSequence));
        fields.add(format(botSession.posX));
        fields.add(format(botSession.posY));
        fields.add(format(botSession.posZ));
        fields.add("0.000");
        fields.add(format(headingOf(botSession)));
        fields.add("1.000");
        fields.add(Integer.toString(botSession.area));

        return fields;
    }

    private static double headingOf(PlayerSession session)
    {
        List<String> fields = session.update1A.fields;

        if (fields != null && fields.size() > 6)
        {
            Double parsed = parse(fields.get(6));

            if (parsed != null)
            {
                return parsed;
            }
        }

        return 0.0;
    }

    private static List<String> rewrite(List<String> template, double x, double y, double z, double heading, int area)
    {
        List<String> copy = new ArrayList<>(template);

        set(copy, 0, Integer.toString(updateSequence));
        set(copy, 1, Integer.toString(movementSequence));
        set(copy, 2, format(x));
        set(copy, 3, format(y));
        set(copy, 4, format(z));
        set(copy, 6, format(heading));
        set(copy, 8, Integer.toString(area));

        return Collections.unmodifiableList(copy);
    }

    private static List<String> rewriteSequenceOnly(List<String> template)
    {
        List<String> copy = new ArrayList<>(template);

        set(copy, 0, Integer.toString(updateSequence));
        set(copy, 1, Integer.toString(movementSequence));

        return Collections.unmodifiableList(copy);
    }

    private static void set(List<String> list, int index, String value)
    {
        if (index >= 0 && index < list.size())
        {
            list.set(index, value);
        }
    }

    private static Double parse(String value)
    {
        try
        {
            return Double.parseDouble(value.trim());
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static String format(double value)
    {
        return String.format(java.util.Locale.US, "%.3f", value);
    }
}
