package dev.nla.query;

import dev.nla.common.ApiException;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;

/** One monotonic request budget shared by metadata, AI, planning and database calls. */
public final class QueryDeadline {
  private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();

  private QueryDeadline() {}

  public static void start() {
    DEADLINE.set(System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
  }

  public static void clear() {
    DEADLINE.remove();
  }

  public static void check() {
    seconds(60);
  }

  public static int seconds(int configured) {
    if (Thread.currentThread().isInterrupted())
      throw new ApiException(
          HttpStatus.GATEWAY_TIMEOUT, "QUERY_TIMEOUT", "Yêu cầu truy vấn đã dừng.");
    Long deadline = DEADLINE.get();
    if (deadline == null) return configured;
    long remaining = TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime());
    if (remaining < 1)
      throw new ApiException(
          HttpStatus.GATEWAY_TIMEOUT, "QUERY_TIMEOUT", "Truy vấn vượt thời gian toàn tác vụ.");
    return (int) Math.min(configured, remaining);
  }
}
