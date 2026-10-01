package com.skillpulse.timetable;

import com.skillpulse.adaptive.UserTopicMastery;
import com.skillpulse.adaptive.UserTopicMasteryRepository;
import com.skillpulse.auth.AppUser;
import com.skillpulse.integration.MlServiceClient;
import com.skillpulse.personalization.PersonalizedPlan;
import com.skillpulse.personalization.PersonalizedPlanDay;
import com.skillpulse.personalization.PersonalizedPlanDayRepository;
import com.skillpulse.personalization.PersonalizedPlanRepository;
import com.skillpulse.personalization.PersonalizedPlanTask;
import com.skillpulse.personalization.PersonalizedPlanTaskRepository;
import com.skillpulse.personalization.PlanProgressService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Weekly timetable on top of the existing 7-day plan (PersonalizedPlan > Day > Task).
 *
 * Rules, same as the rest of the app:
 *  - Rebalancing is the deterministic {@link TimetableRebalancer}. The LLM (Python ml-service) only writes the
 *    friendly explanation afterwards, and a plain built-in sentence is used when it is unavailable.
 *  - Database work happens inside short transactions; the ML call happens OUTSIDE any transaction.
 */
@Service
public class TimetableService {
    /** A later day may hold up to 25% more than the busiest planned day before it counts as full. */
    private static final double DAY_OVERFLOW = 1.25;
    private static final int MIN_DAY_CAPACITY = 15;

    private final PersonalizedPlanRepository plans;
    private final PersonalizedPlanDayRepository days;
    private final PersonalizedPlanTaskRepository tasks;
    private final UserTopicMasteryRepository mastery;
    private final PlanProgressService progress;
    private final MlServiceClient ml;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public TimetableService(PersonalizedPlanRepository plans, PersonalizedPlanDayRepository days,
                            PersonalizedPlanTaskRepository tasks, UserTopicMasteryRepository mastery,
                            PlanProgressService progress, MlServiceClient ml, PlatformTransactionManager txManager) {
        this.plans = plans;
        this.days = days;
        this.tasks = tasks;
        this.mastery = mastery;
        this.progress = progress;
        this.ml = ml;
        TransactionTemplate read = new TransactionTemplate(txManager);
        read.setReadOnly(true);
        this.readTx = read;
        this.writeTx = new TransactionTemplate(txManager);
    }

    // ==== read =============================================================================================

    /** The whole timetable for the learner's latest plan: days sorted by date, tasks with minutes, progress. */
    public Map<String, Object> view(final AppUser user) {
        return readTx.execute(status -> buildView(user.getId()));
    }

    private Map<String, Object> buildView(Long userId) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        PersonalizedPlan plan = plans.findFirstByUserIdOrderByCreatedAtDesc(userId).orElse(null);
        if (plan == null) {
            out.put("hasPlan", false);
            return out;
        }
        LocalDate today = LocalDate.now();
        int total = 0;
        int done = 0;
        int overdueDays = 0;
        int todayPending = 0;
        Long todayDayId = null;
        boolean todayDayIsOpen = false;
        boolean celebrate = false;

