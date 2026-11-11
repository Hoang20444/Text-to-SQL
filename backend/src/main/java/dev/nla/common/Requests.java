package dev.nla.common;

import java.util.*;

/** Shared checks for JSON request bodies: no unknown fields, required fields present. */
public final class Requests {
  private Requests() {}

  public static void fields(Map<String, Object> body, Set<String> allowed) {
    if (!allowed.containsAll(body.keySet()))
      throw ApiException.bad("Yêu cầu chứa trường không được hỗ trợ.");
  }

  public static void required(Map<String, Object> body, String... fields) {
    for (String f : fields)
      if (Json.str(body, f).isBlank() || Json.str(body, f).length() > 2000)
        throw ApiException.bad("Thiếu hoặc sai trường " + f);
  }

  /** Connection details are visible to ADMIN only; secrets are never stored in documents. */
}
