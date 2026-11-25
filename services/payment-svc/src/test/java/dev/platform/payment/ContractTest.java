package dev.platform.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.platform.payment.adapter.in.web.AdminController;
import dev.platform.payment.adapter.in.web.OrderController;
import dev.platform.payment.adapter.in.web.PayPalWebhookController;
import dev.platform.payment.adapter.in.web.PaymentExceptionHandler;
import dev.platform.payment.adapter.in.web.ProductController;
import dev.platform.payment.adapter.in.web.RefundController;
import dev.platform.payment.adapter.in.web.SepayWebhookAuthenticator;
import dev.platform.payment.adapter.in.web.SepayWebhookController;
import dev.platform.payment.application.AdminUseCase;
import dev.platform.payment.application.CatalogEntry;
import dev.platform.payment.application.CatalogUseCase;
import dev.platform.payment.application.ConflictException;
import dev.platform.payment.application.CreateOrderCommand;
import dev.platform.payment.application.CreateOrderUseCase;
import dev.platform.payment.application.CreatedOrder;
import dev.platform.payment.application.GetOrderUseCase;
import dev.platform.payment.application.OrderValidationException;
import dev.platform.payment.application.OrderView;
import dev.platform.payment.application.PayPalWebhookUseCase;
import dev.platform.payment.application.PaymentInstructions;
import dev.platform.payment.application.PaymentNotConfiguredException;
import dev.platform.payment.application.RecordBankTransactionUseCase;
import dev.platform.payment.application.RefundUseCase;
import dev.platform.payment.application.UpstreamUnavailableException;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderCode;
import dev.platform.payment.domain.Provider;
import dev.platform.payment.domain.RefundRequest;
import dev.platform.payment.domain.RefundStatus;
import dev.platform.payment.domain.UnmatchedReason;
import dev.platform.starter.error.NotFoundException;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest({
  OrderController.class,
  ProductController.class,
  SepayWebhookController.class,
  PayPalWebhookController.class,
  AdminController.class,
  RefundController.class
})
@Import({PlatformWebAutoConfiguration.class, PaymentExceptionHandler.class})
class ContractTest {

  static final UUID ACCOUNT = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");
  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");
  static final String BODY =
      "{\"account_id\":\"6f9619ff-8b86-d011-b42d-00c04fc964ff\",\"site\":\"tuvi\","
          + "\"product_code\":\"CREDIT_5\",\"currency\":\"VND\",\"provider\":\"SEPAY\","
          + "\"adult_confirmed\":true}";

  @Autowired MockMvc mvc;
  @Autowired RequestMappingHandlerMapping mappings;
  @MockitoBean CreateOrderUseCase createOrder;
  @MockitoBean GetOrderUseCase getOrder;
  @MockitoBean CatalogUseCase catalog;
  @MockitoBean SepayWebhookAuthenticator sepayAuth;
  @MockitoBean RecordBankTransactionUseCase recorder;
  @MockitoBean PayPalWebhookUseCase paypalWebhook;
  @MockitoBean AdminUseCase admin;
  @MockitoBean RefundUseCase refunds;
  @MockitoBean Clock clock;

  static Order order(Provider provider) {
    return Order.create(
        UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964fe"),
        new OrderCode("TV234567"),
        ACCOUNT,
        "tuvi",
        "CREDIT_5",
        null,
        Money.of("119000", "VND"),
        provider,
        T0,
        Duration.ofMinutes(30));
  }

  @Test
  void every_path_and_method_in_the_spec_is_served() {
    Set<String> served = new HashSet<>();
    for (Map.Entry<RequestMappingInfo, ?> e : mappings.getHandlerMethods().entrySet()) {
      for (RequestMethod m : e.getKey().getMethodsCondition().getMethods()) {
        e.getKey().getPatternValues().forEach(p -> served.add(m + " " + p));
      }
    }
    Set<String> declared = new HashSet<>();
    JsonNode paths = ContractSupport.openApi().get("paths");
    for (String path : paths.propertyNames()) {
      if (path.startsWith("/health/")) {
        continue; // actuator group, not a controller mapping
      }
      for (String method : paths.get(path).propertyNames()) {
        declared.add(method.toUpperCase(Locale.ROOT) + " " + path);
      }
    }

    assertThat(declared)
        .containsExactlyInAnyOrder(
            "GET /products",
            "POST /orders",
            "GET /orders",
            "GET /orders/{id}",
            "POST /orders/{id}/refund-requests",
            "POST /webhooks/sepay",
            "POST /webhooks/paypal",
            "GET /admin/unmatched-transactions",
            "POST /admin/unmatched-transactions/{id}/assign",
            "POST /admin/unmatched-transactions/{id}/resolve",
            "GET /admin/refund-requests",
            "POST /admin/refund-requests/{id}/complete");
    assertThat(served).containsAll(declared);
  }

