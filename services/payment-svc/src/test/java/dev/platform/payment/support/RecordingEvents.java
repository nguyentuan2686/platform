package dev.platform.payment.support;

import dev.platform.payment.application.PaymentEventsPort;
import dev.platform.payment.domain.PaymentSucceeded;
import java.util.ArrayList;
import java.util.List;

public class RecordingEvents implements PaymentEventsPort {

  public final List<PaymentSucceeded> events = new ArrayList<>();

  @Override
  public void publish(PaymentSucceeded event) {
    events.add(event);
  }
}