        List<Map<String, Object>> dayRows = new ArrayList<Map<String, Object>>();
        for (PersonalizedPlanDay day : orderedDays(plan)) {
            LocalDate date = day.getTargetDate();
            int pendingMinutes = 0;
            int pendingTasks = 0;
            List<Map<String, Object>> taskRows = new ArrayList<Map<String, Object>>();
            for (PersonalizedPlanTask task : sortedTasks(day)) {
                int minutes = minutesOf(task, day);
                total++;
                if (task.isCompleted()) {
                    done++;
                } else {
                    pendingMinutes += minutes;
                    pendingTasks++;
                }
                Map<String, Object> tr = new LinkedHashMap<String, Object>();
                tr.put("taskId", task.getId());
                tr.put("description", task.getDescription());
                tr.put("minutes", minutes);
                tr.put("completed", task.isCompleted());
                tr.put("moved", task.getRescheduleCount() > 0);
                taskRows.add(tr);
            }
            String state = day.isCompleted() ? "DONE"
                    : today.equals(date) ? "TODAY"
                    : date.isBefore(today) ? "MISSED" : "UPCOMING";
            if (today.equals(date)) {
                // Two days can share a date after a reschedule; prefer the one that is still open.
                boolean open = !day.isCompleted();
                if (todayDayId == null || (open && !todayDayIsOpen)) {
                    todayDayId = day.getId();
                    todayDayIsOpen = open;
                    todayPending = pendingMinutes;
                    celebrate = day.isCompleted();
                }
            }
            if (!day.isCompleted() && date.isBefore(today) && pendingTasks > 0) overdueDays++;

            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("dayId", day.getId());
            row.put("day", day.getDayNumber());
            row.put("date", date.toString());
            row.put("weekday", date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
            row.put("focus", day.getFocus());
            row.put("objective", day.getObjective());
            row.put("plannedMinutes", day.getPlannedMinutes());
            row.put("pendingMinutes", pendingMinutes);
            row.put("status", state);
            row.put("completed", day.isCompleted());
            row.put("tasks", taskRows);
            dayRows.add(row);
        }

        Map<String, Object> prog = new LinkedHashMap<String, Object>();
        prog.put("tasksDone", done);
        prog.put("tasksTotal", total);
        prog.put("percent", total == 0 ? 0 : Math.round(done * 100.0f / total));

        out.put("hasPlan", true);
        out.put("planId", plan.getId());
        out.put("subject", plan.getSubjectName());
        out.put("summary", plan.getSummary());
        out.put("engine", plan.getEngine());
        out.put("today", today.toString());
        out.put("todayDayId", todayDayId);
        out.put("todayPendingMinutes", todayPending);
        out.put("overdueDays", overdueDays);
        out.put("celebrate", celebrate);
        out.put("progress", prog);
        out.put("days", dayRows);
        return out;
    }

    // ==== "Target done" ====================================================================================

    /** Ticks every task of one day ("Target done") and marks the day complete. */
    public Map<String, Object> complete(final AppUser user, final Long dayId) {
        writeTx.execute(status -> {
            PersonalizedPlanDay day = ownedDay(user, dayId);
            if (day.getTasks().isEmpty()) {
                throw new IllegalArgumentException("This day has no tasks left, so there is nothing to complete.");
            }
            for (PersonalizedPlanTask task : new ArrayList<PersonalizedPlanTask>(day.getTasks())) {
                if (!task.isCompleted()) progress.completeTask(user, task.getId());
            }
            boolean allDone = true;
            for (PersonalizedPlanTask task : day.getTasks()) {
                if (!task.isCompleted()) {
                    allDone = false;
                    break;
                }
            }
            if (allDone && !day.isCompleted()) {
                day.setCompleted(true);
                day.setCompletedAt(Instant.now());
                days.save(day);
            }
            return null;
        });
        return view(user);
    }

    // ==== "Adjust" =========================================================================================

    /**
     * Rebalances today: keeps the highest-priority tasks inside the minutes the learner has and pushes the rest to
     * later days with spare time. Also carries overdue tasks forward. Returns the new timetable plus an
     * "adjustment" block that explains what moved.
     */
    public Map<String, Object> adjust(final AppUser user, final Long dayId, Integer minutesAvailable,
                                      boolean cantStudyToday) {
        final int available;
        if (cantStudyToday) {
            available = 0;
        } else {
            if (minutesAvailable == null || minutesAvailable < 5 || minutesAvailable > 480) {
                throw new IllegalArgumentException(
                        "Enter between 5 and 480 minutes, or choose \"I can't study today\".");
            }
            available = minutesAvailable;
        }
        final Outcome outcome = writeTx.execute(status -> applyAdjust(user, dayId, available));

        Map<String, Object> out = view(user);
        out.put("adjustment", describe(outcome, available, cantStudyToday));
        return out;
    }

