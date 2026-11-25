package dev.platform.payment.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.payment.application.CatalogEntry;
import dev.platform.payment.application.CatalogUseCase;
import dev.platform.payment.application.CreateOrderCommand;
import dev.platform.payment.application.CreateOrderUseCase;
import dev.platform.payment.application.CreatedOrder;
import dev.platform.payment.application.GetOrderUseCase;
import dev.platform.payment.application.OrderValidationException;
import dev.platform.payment.application.OrderView;
import dev.platform.payment.application.PaymentInstructions;
import dev.platform.payment.application.PaymentNotConfiguredException;
import dev.platform.payment.domain.Money;
import dev.platform.payment.domain.Order;
import dev.platform.payment.domain.OrderCode;
import dev.platform.payment.domain.Provider;
import dev.platform.starter.error.NotFoundException;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({OrderController.class, ProductController.class})
@Import({PlatformWebAutoConfiguration.class, PaymentExceptionHandler.class})
class OrderControllerTest {

  static final UUID ACCOUNT = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");
  static final Instant T0 = Instant.parse("2026-11-25T10:00:00Z");

  static final String VALID =
      "{\"account_id\":\"6f9619ff-8b86-d011-b42d-00c04fc964ff\",\"site\":\"tuvi\","
          + "\"product_code\":\"CREDIT_5\",\"currency\":\"VND\",\"provider\":\"SEPAY\","
          + "\"adult_confirmed\":true}";

  @Autowired MockMvc mvc;
  @MockitoBean CreateOrderUseCase createOrder;
  @MockitoBean GetOrderUseCase getOrder;
  @MockitoBean CatalogUseCase catalog;

  static Order order() {
    return Order.create(
        UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964fe"),
        new OrderCode("TV234567"),
        ACCOUNT,
        "tuvi",
        "CREDIT_5",
        null,
        Money.of("119000", "VND"),
        Provider.SEPAY,
        T0,
        Duration.ofMinutes(30));
  }

  @Test
  void post_returns_201_with_vietqr_payment() throws Exception {
    Order order = order();
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

    mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/orders/" + order.id()))
        .andExpect(jsonPath("$.order_id").value(order.id().toString()))
        .andExpect(jsonPath("$.code").value("TV234567"))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.amount").value("119000.00"))
        .andExpect(jsonPath("$.currency").value("VND"))
        .andExpect(jsonPath("$.provider").value("SEPAY"))
        .andExpect(jsonPath("$.expires_at").value("2026-11-25T10:30:00Z"))
        .andExpect(jsonPath("$.payment.kind").value("VIETQR"))
        .andExpect(jsonPath("$.payment.bank_code").value("local-test-bank"))
        .andExpect(jsonPath("$.payment.account_number").value("0000000000"))
        .andExpect(jsonPath("$.payment.account_name").value("TEST BANK"))
        .andExpect(jsonPath("$.payment.content").value("TV234567"))
        .andExpect(jsonPath("$.payment.amount").value("119000.00"))
        .andExpect(jsonPath("$.payment.approve_url").doesNotExist());
  }

  @Test
  void post_returns_paypal_payment() throws Exception {
    Order order = order();
    when(createOrder.create(any(CreateOrderCommand.class)))
        .thenReturn(
            new CreatedOrder(
                order,
                new PaymentInstructions.PayPal(
                    URI.create("https://paypal.fake.invalid/checkoutnow?token=FAKE-PP-1"))));

    mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.payment.kind").value("PAYPAL"))
        .andExpect(
            jsonPath("$.payment.approve_url")
                .value("https://paypal.fake.invalid/checkoutnow?token=FAKE-PP-1"))
        .andExpect(jsonPath("$.payment.bank_code").doesNotExist());
  }

  @Test
  void validation_exception_is_a_400_problem_with_the_field() throws Exception {
    when(createOrder.create(any(CreateOrderCommand.class)))
        .thenThrow(new OrderValidationException("adult_confirmed", "must be true"));

    mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:problem:validation-error"))
        .andExpect(jsonPath("$.errors[0].field").value("adult_confirmed"))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void malformed_body_is_a_400_on_body() throws Exception {
    mvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account_id\":\"x\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("body"));
    verifyNoInteractions(createOrder);
  }

  @Test
  void unconfigured_bank_is_a_503_problem() throws Exception {
    when(createOrder.create(any(CreateOrderCommand.class)))
        .thenThrow(new PaymentNotConfiguredException());

    mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.type").value("urn:problem:upstream-unavailable"));
  }

  @Test
  void get_returns_the_view_status_and_404_for_unknown() throws Exception {
    Order order = order();
    when(getOrder.get(order.id(), ACCOUNT)).thenReturn(new OrderView(order, "EXPIRED"));
    UUID other = UUID.randomUUID();
    when(getOrder.get(other, ACCOUNT)).thenThrow(new NotFoundException("order", other));

    mvc.perform(get("/orders/" + order.id()).param("account_id", ACCOUNT.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("EXPIRED"))
        .andExpect(jsonPath("$.payment").doesNotExist());
    mvc.perform(get("/orders/" + other).param("account_id", ACCOUNT.toString()))
        .andExpect(status().isNotFound());
    mvc.perform(get("/orders/" + other)).andExpect(status().isBadRequest());
  }

  @Test
  void list_returns_the_accounts_orders() throws Exception {
    when(getOrder.list(ACCOUNT)).thenReturn(List.of(new OrderView(order(), "PENDING")));

    mvc.perform(get("/orders").param("account_id", ACCOUNT.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("TV234567"));
  }

  @Test
  void products_lists_code_amount_currency() throws Exception {
    when(catalog.list("tuvi", "VND"))
        .thenReturn(List.of(new CatalogEntry("CREDIT_1", Money.of("29000", "VND"))));

    mvc.perform(get("/products").param("site", "tuvi").param("currency", "VND"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("CREDIT_1"))
        .andExpect(jsonPath("$[0].amount").value("29000.00"))
        .andExpect(jsonPath("$[0].currency").value("VND"));
  }
}
