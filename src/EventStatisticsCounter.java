import java.time.LocalDateTime;

public interface EventStatisticsCounter {

    void addEvent(LocalDateTime dateTime);

    long getNumberOfEventsForLastMinute();

    long getNumberOfEventsForLastHour();

    long getNumberOfEventsForLastDay();

}