    private Outcome applyAdjust(AppUser user, Long dayId, int available) {
        PersonalizedPlanDay todayDay = ownedDay(user, dayId);
        LocalDate today = LocalDate.now();
        if (!today.equals(todayDay.getTargetDate())) {
            throw new IllegalArgumentException("You can only adjust today's study day.");
        }
        List<PersonalizedPlanDay> all = orderedDays(todayDay.getPlan());

        // 1. Fix each task's minutes BEFORE anything moves, so later moves never change how long a task takes.
        int busiest = 0;
        for (PersonalizedPlanDay day : all) {
            busiest = Math.max(busiest, day.getPlannedMinutes());
            for (PersonalizedPlanTask task : day.getTasks()) {
                if (task.getPlannedMinutes() == null || task.getPlannedMinutes() <= 0) {
                    task.setPlannedMinutes(minutesOf(task, day));
                    tasks.save(task);
                }
            }
        }

        // 2. The pool: unfinished tasks due today or overdue.
        Map<Long, PersonalizedPlanDay> dayById = new HashMap<Long, PersonalizedPlanDay>();
        Map<Long, PersonalizedPlanTask> taskById = new HashMap<Long, PersonalizedPlanTask>();
        List<TimetableRebalancer.Item> pool = new ArrayList<TimetableRebalancer.Item>();
        for (PersonalizedPlanDay day : all) {
            dayById.put(day.getId(), day);
            if (day.isCompleted() || day.getTargetDate().isAfter(today)) continue;
            boolean overdue = day.getTargetDate().isBefore(today);
            for (PersonalizedPlanTask task : day.getTasks()) {
                if (task.isCompleted()) continue;
                taskById.put(task.getId(), task);
                pool.add(new TimetableRebalancer.Item(task.getId(), minutesOf(task, day),
                        priority(user.getId(), task, overdue)));
            }
        }
        if (pool.isEmpty()) {
            throw new IllegalArgumentException("Nothing to rearrange: every task due up to today is already done.");
        }

        // 3. Later days and how much free time each has (already in date order).
        int capacity = (int) Math.round(Math.max(busiest, MIN_DAY_CAPACITY) * DAY_OVERFLOW);
        List<TimetableRebalancer.Slot> later = new ArrayList<TimetableRebalancer.Slot>();
        for (PersonalizedPlanDay day : all) {
            if (day.isCompleted() || !day.getTargetDate().isAfter(today)) continue;
            int pending = 0;
            for (PersonalizedPlanTask task : day.getTasks()) {
                if (!task.isCompleted()) pending += minutesOf(task, day);
            }
            later.add(new TimetableRebalancer.Slot(day.getId(), capacity - pending));
        }

        // 4. Rebalance and apply.
        TimetableRebalancer.Result result = TimetableRebalancer.rebalance(pool, todayDay.getId(), available, later);
        Outcome outcome = new Outcome();
        for (Map.Entry<Long, Long> entry : result.placement.entrySet()) {
            PersonalizedPlanTask task = taskById.get(entry.getKey());
            PersonalizedPlanDay target = dayById.get(entry.getValue());
            if (task == null || target == null) continue;
            PersonalizedPlanDay from = task.getPlanDay();
            if (!from.getId().equals(target.getId())) {
                from.getTasks().remove(task);
                target.getTasks().add(task);
                task.setPlanDay(target);
                task.setRescheduleCount(task.getRescheduleCount() + 1);
                tasks.save(task);
                if (target.isCompleted()) { // receiving unfinished work re-opens the day
                    target.setCompleted(false);
                    target.setCompletedAt(null);
                    days.save(target);
                }
            }
            if (target.getId().equals(todayDay.getId())) {
                outcome.kept.add(task.getDescription());
            } else {
                outcome.moved.add(new String[] {task.getDescription(), whenLabel(target.getTargetDate(), today)});
            }
        }
        for (Long id : result.unplaced) {
            PersonalizedPlanTask task = taskById.get(id);
            if (task != null) outcome.unplaced.add(task.getDescription());
        }
        return outcome;
    }

    // ==== explanation ======================================================================================

    private Map<String, Object> describe(Outcome outcome, int available, boolean restDay) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("minutesToday", available);
        out.put("restDay", restDay);
        out.put("keptCount", outcome.kept.size());
        out.put("movedCount", outcome.moved.size());
        out.put("unplacedCount", outcome.unplaced.size());
        List<Map<String, Object>> movedRows = new ArrayList<Map<String, Object>>();
        for (String[] m : outcome.moved) {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("task", m[0]);
            r.put("when", m[1]);
            movedRows.add(r);
        }
        out.put("movedTasks", movedRows);
        out.put("unplacedTasks", outcome.unplaced);
        out.put("warning", outcome.unplaced.isEmpty() ? null
                : "The rest of this week is full, so " + tasksWord(outcome.unplaced.size())
                + " could not be moved and stay where they are. Use \"Catch up\" on My Plan to shift whole days, "
                + "or create a new plan.");

