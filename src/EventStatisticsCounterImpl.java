import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Concurrent implementation of EventStatisticsCounter.
 *
 * All events are stored in {@link AtomicLongArray}.
 * Each array value stores number of events for a specific second.
 * The size of array is 259200 and can store events for the last three days.
 * To add new events and to count number of events for specific time period cached thread pool is used.
 * When counting number of events, we acquires lock to suspend adding of new events to AtomicLongArray.
 * When adding new events, we check the lock whether it locked or not.
 *
 * To add clean up AtomicLongArray (setting values to zero) scheduled thread pool is used.
 * Scheduled thread pool wake's up every 24 hours and clean data for the next day.
 *
 * For proper shutdown of class instance use shutdown() method.
 *
 * @author Viacheslav Myshkovetc
 */
public class EventStatisticsCounterImpl implements EventStatisticsCounter {

    private final static int SECONDS_IN_MINUTE = 60;
    private final static int SECONDS_IN_HOUR = SECONDS_IN_MINUTE * 60;
    private final static int SECONDS_IN_DAY = SECONDS_IN_HOUR * 24;
    private final static int SECONDS_IN_THREE_DAYS = SECONDS_IN_DAY * 3;

    /**
     * Event storage field which holds number of events for each specific second.
     */
    private AtomicLongArray eventStorage = new AtomicLongArray(SECONDS_IN_THREE_DAYS);

    /**
     * Date at which counter start working.
     */
    private LocalDateTime startTime;

    /**
     * Scheduled pool to execute clean up task.
     */
    private ScheduledExecutorService scheduledPool;

    /**
     * Cached pool to execute tasks which is add new events and count number of already stored events.
     */
    private ExecutorService cachedPool;

    /**
     * Lock is to determine whether counting of events is currently in progress or not.
     */
    private ReentrantLock lockCounting;

    public EventStatisticsCounterImpl() {
        startTime = LocalDateTime.now();
        startScheduledTaskClearArray();
        cachedPool = Executors.newCachedThreadPool();
        lockCounting = new ReentrantLock();
    }

    /**
     * Startup and configuration of scheduled pool.
     */
    private void startScheduledTaskClearArray() {
        scheduledPool = Executors.newScheduledThreadPool(1);
        Runnable taskClearArray = () -> cleanOldData();
        scheduledPool.scheduleAtFixedRate(taskClearArray, 1, 1, TimeUnit.DAYS);
    }

    /**
     * The method determines the current time interval (day)
     * and deletes the number of events for the next day.
     */
    private void cleanOldData() {
        long seconds = ChronoUnit.SECONDS.between(startTime, LocalDateTime.now());
        long position = seconds % SECONDS_IN_THREE_DAYS;

        if (position < SECONDS_IN_DAY) {
            setArrayValuesToZero(0, SECONDS_IN_DAY - 1);
        } else if (position < SECONDS_IN_DAY * 2) {
            setArrayValuesToZero(SECONDS_IN_DAY, SECONDS_IN_DAY * 2 - 1);
        } else {
            setArrayValuesToZero(SECONDS_IN_DAY * 2, SECONDS_IN_THREE_DAYS - 1);
        }
    }

    /**
     * The method sets the zeros for given index interval.
     */
    private void setArrayValuesToZero(int startIndex, int lastIndex) {
        for (int i = startIndex; i <= lastIndex; i++) {
            eventStorage.getAndSet(i, 0L);
        }
    }

    /**
     * Correct completion of work with the class.
     */
    public void shutdown() {
        scheduledPool.shutdown();
        cachedPool.shutdown();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addEvent(LocalDateTime timeOfEvent) {
        cachedPool.submit(() -> {
            validateTime(timeOfEvent);
            while (lockCounting.isLocked()){}
            eventStorage.getAndIncrement(getCurrentPosition(timeOfEvent));
        });
    }

    /**
     * Method checks date of event if it is greater than current date, throws an {@link IllegalArgumentException}.
     *
     * @param dateTime date of incoming event.
     */
    private void validateTime(LocalDateTime dateTime) {
        if (dateTime.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Transferred date [" + dateTime.toString() + "] more then current date.");
        }
    }

    /**
     * Method calculate index of array which corresponds to incoming event time.
     *
     * @param eventTime date of incoming event.
     */
    private int getCurrentPosition(LocalDateTime eventTime) {
        long seconds = ChronoUnit.SECONDS.between(startTime, eventTime);
        return (int) (seconds % SECONDS_IN_THREE_DAYS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNumberOfEventsForLastMinute() {
        return putTaskToCachedPoolAndGetResult(SECONDS_IN_MINUTE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNumberOfEventsForLastHour() {
        return putTaskToCachedPoolAndGetResult(SECONDS_IN_HOUR);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getNumberOfEventsForLastDay() {
        return putTaskToCachedPoolAndGetResult(SECONDS_IN_DAY);
    }

    /**
     * Concurrent wrapper for {@link EventStatisticsCounterImpl#getNumberOfEventsForLastSeconds(int)} method.
     * Methods creates new {@link java.util.concurrent.Callable} anonymous function, submits to cached thread pool
     * and then waiting for calculation.
     *
     * In case of concurrent errors zero value will be thrown and stack trace is printed to console.
     *
     * @param numberOfSeconds number of last seconds for which we need to calculate number of events.
     * @return number of events for provided last seconds.
     */
    private long putTaskToCachedPoolAndGetResult(int numberOfSeconds) {
        Future<Long> callableFuture = cachedPool.submit(() -> getNumberOfEventsForLastSeconds(numberOfSeconds));

        long result = 0;
        try {
            result = callableFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Methods iterates over the array indexes and counts number of stored events.
     * If number of seconds more than current index, start iterating form the end of arrays.
     * Number of indexes which we need to deduct from the end of array is (currentIndex - numberOfSeconds),
     * this will be start index of iteration.
     *
     * @param numberOfSeconds number of last seconds for which we need to calculate number of events.
     * @return number of events for provided last seconds.
     */
    private long getNumberOfEventsForLastSeconds(int numberOfSeconds) {
        int numberOfEvents = 0;

        try {
            if (lockCounting.tryLock() || lockCounting.tryLock(numberOfSeconds, TimeUnit.MICROSECONDS)) {
                try {
                    int currentPosition = getCurrentPosition(LocalDateTime.now());

                    if (currentPosition >= numberOfSeconds) {
                        for(int i = currentPosition - numberOfSeconds; i < currentPosition; i++){
                            numberOfEvents += eventStorage.get(i);
                        }
                    } else {
                        int numberOfTailIndexes = numberOfSeconds - currentPosition;
                        for(int i = SECONDS_IN_THREE_DAYS - numberOfTailIndexes; i < SECONDS_IN_THREE_DAYS; i++){
                            numberOfEvents += eventStorage.get(i);
                        }
                        for(int i = 0; i < currentPosition; i++){
                            numberOfEvents += eventStorage.get(i);
                        }
                    }
                } finally {
                    lockCounting.unlock();
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return numberOfEvents;
    }

}
