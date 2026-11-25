package dev.platform.payment.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Guards the operator API: every path containing "admin" (deliberately broad, so a path that could
 * be normalized into /admin/** is guarded too) needs {@code X-Admin-Token} equal to {@code
 * ADMIN_API_TOKEN} (constant-time compare). With no token configured the admin API is closed. The
 * 401 is raised through the exception resolver so it has the platform problem shape and trace_id.
 */
public class AdminTokenFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Admin-Token";

  private final byte[] token;
  private final HandlerExceptionResolver resolver;

  public AdminTokenFilter(String token, HandlerExceptionResolver resolver) {
    this.token = token.getBytes(StandardCharsets.UTF_8);
    this.resolver = resolver;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().toLowerCase(Locale.ROOT).contains("admin");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String presented = request.getHeader(HEADER);
    if (token.length > 0
        && presented != null
        && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token)) {
      chain.doFilter(request, response);
      return;
    }
    resolver.resolveException(request, response, null, new UnauthenticatedException());
  }
}
