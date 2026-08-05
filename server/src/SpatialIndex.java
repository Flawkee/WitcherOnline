import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SpatialIndex
{
    public static final double CELL_SIZE = 128.0;

    public static final class CellKey
    {
        public final int area;
        public final int x;
        public final int y;

        CellKey(int area, int x, int y)
        {
            this.area = area;
            this.x = x;
            this.y = y;
        }

        public static CellKey at(int area, double x, double y)
        {
            return new CellKey(area, cell(x), cell(y));
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof CellKey))
            {
                return false;
            }
            CellKey key = (CellKey) other;
            return area == key.area && x == key.x && y == key.y;
        }

        @Override
        public int hashCode()
        {
            int hash = area;
            hash = 31 * hash + x;
            hash = 31 * hash + y;
            return hash;
        }
    }

    private static final ConcurrentHashMap<CellKey, Set<PlayerSession>> cells = new ConcurrentHashMap<>();

    private SpatialIndex()
    {
    }

    public static void update(PlayerSession session, int area, double x, double y)
    {
        synchronized (session)
        {
            CellKey next = CellKey.at(area, x, y);
            CellKey previous = session.spatialCell;

            if (next.equals(previous))
            {
                return;
            }

            if (previous != null)
            {
                Set<PlayerSession> old = cells.get(previous);
                if (old != null)
                {
                    old.remove(session);
                    if (old.isEmpty())
                    {
                        cells.remove(previous, old);
                    }
                }
            }

            cells.computeIfAbsent(next, ignored -> ConcurrentHashMap.newKeySet()).add(session);
            session.spatialCell = next;
        }
    }

    public static void remove(PlayerSession session)
    {
        if (session == null)
        {
            return;
        }

        synchronized (session)
        {
            CellKey previous = session.spatialCell;
            session.spatialCell = null;

            if (previous == null)
            {
                return;
            }

            Set<PlayerSession> old = cells.get(previous);
            if (old != null)
            {
                old.remove(session);
                if (old.isEmpty())
                {
                    cells.remove(previous, old);
                }
            }
        }
    }

    public static List<PlayerSession> query(int area, double x, double y, double radius)
    {
        int minX = cell(x - radius);
        int maxX = cell(x + radius);
        int minY = cell(y - radius);
        int maxY = cell(y + radius);
        List<PlayerSession> result = new ArrayList<>();

        for (int cx = minX; cx <= maxX; cx++)
        {
            for (int cy = minY; cy <= maxY; cy++)
            {
                Set<PlayerSession> bucket = cells.get(new CellKey(area, cx, cy));
                if (bucket != null)
                {
                    result.addAll(bucket);
                }
            }
        }

        return result.isEmpty() ? Collections.emptyList() : result;
    }

    static int cell(double value)
    {
        return (int) Math.floor(value / CELL_SIZE);
    }
}
