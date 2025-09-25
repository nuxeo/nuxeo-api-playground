package org.nuxeo.ecm.platform.playground;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

public class PlaygroundContentTypeRewriteFilter implements Filter {

    // (?i)           => case-insensitive
    // ^\\s*          => anchored at start, allow leading spaces (defensive)
    // (?=\\s*(;|$))  => lookahead: followed by optional spaces, then ';' or end
    protected String CONTENT_TYPE_NXREQUEST_REGEX = "(?i)^\\s*application/json\\+nxrequest(?=\\s*(;|$))";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {

        if (req instanceof HttpServletRequest http) {
            var ct = http.getHeader("Content-Type");

            if (ct != null) {
                // Replace only if the Content-Type starts with application/json+nxrequest
                var newCt = ct.replaceFirst(CONTENT_TYPE_NXREQUEST_REGEX, "application/json");

                if (!newCt.equals(ct)) {
                    // A change occurred — wrap so downstream sees the normalized type
                    var wrapped = new ContentTypeOverrideRequest(http, newCt);
                    chain.doFilter(wrapped, res);
                    return;
                }
            }
        }

        chain.doFilter(req, res);
    }

    // Wrapper that overrides Content-Type getters
    private static class ContentTypeOverrideRequest extends HttpServletRequestWrapper {

        private final String overriddenContentType;

        ContentTypeOverrideRequest(HttpServletRequest request, String overriddenContentType) {
            super(request);
            this.overriddenContentType = overriddenContentType;
        }

        @Override
        public String getContentType() {
            return overriddenContentType;
        }

        @Override
        public String getHeader(String name) {
            if ("Content-Type".equalsIgnoreCase(name)) {
                return overriddenContentType;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("Content-Type".equalsIgnoreCase(name)) {
                return Collections.enumeration(Collections.singletonList(overriddenContentType));
            }
            return super.getHeaders(name);
        }

    }
}