        // The LLM only words the explanation. Every fact in it comes from this deterministic outcome.
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("availableMinutes", available);
        body.put("restDay", restDay);
        body.put("kept", clipList(outcome.kept));
        List<Object> movedForMl = new ArrayList<Object>();
        for (int i = 0; i < outcome.moved.size() && i < 10; i++) {
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("task", clip(outcome.moved.get(i)[0]));
            r.put("when", outcome.moved.get(i)[1]);
            movedForMl.add(r);
        }
        body.put("moved", movedForMl);
        body.put("unplaced", clipList(outcome.unplaced));
        Map<String, Object> reply = ml.callSlow("/timetable/explain", body);
        String message = reply != null && reply.get("message") instanceof String
                ? ((String) reply.get("message")).trim() : "";
        if (message.isEmpty()) {
            out.put("message", template(outcome, available, restDay));
            out.put("messageEngine", "built-in");
        } else {
            out.put("message", message);
            out.put("messageEngine", String.valueOf(reply.get("engine")));
        }
        return out;
    }

    private static String template(Outcome o, int available, boolean rest) {
        StringBuilder sb = new StringBuilder();
        if (rest) {
            sb.append("Rest well today.");
            if (!o.moved.isEmpty()) {
                sb.append(" I moved ").append(tasksWord(o.moved.size()))
                        .append(" to later days, starting with the ones you need most.");
            }
        } else {
            sb.append("With ").append(available).append(" minutes today, I kept ")
                    .append(tasksWord(o.kept.size())).append(" that matter most");
            if (!o.moved.isEmpty()) sb.append(" and moved ").append(tasksWord(o.moved.size())).append(" to later days");
            sb.append(".");
        }
        if (!o.unplaced.isEmpty()) {
            sb.append(" ").append(tasksWord(o.unplaced.size())).append(" did not fit into the rest of the week.");
        }
        return sb.toString();
    }

    // ==== helpers ==========================================================================================

    private static final class Outcome {
        final List<String> kept = new ArrayList<String>();
        final List<String[]> moved = new ArrayList<String[]>();
        final List<String> unplaced = new ArrayList<String>();
    }

    private PersonalizedPlanDay ownedDay(AppUser user, Long dayId) {
        if (dayId == null) throw new IllegalArgumentException("Day not found.");
        PersonalizedPlanDay day = days.findById(dayId)
                .orElseThrow(() -> new IllegalArgumentException("Day not found."));
        if (!day.getPlan().getUser().getId().equals(user.getId())) throw new IllegalArgumentException("Day not found.");
        return day;
    }

    /** Low mastery first, and anything overdue gets a bonus. Higher number = more urgent. */
    private double priority(Long userId, PersonalizedPlanTask task, boolean overdue) {
        double m = 0.5;
        if (task.getTopic() != null) {
            Optional<UserTopicMastery> row = mastery.findByUserIdAndTopicId(userId, task.getTopic().getId());
            if (row.isPresent()) m = row.get().getMasteryProbability();
        }
        return (1.0 - m) + (overdue ? 0.25 : 0.0);
    }

    private static int minutesOf(PersonalizedPlanTask task, PersonalizedPlanDay day) {
        Integer stored = task.getPlannedMinutes();
        if (stored != null && stored > 0) return stored;
        int count = Math.max(1, day.getTasks().size());
        return Math.max(5, (int) Math.round(day.getPlannedMinutes() / (double) count));
    }

    private static List<PersonalizedPlanDay> orderedDays(PersonalizedPlan plan) {
        List<PersonalizedPlanDay> list = new ArrayList<PersonalizedPlanDay>(plan.getDays());
        Collections.sort(list, new Comparator<PersonalizedPlanDay>() {
            @Override
            public int compare(PersonalizedPlanDay a, PersonalizedPlanDay b) {
                int byDate = a.getTargetDate().compareTo(b.getTargetDate());
                return byDate != 0 ? byDate : Integer.compare(a.getDayNumber(), b.getDayNumber());
            }
        });
        return list;
    }

    private static List<PersonalizedPlanTask> sortedTasks(PersonalizedPlanDay day) {
        List<PersonalizedPlanTask> list = new ArrayList<PersonalizedPlanTask>(day.getTasks());
        Collections.sort(list, new Comparator<PersonalizedPlanTask>() {
            @Override
            public int compare(PersonalizedPlanTask a, PersonalizedPlanTask b) {
                return Long.compare(a.getId(), b.getId());
            }
        });
        return list;
    }

    private static String whenLabel(LocalDate date, LocalDate today) {
        if (date.equals(today.plusDays(1))) return "tomorrow";
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static String tasksWord(int n) {
        return n + (n == 1 ? " task" : " tasks");
    }

    private static String clip(String value) {
        String v = value == null ? "" : value.trim();
        return v.length() <= 140 ? v : v.substring(0, 140);
    }

    private static List<Object> clipList(List<String> values) {
        List<Object> out = new ArrayList<Object>();
        for (int i = 0; i < values.size() && i < 10; i++) out.add(clip(values.get(i)));
        return out;
    }
}
