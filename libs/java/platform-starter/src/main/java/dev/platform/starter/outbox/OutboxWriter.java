package dev.platform.starter.outbox;

/** Appends events to the outbox table inside the caller's database transaction. */
public interface OutboxWriter {

  /**
   * Stores the event (and the current W3C {@code traceparent}, if any) in the outbox. Must be
   * called inside an active transaction; otherwise throws {@link
   * org.springframework.transaction.IllegalTransactionStateException}.
   */
  void append(OutboxEvent event);
}
