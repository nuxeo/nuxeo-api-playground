package org.nuxeo.ecm.platform.playground;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;


public class PlaygroundContentTypeRewriteFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        if (req instanceof HttpServletRequest http) {
            String ct = http.getHeader("Content-Type");

            if (ct != null) {
                String ctLower = ct.toLowerCase(Locale.ROOT).trim();
                if (ctLower.startsWith("application/json+nxrequest")) {
                    String params = "";
                    int semi = ct.indexOf(';');
                    if (semi >= 0) {
                        params = ct.substring(semi);
                    }
                    String newCt = "application/json" + params;
                    HttpServletRequest wrapped = new ContentTypeOverrideRequest(http, newCt);
                    chain.doFilter(wrapped, res);
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }

    private static class ContentTypeOverrideRequest extends HttpServletRequestWrapper {
        private final String overridden;

        ContentTypeOverrideRequest(HttpServletRequest request, String overridden) {
            super(request);
            this.overridden = overridden;
        }

        @Override
        public String getContentType() {
            return overridden;
        }

        @Override
        public String getHeader(String name) {
            return "Content-Type".equalsIgnoreCase(name) ? overridden : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("Content-Type".equalsIgnoreCase(name)) {
                return Collections.enumeration(Collections.singletonList(overridden));
            }
            return super.getHeaders(name);
        }
    }
}
