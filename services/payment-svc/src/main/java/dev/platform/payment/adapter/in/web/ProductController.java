package dev.platform.payment.adapter.in.web;

import dev.platform.payment.application.CatalogUseCase;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {

  private final CatalogUseCase catalog;

  public ProductController(CatalogUseCase catalog) {
    this.catalog = catalog;
  }

  public record ProductResponse(String code, String amount, String currency) {}

  @GetMapping("/products")
  List<ProductResponse> products(@RequestParam String site, @RequestParam String currency) {
    return catalog.list(site, currency).stream()
        .map(e -> new ProductResponse(e.code(), e.price().toPlainString(), e.price().currency()))
        .toList();
  }
}
