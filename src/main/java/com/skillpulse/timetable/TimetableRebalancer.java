package com.skillpulse.timetable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The timetable's rebalancing rule. It is a plain deterministic algorithm, never an LLM call, so the result is always
 * a valid timetable:
 *
 *  1. Take every unfinished task that is due today or overdue (the "pool").
 *  2. Sort it by priority, highest first (low mastery and overdue tasks have the highest priority).
 *  3. Keep tasks in today's minutes while they fit.
 *  4. Push each remaining task to the first later day that still has enough spare minutes.
 *  5. Whatever fits nowhere is reported as unplaced, so the page can warn that the week cannot hold it all.
 *
 * It has no Spring or database dependencies on purpose, so it is easy to unit test.
 */
public final class TimetableRebalancer {
    private TimetableRebalancer() { }

    /** One unfinished task that needs a place. */
    public static final class Item {
        public final long taskId;
        public final int minutes;
        public final double priority;

        public Item(long taskId, int minutes, double priority) {
            this.taskId = taskId;
            this.minutes = Math.max(1, minutes);
            this.priority = priority;
        }
    }

    /** A later day and how many free minutes it still has. */
    public static final class Slot {
        public final long dayId;
        public int spare;

        public Slot(long dayId, int spare) {
            this.dayId = dayId;
            this.spare = Math.max(0, spare);
        }
    }

    public static final class Result {
        /** taskId -> id of the day it should now be on (today's day or a later day). Insertion order = priority order. */
        public final Map<Long, Long> placement = new LinkedHashMap<Long, Long>();
        /** Tasks that fit neither today nor any later day. They stay where they are. */
        public final List<Long> unplaced = new ArrayList<Long>();
    }

    public static Result rebalance(List<Item> pool, long todayDayId, int todayMinutes, List<Slot> laterDays) {
        List<Item> sorted = new ArrayList<Item>(pool);
        Collections.sort(sorted, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                int byPriority = Double.compare(b.priority, a.priority);
                return byPriority != 0 ? byPriority : Long.compare(a.taskId, b.taskId);
            }
        });

        Result result = new Result();
        int left = Math.max(0, todayMinutes);
        for (Item item : sorted) {
            if (item.minutes <= left) {
                left -= item.minutes;
                result.placement.put(item.taskId, todayDayId);
                continue;
            }
            boolean placed = false;
            for (Slot slot : laterDays) {
                if (slot.spare >= item.minutes) {
                    slot.spare -= item.minutes;
                    result.placement.put(item.taskId, slot.dayId);
                    placed = true;
                    break;
                }
            }
            if (!placed) result.unplaced.add(item.taskId);
        }
        return result;
    }
}
