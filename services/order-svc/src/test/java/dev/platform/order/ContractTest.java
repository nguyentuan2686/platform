package dev.platform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.networknt.schema.Error;
import dev.platform.order.adapter.in.web.OrderController;
import dev.platform.order.application.GetOrderUseCase;
import dev.platform.order.application.PlaceOrderUseCase;
import dev.platform.order.domain.Order;
import dev.platform.order.domain.OrderId;
import dev.platform.order.domain.OrderLine;
import dev.platform.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(OrderController.class)
class ContractTest {

  static final OrderId ID = OrderId.of("6f9619ff-8b86-d011-b42d-00c04fc964ff");

  @Autowired MockMvc mvc;
  @Autowired RequestMappingHandlerMapping mappings;
  @MockitoBean PlaceOrderUseCase placeOrder;
  @MockitoBean GetOrderUseCase getOrder;

  @Test
  void every_path_and_method_in_the_spec_is_served() {
    Set<String> served = new HashSet<>();
    for (Map.Entry<RequestMappingInfo, ?> e : mappings.getHandlerMethods().entrySet()) {
      Set<String> patterns = e.getKey().getPatternValues();
      for (RequestMethod m : e.getKey().getMethodsCondition().getMethods()) {
        patterns.forEach(p -> served.add(m + " " + p));
      }
    }

    Set<String> declared = new HashSet<>();
    JsonNode paths = ContractSupport.openApi().get("paths");
    for (String path : paths.propertyNames()) {
      for (String method : paths.get(path).propertyNames()) {
        declared.add(method.toUpperCase(Locale.ROOT) + " " + path);
      }
    }

    assertThat(declared).containsExactlyInAnyOrder("POST /orders", "GET /orders/{id}");
    assertThat(served).containsAll(declared);
  }

  @Test
  void order_response_matches_the_spec_schema() throws Exception {
    Order order =
        Order.restore(
            ID,
            "c-1",
            List.of(
                new OrderLine("sku-1", 2, new BigDecimal("12.75")),
                new OrderLine("sku-2", 1, new BigDecimal("0"))),
            OrderStatus.PLACED,
            Instant.parse("2026-11-23T10:00:00Z"));
    when(getOrder.get(ID)).thenReturn(order);

    String body = mvc.perform(get("/orders/" + ID)).andReturn().getResponse().getContentAsString();

    JsonNode json = JsonMapper.shared().readTree(body);
    List<Error> errors = ContractSupport.componentSchema("OrderResponse").validate(json);
    assertThat(errors).isEmpty();
  }

  @Test
  void schema_check_is_not_vacuous() {
    JsonNode bad = JsonMapper.shared().readTree("{\"id\":\"x\",\"status\":\"NOPE\"}");
    assertThat(ContractSupport.componentSchema("OrderResponse").validate(bad)).isNotEmpty();
  }
}
