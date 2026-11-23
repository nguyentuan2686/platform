package dev.platform.order.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.platform.order.application.GetOrderUseCase;
import dev.platform.order.application.PlaceOrderCommand;
import dev.platform.order.application.PlaceOrderUseCase;
import dev.platform.order.domain.Order;
import dev.platform.order.domain.OrderId;
import dev.platform.order.domain.OrderLine;
import dev.platform.order.domain.OrderStatus;
import dev.platform.starter.error.NotFoundException;
import dev.platform.starter.web.PlatformWebAutoConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@Import(PlatformWebAutoConfiguration.class)
class OrderControllerTest {

  static final OrderId ID = OrderId.of("6f9619ff-8b86-d011-b42d-00c04fc964ff");

  static final String VALID =
      "{\"customerId\":\"c-1\",\"items\":[{\"sku\":\"sku-1\",\"quantity\":2,\"unitPrice\":12.75}]}";

  @Autowired MockMvc mvc;
  @MockitoBean PlaceOrderUseCase placeOrder;
  @MockitoBean GetOrderUseCase getOrder;

  static Order sample() {
    return Order.restore(
        ID,
        "c-1",
        List.of(new OrderLine("sku-1", 2, new BigDecimal("12.75"))),
        OrderStatus.PLACED,
        Instant.parse("2026-11-23T10:00:00Z"));
  }

  static String request(String customerId, String item) {
    return "{\"customerId\":\"" + customerId + "\",\"items\":[" + item + "]}";
  }

  @Test
  void valid_post_returns_201_with_location_and_body() throws Exception {
    when(placeOrder.place(any(PlaceOrderCommand.class))).thenReturn(sample());

    mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/orders/" + ID))
        .andExpect(jsonPath("$.id").value(ID.toString()))
        .andExpect(jsonPath("$.customerId").value("c-1"))
        .andExpect(jsonPath("$.items[0].sku").value("sku-1"))
        .andExpect(jsonPath("$.total").value(25.50))
        .andExpect(jsonPath("$.status").value("PLACED"))
        .andExpect(jsonPath("$.createdAt").value("2026-11-23T10:00:00Z"));
  }

  @Test
  void empty_items_is_400_on_items() throws Exception {
    mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(request("c-1", "")))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errors[0].field").value("items"))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void zero_quantity_is_400_on_nested_field() throws Exception {
    String item = "{\"sku\":\"s\",\"quantity\":0,\"unitPrice\":1}";
    mvc.perform(
            post("/orders").contentType(MediaType.APPLICATION_JSON).content(request("c-1", item)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("items[0].quantity"));
  }

  @Test
  void quantity_above_10000_is_400() throws Exception {
    String item = "{\"sku\":\"s\",\"quantity\":10001,\"unitPrice\":1}";
    mvc.perform(
            post("/orders").contentType(MediaType.APPLICATION_JSON).content(request("c-1", item)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("items[0].quantity"));
  }

  @Test
  void quantity_of_10000_is_accepted() throws Exception {
    when(placeOrder.place(any(PlaceOrderCommand.class))).thenReturn(sample());
    String item = "{\"sku\":\"s\",\"quantity\":10000,\"unitPrice\":1}";
    mvc.perform(
            post("/orders").contentType(MediaType.APPLICATION_JSON).content(request("c-1", item)))
        .andExpect(status().isCreated());
  }

  @Test
  void negative_unit_price_is_400() throws Exception {
    String item = "{\"sku\":\"s\",\"quantity\":1,\"unitPrice\":-1}";
    mvc.perform(
            post("/orders").contentType(MediaType.APPLICATION_JSON).content(request("c-1", item)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("items[0].unitPrice"));
  }

  @Test
  void unit_price_with_three_decimals_is_400() throws Exception {
    String item = "{\"sku\":\"s\",\"quantity\":1,\"unitPrice\":10.505}";
    mvc.perform(
            post("/orders").contentType(MediaType.APPLICATION_JSON).content(request("c-1", item)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("items[0].unitPrice"));
  }

  @Test
  void unit_price_with_18_integer_digits_is_400() throws Exception {
    String item = "{\"sku\":\"s\",\"quantity\":1,\"unitPrice\":123456789012345678}";
    mvc.perform(
            post("/orders").contentType(MediaType.APPLICATION_JSON).content(request("c-1", item)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("items[0].unitPrice"));
  }

  @Test
  void blank_customer_is_400() throws Exception {
    String item = "{\"sku\":\"s\",\"quantity\":1,\"unitPrice\":1}";
    mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(request(" ", item)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("customerId"));
  }

  @Test
  void malformed_json_is_400() throws Exception {
    mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void get_returns_order() throws Exception {
    when(getOrder.get(ID)).thenReturn(sample());

    mvc.perform(get("/orders/" + ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ID.toString()))
        .andExpect(jsonPath("$.items[0].quantity").value(2));
  }

  @Test
  void get_unknown_is_404_problem() throws Exception {
    when(getOrder.get(ID)).thenThrow(new NotFoundException("order", ID));

    mvc.perform(get("/orders/" + ID))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.trace_id").exists());
  }

  @Test
  void get_with_non_uuid_id_is_400_problem() throws Exception {
    mvc.perform(get("/orders/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.trace_id").exists());
  }
}
