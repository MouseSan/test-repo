import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;

public class EventStatisticsCounterImpl implements EventStatisticsCounter {

    private final static int SECONDS_IN_MINUTE = 60;
    private final static int SECONDS_IN_HOUR = SECONDS_IN_MINUTE * 60;
    private final static int SECONDS_IN_DAY = SECONDS_IN_HOUR * 24;
    private final static int SECONDS_IN_THREE_DAYS = SECONDS_IN_DAY * 3;

    private AtomicLongArray counter = new AtomicLongArray(SECONDS_IN_THREE_DAYS);
    private LocalDateTime startTime;

    private ScheduledExecutorService scheduledPool;
    private ExecutorService cachedPool;
    private ReentrantLock lock;


    public EventStatisticsCounterImpl() {
        this.startTime = LocalDateTime.now();
        startScheduledTaskClearArray();
        cachedPool = Executors.newCachedThreadPool();
        lock = new ReentrantLock();
    }

    private void startScheduledTaskClearArray() {
        scheduledPool = Executors.newScheduledThreadPool(1);
        Runnable taskClearArray = () -> cleanOldData();
        scheduledPool.scheduleAtFixedRate(taskClearArray, 1, 1, TimeUnit.DAYS);
    }

    private void cleanOldData() {
        long seconds = ChronoUnit.SECONDS.between(startTime, LocalDateTime.now());
        long position = seconds % SECONDS_IN_THREE_DAYS;

        if (position < SECONDS_IN_DAY) {
            resetArray(0, SECONDS_IN_DAY - 1);
        } else if (position < SECONDS_IN_DAY * 2) {
            resetArray(SECONDS_IN_DAY, SECONDS_IN_DAY * 2 - 1);
        } else {
            resetArray(SECONDS_IN_DAY * 2, SECONDS_IN_THREE_DAYS - 1);
        }
    }

    private void resetArray(int startIndex, int lastIndex) {
        for (int i = startIndex; i <= lastIndex; i++) {
            counter.getAndSet(i, 0L);
        }
    }

    public void shutdown() {
        scheduledPool.shutdown();
        cachedPool.shutdown();
    }

    @Override
    public void addEvent(LocalDateTime dateTime) {
        cachedPool.submit(() -> {
            validateTime(dateTime);
            while (lock.isLocked()){}
            counter.getAndIncrement(getCurrentPosition(dateTime));
        });
    }

    private void validateTime(LocalDateTime dateTime) {
        if (dateTime.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Transferred date [" + dateTime.toString() + "] more then current date.");
        }
    }

    private int getCurrentPosition(LocalDateTime eventTime) {
        long seconds = ChronoUnit.SECONDS.between(startTime, eventTime);
        return (int) (seconds % SECONDS_IN_THREE_DAYS);
    }

    @Override
    public long getNumberOfEventsForLastMinute() {
        return getNumberOfEventsForLastSeconds(SECONDS_IN_MINUTE);
    }

    @Override
    public long getNumberOfEventsForLastHour() {
        return getNumberOfEventsForLastSeconds(SECONDS_IN_HOUR);
    }

    @Override
    public long getNumberOfEventsForLastDay() {
        return getNumberOfEventsForLastSeconds(SECONDS_IN_DAY);
    }


    private long getNumberOfEventsForLastSeconds(int numberOfSeconds) {
        int numberOfEvents = 0;
        int currentPosition = getCurrentPosition(LocalDateTime.now());

        try {
            if (lock.tryLock() || lock.tryLock(numberOfSeconds, TimeUnit.MICROSECONDS)) {
                try {
                    if (currentPosition >= numberOfSeconds) {
                        for(int i = currentPosition - numberOfSeconds; i < currentPosition; i++){
                            numberOfEvents += counter.get(i);
                        }
                    } else {
                        int numberOfTailIndexes = numberOfSeconds - currentPosition;
                        for(int i = SECONDS_IN_THREE_DAYS - numberOfTailIndexes; i < SECONDS_IN_THREE_DAYS; i++){
                            numberOfEvents += counter.get(i);
                        }
                        for(int i = 0; i < currentPosition; i++){
                            numberOfEvents += counter.get(i);
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return numberOfEvents;
    }

}
