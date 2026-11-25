package dev.platform.starter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service-to-service guard: every request outside {@code openPaths} must carry {@code
 * X-Internal-Token} equal to the configured token (constant-time compare), else a 401 {@code
 * application/problem+json} with {@code trace_id}. The token itself is never logged or echoed.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Internal-Token";

  private static final String DETAIL = "Missing or invalid internal token";

  private final byte[] token;
  private final List<String> openPaths;
  private final AntPathMatcher matcher = new AntPathMatcher();

  public InternalTokenFilter(String token, List<String> openPaths) {
    this.token = token.getBytes(StandardCharsets.UTF_8);
    this.openPaths = List.copyOf(openPaths);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    // Anything that could be normalized into a different path (dot segments, path params,
    // encoded characters, backslashes, empty segments) is never treated as open.
    if (path.contains("..")
        || path.contains(";")
        || path.contains("%")
        || path.contains("\\")
        || path.contains("//")) {
      return false;
    }
    return openPaths.stream().anyMatch(pattern -> matcher.match(pattern, path));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String presented = request.getHeader(HEADER);
    if (presented != null
        && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), token)) {
      chain.doFilter(request, response);
      return;
    }
    String body =
        "{\"type\":\"urn:problem:unauthenticated\",\"title\":\"Unauthorized\",\"status\":401,"
            + "\"detail\":\""
            + DETAIL
            + "\",\"trace_id\":\""
            + TraceIds.current()
            + "\"}";
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(body);
  }
}