  @Test
  void created_order_with_vietqr_matches_the_spec_schema() throws Exception {
    Order order = order(Provider.SEPAY);
    when(createOrder.create(any(CreateOrderCommand.class)))
        .thenReturn(
            new CreatedOrder(
                order,
                new PaymentInstructions.VietQr(
                    "local-test-bank",
                    "0000000000",
                    "TEST BANK",
                    order.amount(),
                    "TV234567",
                    "https://qr.example.invalid/img?amount=119000&des=TV234567")));

    JsonNode json =
        body(
            mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)),
            201);

    assertThat(ContractSupport.componentSchema("CreatedOrder").validate(json)).isEmpty();
    assertThat(json.get("payment").get("kind").asString()).isEqualTo("VIETQR");
  }

  @Test
  void created_order_with_paypal_matches_the_spec_schema() throws Exception {
    Order order = order(Provider.PAYPAL);
    when(createOrder.create(any(CreateOrderCommand.class)))
        .thenReturn(
            new CreatedOrder(
                order,
                new PaymentInstructions.PayPal(
                    URI.create("https://paypal.fake.invalid/checkoutnow?token=FAKE-PP-1"))));

    JsonNode json =
        body(
            mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)),
            201);

    assertThat(ContractSupport.componentSchema("CreatedOrder").validate(json)).isEmpty();
  }

  @Test
  void order_get_and_list_match_the_spec_schema() throws Exception {
    Order order = order(Provider.SEPAY);
    when(getOrder.get(order.id(), ACCOUNT)).thenReturn(new OrderView(order, "PENDING"));
    when(getOrder.list(ACCOUNT)).thenReturn(List.of(new OrderView(order, "EXPIRED")));

    JsonNode one =
        body(
            mvc.perform(get("/orders/" + order.id()).param("account_id", ACCOUNT.toString())), 200);
    JsonNode many = body(mvc.perform(get("/orders").param("account_id", ACCOUNT.toString())), 200);

    assertThat(ContractSupport.componentSchema("Order").validate(one)).isEmpty();
    assertThat(many.isArray()).isTrue();
    assertThat(ContractSupport.componentSchema("Order").validate(many.get(0))).isEmpty();
  }

  @Test
  void products_match_the_spec_schema() throws Exception {
    when(catalog.list("tuvi", "VND"))
        .thenReturn(List.of(new CatalogEntry("PASS_MONTH", Money.of("49000", "VND"))));

    JsonNode json =
        body(mvc.perform(get("/products").param("site", "tuvi").param("currency", "VND")), 200);

    assertThat(ContractSupport.componentSchema("Product").validate(json.get(0))).isEmpty();
  }

  @Test
  void schema_check_is_not_vacuous() {
    JsonNode bad = JsonMapper.shared().readTree("{\"order_id\":\"x\",\"status\":\"NOPE\"}");
    assertThat(ContractSupport.componentSchema("CreatedOrder").validate(bad)).isNotEmpty();
    assertThat(
            ContractSupport.componentSchema("Problem")
                .validate(JsonMapper.shared().readTree("{\"title\":\"x\"}")))
        .isNotEmpty();
  }

  @Test
  void problems_match_the_spec_schema() throws Exception {
    when(createOrder.create(any(CreateOrderCommand.class)))
        .thenThrow(new OrderValidationException("currency", "must be VND for SEPAY"));
    JsonNode validation =
        body(
            mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)),
            400);
    assertThat(ContractSupport.componentSchema("Problem").validate(validation)).isEmpty();
    assertThat(validation.get("errors").get(0).get("field").asString()).isEqualTo("currency");

    UUID missing = UUID.randomUUID();
    when(getOrder.get(missing, ACCOUNT)).thenThrow(new NotFoundException("order", missing));
    JsonNode notFound =
        body(mvc.perform(get("/orders/" + missing).param("account_id", ACCOUNT.toString())), 404);
    assertThat(ContractSupport.componentSchema("Problem").validate(notFound)).isEmpty();

    when(createOrder.create(any(CreateOrderCommand.class)))
        .thenThrow(new PaymentNotConfiguredException());
    JsonNode unavailable =
        body(
            mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)),
            503);
    assertThat(ContractSupport.componentSchema("Problem").validate(unavailable)).isEmpty();
  }

  @Test
  void admin_queue_and_refund_requests_match_the_spec_schema() throws Exception {
    when(admin.unmatched())
        .thenReturn(
            List.of(
                new AdminUseCase.UnmatchedView(
                    7L,
                    Provider.SEPAY,
                    Money.of("100000", "VND"),
                    T0,
                    UnmatchedReason.AMOUNT_LOW,
                    "TV234567 chuyen tien",
                    List.of("TV234567"))));
    RefundRequest request = RefundRequest.open(UUID.randomUUID(), order(Provider.SEPAY), T0);
    when(admin.refundRequests(RefundStatus.OPEN)).thenReturn(List.of(request));
    when(admin.completeRefund(any(UUID.class), any())).thenReturn(request);
    when(refunds.request(any(UUID.class), any(UUID.class)))
        .thenReturn(new RefundUseCase.Result(request, true));

    JsonNode queue = body(mvc.perform(get("/admin/unmatched-transactions")), 200);
    JsonNode open = body(mvc.perform(get("/admin/refund-requests")), 200);
    JsonNode done =
        body(
            mvc.perform(
                post("/admin/refund-requests/" + request.id() + "/complete")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"note\":\"sent\"}")),
            200);
    JsonNode created =
        body(
            mvc.perform(
                post("/orders/" + request.orderId() + "/refund-requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"account_id\":\"" + ACCOUNT + "\"}")),
            201);

    assertThat(ContractSupport.componentSchema("UnmatchedTransaction").validate(queue.get(0)))
        .isEmpty();
    assertThat(ContractSupport.componentSchema("RefundRequest").validate(open.get(0))).isEmpty();
    assertThat(ContractSupport.componentSchema("RefundRequest").validate(done)).isEmpty();
    assertThat(ContractSupport.componentSchema("RefundRequest").validate(created)).isEmpty();
    assertThat(queue.get(0).get("candidate_codes").get(0).asString()).isEqualTo("TV234567");
  }

  @Test
  void webhook_acknowledgement_and_new_problems_match_the_spec_schema() throws Exception {
    when(sepayAuth.authenticate(any(), any(), any())).thenReturn(true);
    JsonNode ack =
        body(
            mvc.perform(
                post("/webhooks/sepay")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"id\":1,\"transferType\":\"out\"}")),
            200);
    assertThat(ContractSupport.componentSchema("WebhookAck").validate(ack)).isEmpty();

    when(sepayAuth.authenticate(any(), any(), any())).thenReturn(false);
    JsonNode unauthorized =
        body(
            mvc.perform(
                post("/webhooks/sepay").contentType(MediaType.APPLICATION_JSON).content("{}")),
            401);
    assertThat(ContractSupport.componentSchema("Problem").validate(unauthorized)).isEmpty();
    assertThat(unauthorized.toString()).doesNotContain("secret", "Apikey");

    when(refunds.request(any(UUID.class), any(UUID.class)))
        .thenThrow(new ConflictException("credits_used"));
    JsonNode conflict =
        body(
            mvc.perform(
                post("/orders/" + UUID.randomUUID() + "/refund-requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"account_id\":\"" + ACCOUNT + "\"}")),
            409);
    assertThat(ContractSupport.componentSchema("Problem").validate(conflict)).isEmpty();
    assertThat(conflict.get("reason").asString()).isEqualTo("credits_used");

    when(refunds.request(any(UUID.class), any(UUID.class)))
        .thenThrow(new UpstreamUnavailableException("identity-svc"));
    JsonNode unavailable =
        body(
            mvc.perform(
                post("/orders/" + UUID.randomUUID() + "/refund-requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"account_id\":\"" + ACCOUNT + "\"}")),
            503);
    assertThat(unavailable.get("type").asString()).isEqualTo("urn:problem:upstream-unavailable");
  }

  @Test
  void event_schema_v1_accepts_additive_fields_and_rejects_missing_required_ones() {
    String data =
        "\"order_id\":\"6f9619ff-8b86-d011-b42d-00c04fc964fe\",\"order_code\":\"TV234567\","
            + "\"account_id\":\"6f9619ff-8b86-d011-b42d-00c04fc964ff\",\"product_code\":\"CREDIT_5\","
            + "\"site\":\"tuvi\",\"amount\":\"119000.00\",\"currency\":\"VND\","
            + "\"provider\":\"SEPAY\",\"paid_at\":\"2026-11-25T10:00:00Z\"";
    String envelope =
        "{\"event_id\":\"6f9619ff-8b86-d011-b42d-00c04fc964fd\",\"event_type\":\"PaymentSucceeded\","
            + "\"occurred_at\":\"2026-11-25T10:00:00Z\",\"data\":{%s}}";

    assertThat(
            ContractSupport.eventSchema()
                .validate(JsonMapper.shared().readTree(envelope.formatted(data + ",\"extra\":1"))))
        .isEmpty();
    assertThat(
            ContractSupport.eventSchema()
                .validate(
                    JsonMapper.shared()
                        .readTree(envelope.formatted(data.replace("\"site\":\"tuvi\",", "")))))
        .isNotEmpty();
  }

  private static JsonNode body(ResultActions result, int status) throws Exception {
    var response = result.andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(status);
    return JsonMapper.shared().readTree(response.getContentAsString());
  }
}
