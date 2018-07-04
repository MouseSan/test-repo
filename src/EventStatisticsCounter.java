import java.time.LocalDateTime;

/**
 * Interface describes the event statistics counter.
 * It have ability to add new event and count number of events that came
 * in the last 60 seconds, last 60 minutes and last 24 hours.
 *
 * Precision of calculation 1 second.
 *
 * @author Viacheslav Myshkovetc
 */
public interface EventStatisticsCounter {

    /**
     * Method add a new event to the statistics counter.
     *
     * @param timeOfEvent - time at which event is happened.
     */
    void addEvent(LocalDateTime timeOfEvent);

    /**
     * Method counts number of events which came in the last 60 seconds.
     * 60 seconds counting from current time (e.g. time of method call).
     *
     * @return number of events for last 60 seconds.
     */
    long getNumberOfEventsForLastMinute();

    /**
     * Method counts number of events which came in the last 60 minutes.
     * 60 minutes counting from current time (e.g. time of method call).
     *
     * @return number of events for last 60 minutes.
     */
    long getNumberOfEventsForLastHour();

    /**
     * Method counts number of events which came in the last 24 hours.
     * 60 hours counting from current time (e.g. time of method call).
     *
     * @return number of events for last 60 seconds
     */
    long getNumberOfEventsForLastDay();

}
