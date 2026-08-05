import java.util.ArrayList;
import java.util.List;

public class Party
{
    public static final int MAX_MEMBERS = 8;

    public final int partyId;
    public final List<String> members = new ArrayList<>();
    private int scaleStepMilli = 500;
    private int scaleMaxMilli = 4000;

    public Party(int partyId)
    {
        this.partyId = partyId;
    }

    public synchronized String leader()
    {
        if (members.isEmpty())
        {
            return null;
        }

        return members.get(0);
    }

    public synchronized boolean contains(String usernameKey)
    {
        return members.contains(usernameKey);
    }

    public synchronized boolean add(String usernameKey)
    {
        if (members.contains(usernameKey) || members.size() >= MAX_MEMBERS)
        {
            return false;
        }

        members.add(usernameKey);
        return true;
    }

    public synchronized boolean remove(String usernameKey)
    {
        return members.remove(usernameKey);
    }

    public synchronized int size()
    {
        return members.size();
    }

    public synchronized List<String> snapshot()
    {
        return new ArrayList<>(members);
    }

    public synchronized boolean applyLeaderScaling(String usernameKey, int stepMilli, int maxMilli)
    {
        if (members.isEmpty() || !members.get(0).equals(usernameKey))
        {
            return false;
        }

        if (scaleStepMilli == stepMilli && scaleMaxMilli == maxMilli)
        {
            return false;
        }

        scaleStepMilli = stepMilli;
        scaleMaxMilli = maxMilli;
        return true;
    }

    public synchronized int scaleStepMilli()
    {
        return scaleStepMilli;
    }

    public synchronized int scaleMaxMilli()
    {
        return scaleMaxMilli;
    }
}
