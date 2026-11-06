/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.valves.rewrite;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.Assert;
import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.servlets.DefaultServlet;
import org.apache.catalina.startup.TesterServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.catalina.valves.ValveBase;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.buf.UDecoder;

/*
 * Implementation note:
 *
 * A number of these tests involve the rewrite valve returning an HTTP Location
 * header that include un-encoded UTF-8 bytes. How the HTTP client handles these
 * depends on the default character encoding configured for the JVM running the
 * test. The tests expect the client to be configured with UTF-8 as the default
 * encoding. Use of any other encoding is likely to lead to test failures.
 */
public class TestRewriteValve extends TomcatBaseTest {

    @Test
    public void testNoRewrite() throws Exception {
        doTestRewrite("", "/a/%255A", "/a/%255A");
    }

    @Test
    public void testBackslashPercentSign() throws Exception {
        doTestRewrite("RewriteRule ^(.*) /a/\\%5A", "/", "/a/%5A");
    }

    @Test
    public void testNoopRewrite() throws Exception {
        doTestRewrite("RewriteRule ^(.*) $1", "/a/%255A", "/a/%255A");
    }

    @Test
    public void testPathRewrite() throws Exception {
        doTestRewrite("RewriteRule ^/b(.*) /a$1", "/b/%255A", "/a/%255A");
    }

    @Test
    public void testNonNormalizedPathRewrite() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /b/../a/$1", "/b/%255A", "/b/../a/%255A");
    }

    @Test
    public void testChainMatch() throws Exception {
        // The first rule matches so the chained (second) rule is applied to the result
        doTestRewrite("RewriteRule ^/a(.*) /b$1 [C]\n" +
                "RewriteRule ^/b(.*) /c$1", "/a/x", "/c/x");
    }

    @Test
    public void testChainHeadNoMatch() throws Exception {
        // The first rule does not match so the whole chain, including the
        // terminal rule that would otherwise match, must be skipped. If the
        // terminal rule were incorrectly applied the request would be rewritten
        // to /W/x.
        doTestRewrite("RewriteRule ^/never(.*) /c$1 [C]\n" +
                "RewriteRule ^/c(.*) /W$1", "/c/x", "/c/x");
    }

    @Test
    public void testChainMiddleNoMatch() throws Exception {
        // The first rule matches but the second (chained) rule does not, so the
        // rest of the chain, including the terminal rule, must be skipped and
        // the result of the first rule is retained. If the terminal rule were
        // incorrectly applied the request would be rewritten to /W/y.
        doTestRewrite("RewriteRule ^/a(.*) /c$1 [C]\n" +
                "RewriteRule ^/nomatch(.*) /x$1 [C]\n" +
                "RewriteRule ^/c(.*) /W$1", "/a/y", "/c/y");
    }

    @Test
    public void testNextRestartsFromFirstRule() throws Exception {
        // [N] must restart rule processing from the first rule. The first rule
        // only matches the output of the second rule, so it is applied only if
        // processing restarts at rule 0 (not rule 1).
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1\n" +
                "RewriteRule ^/a/(.*) /b/$1 [N]", "/a/x", "/c/x");
    }

    @Test
    public void testQsdDiscardsOriginalWithQsaRewrite() throws Exception {
        // [QSD] must discard the original query string even when a later
        // [QSA] rule matches and sets a new query string
        doTestRewrite("RewriteRule ^/a/(.*) /b/$1 [QSD]\n" +
                "RewriteRule ^/b/(.*) /c/$1?x=1 [QSA]", "/a/d?y=2", "/c/d", "x=1");
    }

    // BZ 57863
    @Test
    public void testRewriteMap01() throws Exception {
        doTestRewrite("RewriteMap mapa org.apache.catalina.valves.rewrite.TesterRewriteMapA\n" +
                "RewriteRule /b/(.*).html$ /c/${mapa:$1}", "/b/a.html", "/c/aa");
    }

    @Test
    public void testRewriteMap02() throws Exception {
        doTestRewrite("RewriteMap mapa org.apache.catalina.valves.rewrite.TesterRewriteMapA\n" +
                "RewriteRule /b/(.*).html$ /c/${mapa:$1|dd}", "/b/x.html", "/c/dd");
    }

    // BZ 62667
    @Test
    public void testRewriteMap03() throws Exception {
        doTestRewrite("RewriteMap mapa org.apache.catalina.valves.rewrite.TesterRewriteMapA\n" +
                "RewriteRule /b/(.*).html$ /c/${mapa:$1|d$1d}", "/b/x.html", "/c/dxd");
    }

    @Test
    public void testRewriteMap04() throws Exception {
        doTestRewrite("RewriteMap mapa org.apache.catalina.valves.rewrite.TesterRewriteMapA\n" +
                "RewriteRule /b/(.*).html$ /c/${mapa:a$1|dd}", "/b/a.html", "/c/aaaa");
    }

    @Test
    public void testRewriteMap05() throws Exception {
        doTestRewrite("RewriteMap mapa org.apache.catalina.valves.rewrite.TesterRewriteMapA\n" +
                "RewriteRule /b/.* /c/${mapa:a}", "/b/a.html", "/c/aa");
    }

    @Test
    public void testRewriteMap06() throws Exception {
        doTestRewrite("RewriteMap mapa org.apache.catalina.valves.rewrite.TesterRewriteMapA\n" +
                "RewriteRule /b/.* /c/${mapa:${mapa:a}}", "/b/a.html", "/c/aaaa");
    }

    @Test
    public void testRewriteMap07() throws Exception {
        doTestRewrite("RewriteMap mapa org.apache.catalina.valves.rewrite.TesterRewriteMapA foo bar\n" +
                "RewriteRule /b/.* /c/${mapa:${mapa:a}}", "/b/a.html", "/c/aaaa");
    }

    @Test
    public void testRewriteMap08() throws Exception {
        doTestRewrite("RewriteMap lc int:tolower\n" +
                "RewriteRule ^(.*) ${lc:$1}", "/C/AaA", "/c/aaa");
    }

    @Test
    public void testRewriteServerVar() throws Exception {
        doTestRewrite("RewriteRule /b/(.*).html$ /c%{SERVLET_PATH}", "/b/x.html", "/c/b/x.html");
    }

    @Test
    public void testRewriteEnvVarAndServerVar() throws Exception {
        System.setProperty("some_variable", "something");
        doTestRewrite("RewriteRule /b/(.*).html$ /c/%{ENV:some_variable}%{SERVLET_PATH}",
                "/b/x.html", "/c/something/b/x.html");
    }

    @Test
    public void testRewriteServerVarAndEnvVar() throws Exception {
        System.setProperty("some_variable", "something");
        doTestRewrite("RewriteRule /b/(.*).html$ /c%{SERVLET_PATH}/%{ENV:some_variable}",
                "/b/x.html", "/c/b/x.html/something");
    }

    @Test
    public void testRewriteMissingCurlyBraceOnVar() throws Exception {
        try {
            doTestRewrite("RewriteRule /b/(.*).html$ /c%_{SERVLET_PATH}", "/b/x.html", "/c");
            Assert.fail("IAE expected.");
        } catch (java.lang.IllegalArgumentException e) {
            // expected as %_{ is invalid
        }
    }

    @Test
    public void testRewriteMissingCurlyBraceOnMapper() throws Exception {
        try {
            doTestRewrite("RewriteRule /b/(.*).html$ /c$_{SERVLET_PATH}", "/b/x.html", "/c");
            Assert.fail("IAE expected.");
        } catch (java.lang.IllegalArgumentException e) {
            // expected as $_{ is invalid
        }
    }

    // https://bz.apache.org/bugzilla/show_bug.cgi?id=60013
    public void testRewriteWithEncoding02() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*)$ /c/?param=$1 [L]",
                "/b/%E5%9C%A8%E7%BA%BF%E6%B5%8B%E8%AF%95", "/c/", "param=\u5728\u7EBF\u6D4B\u8BD5");
    }

    @Test
    public void testNonAsciiPath() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1", "/b/%E5%9C%A8%E7%BA%BF%E6%B5%8B%E8%AF%95",
                "/c/%E5%9C%A8%E7%BA%BF%E6%B5%8B%E8%AF%95");
    }

    @Test
    public void testNonAsciiPathRedirect() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1 [R]",
                "/b/%E5%9C%A8%E7%BA%BF%E6%B5%8B%E8%AF%95",
                "/c/%E5%9C%A8%E7%BA%BF%E6%B5%8B%E8%AF%95");
    }

    @Test
    public void testQueryStringTargetOnly() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1?je=2", "/b/id=1", "/c/id=1", "je=2");
    }

    @Test
    public void testQueryStringTargetOnlyQSA() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1?je=2 [QSA]", "/b/id=1", "/c/id=1", "je=2");
    }

    @Test
    public void testQueryStringTargetOnlyQSD() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1?je=2 [QSD]", "/b/id=1", "/c/id=1", "je=2");
    }

    @Test
    public void testQueryStringTargetOnlyQSAQSD() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1?je=2 [QSA,QSD]", "/b/id=1", "/c/id=1", "je=2");
    }

    @Test
    public void testQueryStringTargetOnlyQS() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c?$1", "/b/id=1", "/c", "id=1");
    }

    @Test
    public void testQueryStringTargetOnlyQSAQS() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c?$1 [QSA]", "/b/id=1", "/c", "id=1");
    }

    @Test
    public void testQueryStringTargetOnlyQSDQS() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c?$1 [QSD]", "/b/id=1", "/c", "id=1");
    }

    @Test
    public void testQueryStringTargetOnlyQSAQSDQS() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c?$1 [QSA,QSD]", "/b/id=1", "/c", "id=1");
    }

    @Test
    public void testQueryStringSourceOnly() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1", "/b/d?id=1", "/c/d", "id=1");
    }

    @Test
    public void testQueryStringSourceOnlyQSA() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1 [QSA]", "/b/d?id=1", "/c/d", "id=1");
    }

    @Test
    public void testQueryStringSourceOnlyQSD() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1 [QSD]", "/b/d?id=1", "/c/d", null);
    }

    @Test
    public void testQueryStringSourceOnlyQSAQSD() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1 [QSA,QSD]", "/b/d?id=1", "/c/d", null);
    }

    @Test
    public void testQueryStringSourceAndTarget() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1?id=1", "/b/d?je=2", "/c/d", "id=1");
    }

    @Test
    public void testQueryStringSourceAndTargetQSA() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1?id=1 [QSA]", "/b/d?je=2", "/c/d", "id=1&je=2");
    }

    @Test
    public void testQueryStringSourceAndTargetQSD() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1?id=1 [QSD]", "/b/d?je=2", "/c/d", "id=1");
    }

    @Test
    public void testQueryStringSourceAndTargetQSAQSD() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1?id=1 [QSA,QSD]", "/b/d?je=2", "/c/d", "id=1");
    }

    @Test
    public void testQueryStringEncoded01() throws Exception {
        doTestRewrite("RewriteCond %{QUERY_STRING} a=(.*)\nRewriteRule ^/b.*$ /%1 [QSD]", "/b?a=c", "/c", null);
    }

    @Test
    public void testQueryStringEncoded02() throws Exception {
        doTestRewrite("RewriteCond %{QUERY_STRING} a=(.*)\nRewriteRule ^/b.*$ /z/%1 [QSD]",
                "/b?a=%2e%2e%2fc%2faAbB", "/z/%2e%2e%2fc%2faAbB", null);
    }

    /*
     * The advisory for CVE-2025-55752 describes a directory traversal (and,
     * where PUT is enabled, remote code execution) triggered by a rewrite rule
     * that inserts attacker controlled data - typically taken from the query
     * string - into the rewritten URL. In the affected versions the rewritten
     * URL was normalized *before* it was decoded so any ".." sequences created
     * by that decoding were still present in the decoded URI handed to the
     * Mapper and, from there, to the DefaultServlet.
     *
     * Since the fix for CVE-2025-31651 the rewritten URL is decoded after
     * rewriting (%nn sequences inserted by a rule are honoured). It is decoded
     * *before* it is normalized so any ".." created by that decoding is
     * resolved by the normalization (or rejected with a 400 if it would escape
     * the web application) and never reaches the Mapper, the /WEB-INF and
     * /META-INF checks or the DefaultServlet.
     */
    @Test
    public void testQueryStringTraversalWebInf() throws Exception {
        // %2e%2e%2f == ../
        doTestTraversalBlocked("/b?a=%2e%2e%2fWEB-INF%2fweb.xml", "<web-app");
    }

    @Test
    public void testQueryStringTraversalWebInfDoubleEncoded() throws Exception {
        // Guards against a double decode of the rewritten URL
        doTestTraversalBlocked("/b?a=%252e%252e%252fWEB-INF%252fweb.xml", "<web-app");
    }

    @Test
    public void testQueryStringTraversalWebInfNotEncoded() throws Exception {
        // Normalization has to happen before the /WEB-INF check in
        // StandardContextValve, i.e. no ".." may survive into the decoded URI
        doTestTraversalBlocked("/b?a=../WEB-INF/web.xml", "<web-app");
    }

    @Test
    public void testQueryStringTraversalStaticFile() throws Exception {
        /*
         * "/z/%2e%2e%2findex.html" is decoded to "/z/../index.html" and then
         * normalized to "/index.html", a resource *inside* the web application
         * (the same result as testQueryStringTraversalNotEncodedIsNormalized).
         */
        ByteChunk res = new ByteChunk();
        int rc = doTestTraversal("/b?a=%2e%2e%2findex.html", res);
        Assert.assertEquals(HttpURLConnection.HTTP_OK, rc);
        Assert.assertTrue(res.toString().contains("Index page"));
    }

    @Test
    public void testQueryStringTraversalStaticFileAboveRoot() throws Exception {
        // Decoded then normalized, this would escape the web application
        ByteChunk res = new ByteChunk();
        int rc = doTestTraversal("/b?a=%2e%2e%2f%2e%2e%2findex.html", res);
        String body = res.toString();
        Assert.assertFalse(body, body != null && body.contains("Index page"));
        Assert.assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, rc);
    }

    @Test
    public void testQueryStringTraversalNotEncodedIsNormalized() throws Exception {
        /*
         * Nothing is encoded here so the ".." is part of the (already decoded)
         * rewritten URL and is resolved by the normalization that follows the
         * rewrite. "/z/../index.html" therefore becomes "/index.html" which is
         * a valid, publicly accessible resource *inside* the web application.
         *
         * This is the documented behaviour for a rewrite that denormalizes the
         * URL (compare testNonNormalizedPathRewrite) and it is not the
         * CVE-2025-55752 traversal: the ".." never reaches the Mapper or the
         * /WEB-INF and /META-INF checks. It is asserted here to show that the
         * ".." really is resolved rather than passed through - the request is
         * mapped to "/index.html", not to the non-existent literal path.
         */
        ByteChunk res = new ByteChunk();
        int rc = doTestTraversal("/b?a=../index.html", res);
        Assert.assertEquals(HttpURLConnection.HTTP_OK, rc);
        Assert.assertTrue(res.toString().contains("Index page"));
    }

    private void doTestTraversalBlocked(String request, String forbiddenContent) throws Exception {

        ByteChunk res = new ByteChunk();
        int rc = doTestTraversal(request, res);

        String body = res.toString();
        if (body == null) {
            body = "";
        }
        Assert.assertFalse("Traversal succeeded for [" + request + "]. Response code [" + rc +
                "], body [" + body + "]", body.contains(forbiddenContent));
        Assert.assertEquals("Unexpected response code for [" + request + "]",
                HttpURLConnection.HTTP_NOT_FOUND, rc);
    }

    private int doTestTraversal(String request, ByteChunk res) throws Exception {

        Tomcat tomcat = getTomcatInstance();

        // A real docBase is required so the DefaultServlet has something to serve
        File appDir = new File("test/webapp");
        Context ctx = tomcat.addContext("", appDir.getAbsolutePath());

        RewriteValve rewriteValve = new RewriteValve();
        ctx.getPipeline().addValve(rewriteValve);

        // Insert the (attacker controlled) query string into the rewritten URL
        rewriteValve.setConfiguration("RewriteCond %{QUERY_STRING} a=(.*)\n" +
                "RewriteRule ^/b.*$ /z/%1 [QSD]");

        Tomcat.addServlet(ctx, "default", new DefaultServlet());
        ctx.addServletMappingDecoded("/", "default");

        tomcat.start();

        // Control. Confirm the DefaultServlet can serve content from the docBase
        // so that a 404 for the traversal attempts is meaningful.
        ByteChunk control = new ByteChunk();
        int controlRc = getUrl("http://localhost:" + getPort() + "/index.html", control, null);
        control.setCharset(StandardCharsets.UTF_8);
        Assert.assertEquals(HttpURLConnection.HTTP_OK, controlRc);
        Assert.assertTrue(control.toString().contains("Index page"));

        int rc = getUrl("http://localhost:" + getPort() + request, res, null);
        res.setCharset(StandardCharsets.UTF_8);
        return rc;
    }

    @Test
    public void testQueryStringRemove() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1?", "/b/d?id=1", "/c/d", null);
    }

    @Test
    public void testQueryStringRemove02() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1 [QSD]", "/b/d?id=1", "/c/d", null);
    }

    @Test
    public void testQueryStringRemoveInvalid() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1?", "/b/d?=1", "/c/d", null);
    }

    @Test
    public void testQueryStringRemoveInvalid02() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c/$1 [QSD]", "/b/d?=1", "/c/d", null);
    }

    @Test
    public void testNonAsciiQueryString() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c?$1",
                "/b/id=%E5%9C%A8%E7%BA%BF%E6%B5%8B%E8%AF%95",
                "/c", "id=%E5%9C%A8%E7%BA%BF%E6%B5%8B%E8%AF%95");
    }


    @Test
    public void testNonAsciiQueryStringAndPath() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/$1?$2",
                "/b/%E5%9C%A8%E7%BA%BF/id=%E6%B5%8B%E8%AF%95",
                "/c/%E5%9C%A8%E7%BA%BF", "id=%E6%B5%8B%E8%AF%95");
    }


    @Test
    public void testNonAsciiQueryStringAndRedirect() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*) /c?$1 [R]",
                "/b/id=%E5%9C%A8%E7%BA%BF%E6%B5%8B%E8%AF%95",
                "/c", "id=%E5%9C%A8%E7%BA%BF%E6%B5%8B%E8%AF%95");
    }


    @Test
    public void testNonAsciiQueryStringAndPathAndRedirect() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/$1?$2 [R]",
                "/b/%E5%9C%A8%E7%BA%BF/id=%E6%B5%8B%E8%AF%95",
                "/c/%E5%9C%A8%E7%BA%BF", "id=%E6%B5%8B%E8%AF%95");
    }


    @Test
    public void testNonAsciiQueryStringWithB() throws Exception {
        doTestRewrite("RewriteRule ^/b/(.*)/id=(.*) /c?filename=$1&id=$2 [B]",
                "/b/file01/id=%E5%9C%A8%E7%BA%BF%E6%B5%8B%E8%AF%95", "/c",
                "filename=file01&id=%E5%9C%A8%E7%BA%BF%E6%B5%8B%E8%AF%95");
    }


    @Test
    public void testNonAsciiQueryStringAndPathAndRedirectWithB() throws Exception {
        // Note the double encoding of the result (httpd produces the same result)
        doTestRewrite("RewriteRule ^/b/(.*)/(.*)/id=(.*) /c/$1?filename=$2&id=$3 [B,R]",
                "/b/%E5%9C%A8%E7%BA%BF/file01/id=%E6%B5%8B%E8%AF%95",
                "/c/%E5%9C%A8%E7%BA%BF",
                "filename=file01&id=%E6%B5%8B%E8%AF%95");
    }


    @Test
    public void testUtf8WithBothQsFlagsNone() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithBothQsFlagsB() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [B]",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithBothQsFlagsR() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R]",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithBothQsFlagsRB() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R,B]",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithBothQsFlagsRNE() throws Exception {
        // Note %C2%A1 == \u00A1
        // Failing to escape the redirect means UTF-8 bytes in the Location
        // header which will be treated as if they are ISO-8859-1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R,NE]",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", null);
    }


    @Test
    public void testUtf8WithBothQsFlagsRBNE() throws Exception {
        // Note %C2%A1 == \u00A1
        // Failing to escape the redirect means UTF-8 bytes in the Location
        // header which will be treated as if they are ISO-8859-1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R,B,NE]",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", null);
    }


    @Test
    public void testUtf8WithBothQsFlagsBQSA() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [B,QSA]",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", "/c/%C2%A1%C2%A1",
                "id=%C2%A1&di=%C2%AE");
    }


    @Test
    public void testUtf8WithBothQsFlagsRQSA() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R,QSA]",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", "/c/%C2%A1%C2%A1",
                "id=%C2%A1&di=%C2%AE");
    }


    @Test
    public void testUtf8WithBothQsFlagsRBQSA() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R,B,QSA]",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", "/c/%C2%A1%C2%A1",
                "id=%C2%A1&di=%C2%AE");
    }


    @Test
    public void testUtf8WithBothQsFlagsRNEQSA() throws Exception {
        // Note %C2%A1 == \u00A1
        // Failing to escape the redirect means UTF-8 bytes in the Location
        // header which will be treated as if they are ISO-8859-1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R,NE,QSA]",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", null);
    }


    @Test
    public void testUtf8WithBothQsFlagsRBNEQSA() throws Exception {
        // Note %C2%A1 == \u00A1
        // Failing to escape the redirect means UTF-8 bytes in the Location
        // header which will be treated as if they are ISO-8859-1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R,B,NE,QSA]",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", null);
    }


    @Test
    public void testUtf8WithOriginalQsFlagsNone() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1",
                "/b/%C2%A1?id=%C2%A1", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithOriginalQsFlagsB() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1 [B]",
                "/b/%C2%A1?id=%C2%A1", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithOriginalQsFlagsR() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1 [R]",
                "/b/%C2%A1?id=%C2%A1", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithOriginalQsFlagsRB() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1 [R,B]",
                "/b/%C2%A1?id=%C2%A1", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithOriginalQsFlagsRNE() throws Exception {
        // Note %C2%A1 == \u00A1
        // Failing to escape the redirect means UTF-8 bytes in the Location
        // header which will be treated as if they are ISO-8859-1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1 [R,NE]",
                "/b/%C2%A1?id=%C2%A1", null);
    }


    @Test
    public void testUtf8WithOriginalQsFlagsRBNE() throws Exception {
        // Note %C2%A1 == \u00A1
        // Failing to escape the redirect means UTF-8 bytes in the Location
        // header which will be treated as if they are ISO-8859-1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1 [R,B,NE]",
                "/b/%C2%A1?id=%C2%A1", null);
    }


    @Test
    public void testUtf8WithRewriteQsFlagsNone() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2",
                "/b/%C2%A1/id=%C2%A1", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithRewriteQsFlagsB() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [B]",
                "/b/%C2%A1/id=%C2%A1", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithRewriteQsFlagsR() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R]",
                "/b/%C2%A1/id=%C2%A1", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithBothQsFlagsQSA() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [QSA]",
                "/b/%C2%A1/id=%C2%A1?di=%C2%AE", "/c/%C2%A1%C2%A1",
                "id=%C2%A1&di=%C2%AE");
    }


    @Test
    public void testUtf8WithRewriteQsFlagsRB() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R,B]",
                "/b/%C2%A1/id=%C2%A1", "/c/%C2%A1%C2%A1", "id=%C2%A1");
    }


    @Test
    public void testUtf8WithRewriteQsFlagsRNE() throws Exception {
        // Note %C2%A1 == \u00A1
        // Failing to escape the redirect means UTF-8 bytes in the Location
        // header which will be treated as if they are ISO-8859-1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R,NE]",
                "/b/%C2%A1/id=%C2%A1", null);
    }


    @Test
    public void testUtf8WithRewriteQsFlagsRBNE() throws Exception {
        // Note %C2%A1 == \u00A1
        // Failing to escape the redirect means UTF-8 bytes in the Location
        // header which will be treated as if they are ISO-8859-1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [R,B,NE]",
                "/b/%C2%A1/id=%C2%A1", null);
    }


    @Test
    public void testUtf8WithRewriteQsFlagsQSA() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*)/(.*) /c/\u00A1$1?$2 [QSA]",
                "/b/%C2%A1/id=%C2%A1", "/c/%C2%A1%C2%A1",
                "id=%C2%A1");
    }


    @Test
    public void testUtf8FlagsNone() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1", "/b/%C2%A1", "/c/%C2%A1%C2%A1");
    }


    @Test
    public void testUtf8FlagsB() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1 [B]", "/b/%C2%A1", "/c/%C2%A1%C2%A1");
    }


    @Test
    public void testUtf8FlagsR() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1 [R]", "/b/%C2%A1", "/c/%C2%A1%C2%A1");
    }


    @Test
    public void testUtf8FlagsRB() throws Exception {
        // Note %C2%A1 == \u00A1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1 [R,B]", "/b/%C2%A1", "/c/%C2%A1%C2%A1");
    }


    @Test
    public void testUtf8FlagsRNE() throws Exception {
        // Note %C2%A1 == \u00A1
        // Failing to escape the redirect means UTF-8 bytes in the Location
        // header which will be treated as if they are ISO-8859-1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1 [R,NE]", "/b/%C2%A1", null);
    }


    @Test
    public void testUtf8FlagsRBNE() throws Exception {
        // Note %C2%A1 == \u00A1
        // Failing to escape the redirect means UTF-8 bytes in the Location
        // header which will be treated as if they are ISO-8859-1
        doTestRewrite("RewriteRule ^/b/(.*) /c/\u00A1$1 [R,B,NE]", "/b/%C2%A1", null);
    }


    @Test
    public void testFlagsNC() throws Exception {
        // https://bz.apache.org/bugzilla/show_bug.cgi?id=60116
        doTestRewrite("RewriteCond %{QUERY_STRING} a=([a-z]*) [NC]\n"
                + "RewriteRule .* - [E=X-Test:%1]",
                    "/c?a=aAa", "/c", "a=aAa", "aAa");
    }


    @Test
    public void testHostRewrite() throws Exception {
        // Based on report from users list that ':' was encoded and breaking
        // the redirect
        doTestRewrite("RewriteRule ^/b(.*) http://%{HTTP_HOST}:%{SERVER_PORT}/a$1 [R]",
                "/b/%255A", "/a/%255A");
    }


    @Test
    public void testDefaultRedirect() throws Exception {
        doTestRedirect("RewriteRule ^/from/a$ /to/b [R]", "/redirect/from/a", "/redirect/to/b",
                302);
    }


    @Test
    public void testTempRedirect() throws Exception {
        doTestRedirect("RewriteRule ^/from/a$ /to/b [R=temp]", "/redirect/from/a", "/redirect/to/b",
                302);
    }


    @Test
    public void testPermanentRedirect() throws Exception {
         // Disable the following of redirects for this test only
        boolean originalValue = HttpURLConnection.getFollowRedirects();
        HttpURLConnection.setFollowRedirects(false);
        try {
            doTestRedirect("RewriteRule ^/from/a$ /to/b [R=permanent]", "/redirect/from/a", "/redirect/to/b",
                301);
        } finally {
            HttpURLConnection.setFollowRedirects(originalValue);
        }
    }


    @Test
    public void testSeeotherRedirect() throws Exception {
         // Disable the following of redirects for this test only
        boolean originalValue = HttpURLConnection.getFollowRedirects();
        HttpURLConnection.setFollowRedirects(false);
        try {
            doTestRedirect("RewriteRule ^/from/a$ /to/b [R=seeother]", "/redirect/from/a", "/redirect/to/b",
                303);
        } finally {
            HttpURLConnection.setFollowRedirects(originalValue);
        }
    }


    @Test
    public void test307Redirect() throws Exception {
         // Disable the following of redirects for this test only
        boolean originalValue = HttpURLConnection.getFollowRedirects();
        HttpURLConnection.setFollowRedirects(false);
        try {
            doTestRedirect("RewriteRule ^/from/a$ /to/b [R=307]", "/redirect/from/a", "/redirect/to/b",
                307);
        } finally {
            HttpURLConnection.setFollowRedirects(originalValue);
        }
    }


    @Test
    public void testBackReferenceRewrite() throws Exception {
        doTestRewrite("RewriteRule ^/b/(rest)?$ /c/$1", "/b/rest", "/c/rest");
    }


    @Test
    public void testEmptyBackReferenceRewrite() throws Exception {
        doTestRewrite("RewriteRule ^/b/(rest)?$ /c/$1", "/b/", "/c/");
    }


    @Test
    public void testNegativePattern01() throws Exception {
        doTestRewrite("RewriteRule !^/b/.* /c/", "/b", "/c/");
    }


    @Test
    public void testNegativePattern02() throws Exception {
        doTestRewrite("RewriteRule !^/b/.* /c/", "/d/e/f", "/c/");
    }


    @Test
    public void testNegativePattern03() throws Exception {
        doTestRewrite("RewriteRule !^/c/.* /b/", "/c/", "/c/");
    }


    @Test
    public void testNegativePattern04() throws Exception {
        doTestRewrite("RewriteRule !^/c/.* /b/", "/c/d", "/c/d");
    }

    @Test
    public void testMultiLine001() throws Exception {
        doTestRewrite("RewriteRule /dummy /anotherDummy [L]\nRewriteRule ^/a /c [L]", "/a", "/c");
    }

    @Test
    public void testMultiLine002() throws Exception {
        doTestRewrite("RewriteRule /dummy /a\nRewriteRule /a /c [L]", "/dummy", "/c");
    }

    @Test
    public void testOrCondDoesNotSkipNonOrCond() throws Exception {
        // The matched [OR] condition only covers the condition after it. The
        // ^/admin condition must still be evaluated, so the rule is not applied
        // to /c/other and no redirect is issued.
        doTestRewrite("RewriteCond %{HTTP_HOST} localhost [OR]\n" +
                "RewriteCond %{HTTP_HOST} 127.0.0.1\n" +
                "RewriteCond %{REQUEST_URI} ^/admin\n" +
                "RewriteRule ^(.*) /blocked$1 [R]", "/c/other", "/c/other");
    }


    @Test
    public void testOrCondRewritesWhenAllConditionsMatch() throws Exception {
        // Guard for testOrCondDoesNotSkipNonOrCond: a satisfied OR group and a
        // satisfied trailing condition still apply the rule.
        doTestRewrite("RewriteCond %{HTTP_HOST} nomatch [OR]\n" +
                "RewriteCond %{HTTP_HOST} localhost\n" +
                "RewriteCond %{REQUEST_URI} ^/c/.*\n" +
                "RewriteRule ^/c/(.*) /z/$1", "/c/other", "/z/other");
    }


    private void doTestRewrite(String config, String request, String expectedURI) throws Exception {
        doTestRewrite(config, request, expectedURI, null);
    }


    private void doTestRewrite(String config, String request, String expectedURI,
            String expectedQueryString) throws Exception {
        doTestRewrite(config, request, expectedURI, expectedQueryString, null);
    }


    private void doTestRewrite(String config, String request, String expectedURI,
            String expectedQueryString, String expectedAttributeValue) throws Exception {

        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        RewriteValve rewriteValve = new RewriteValve();
        ctx.getPipeline().addValve(rewriteValve);

        rewriteValve.setConfiguration(config);

        Tomcat.addServlet(ctx, "snoop", new SnoopServlet());
        ctx.addServletMappingDecoded("/a/Z", "snoop");
        ctx.addServletMappingDecoded("/a/%5A", "snoop");
        ctx.addServletMappingDecoded("/c/*", "snoop");
        ctx.addServletMappingDecoded("/z/*", "snoop");
        Tomcat.addServlet(ctx, "default", new DefaultServlet());
        ctx.addServletMappingDecoded("/", "default");

        tomcat.start();

        ByteChunk res = new ByteChunk();
        int rc = getUrl("http://localhost:" + getPort() + request, res, null);
        res.setCharset(StandardCharsets.UTF_8);

        if (expectedURI == null) {
            // Rewrite is expected to fail. Probably because invalid characters
            // were written into the request target
            Assert.assertEquals(400, rc);
        } else {
            // If there is an expected URI, the request should be successful
            Assert.assertEquals(200, rc);
            String body = res.toString();
            RequestDescriptor requestDesc = SnoopResult.parse(body);
            String requestURI = requestDesc.getRequestInfo("REQUEST-URI");
            Assert.assertEquals(expectedURI, requestURI);

            if (expectedQueryString == null) {
                Assert.assertTrue(requestDesc.getParams().isEmpty());
            } else {
                String queryString = requestDesc.getRequestInfo("REQUEST-QUERY-STRING");
                Assert.assertEquals(expectedQueryString, queryString);
            }

            if (expectedAttributeValue != null) {
                String attributeValue = requestDesc.getAttribute("X-Test");
                Assert.assertEquals(expectedAttributeValue, attributeValue);
            }
        }
    }

    /*
     * CVE-2025-55668 - Session fixation in the rewrite valve.
     *
     * When the rewrite valve issues a redirect ([R] flag), the redirect Location
     * must be passed through Response.encodeRedirectURL() so that, when the
     * container is tracking the session via the URL (cookies not usable by the
     * client), the session id is encoded into the Location as a ;jsessionid=
     * path parameter rather than allowing an attacker-supplied session id to be
     * silently reused across the redirect.
     *
     * This test drives a first request that creates a session (returning the id
     * in the URL because the client does not accept cookies), then a second
     * request to a rewrite [R] redirect rule carrying that session id as a URL
     * path parameter. The redirect Location must contain the ;jsessionid= path
     * parameter, proving the redirect went through encodeRedirectURL().
     */
    @Test
    public void testRedirectEncodesSessionId() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("redirect", null);
        // The default context supports both COOKIE and URL session tracking.
        // Because the test client below does not return a session cookie, the
        // session id must be tracked via the URL and therefore encoded into
        // both generated URLs and redirect Location headers.

        RewriteValve rewriteValve = new RewriteValve();
        ctx.getPipeline().addValve(rewriteValve);

        rewriteValve.setConfiguration("RewriteRule ^/from/a$ /to/b [R]");

        Tomcat.addServlet(ctx, "session", new SessionCreatingServlet());
        ctx.addServletMappingDecoded("/to/b", "session");

        // Disable following of redirects so we can inspect the Location header
        boolean originalValue = HttpURLConnection.getFollowRedirects();
        HttpURLConnection.setFollowRedirects(false);
        try {
            tomcat.start();

            // First request: create a session. Because the client does not send
            // the session id back via a cookie, the response encodes it in the
            // body via encodeURL().
            ByteChunk res1 = new ByteChunk();
            int rc1 = getUrl("http://localhost:" + getPort() + "/redirect/to/b", res1, null);
            Assert.assertEquals(200, rc1);
            res1.setCharset(StandardCharsets.UTF_8);
            String body = res1.toString();
            int idx = body.indexOf(";jsessionid=");
            Assert.assertTrue("No session id encoded in first response: " + body, idx != -1);
            String sessionId = body.substring(idx + ";jsessionid=".length()).trim();

            // Second request: hit the [R] redirect rule carrying the session id
            // as a URL path parameter. The redirect Location must be encoded
            // with the session id, proving encodeRedirectURL() was applied.
            ByteChunk res2 = new ByteChunk();
            Map<String, List<String>> resHead = new HashMap<>();
            int rc2 = methodUrl(
                    "http://localhost:" + getPort() + "/redirect/from/a;jsessionid=" + sessionId,
                    res2, DEFAULT_CLIENT_TIMEOUT_MS, null, resHead, "GET", false);
            Assert.assertEquals(302, rc2);
            List<String> locations = resHead.get("Location");
            Assert.assertNotNull(locations);
            Assert.assertFalse(locations.isEmpty());
            String redirectURI = locations.get(0);
            Assert.assertTrue("Redirect Location did not encode the session id: " + redirectURI,
                    redirectURI.contains(";jsessionid=" + sessionId));
        } finally {
            HttpURLConnection.setFollowRedirects(originalValue);
        }
    }


    private static class SessionCreatingServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            // Ensure a session exists so it can be encoded into subsequent URLs
            req.getSession(true);
            resp.setContentType("text/plain");
            // Encode a URL so the client learns the session id when cookies are
            // not in use (URL session tracking).
            resp.getWriter().print(resp.encodeURL("/redirect/to/b"));
        }
    }


    private void doTestRedirect(String config, String request, String expectedURI,
        int expectedStatusCode) throws Exception {

        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("redirect", null);

        RewriteValve rewriteValve = new RewriteValve();
        ctx.getPipeline().addValve(rewriteValve);

        rewriteValve.setConfiguration(config);

        Tomcat.addServlet(ctx, "tester", new TesterServlet());
        ctx.addServletMappingDecoded("/from/a", "tester");
        ctx.addServletMappingDecoded("/to/b", "tester");

        tomcat.start();

        ByteChunk res = new ByteChunk();
        Map<String, List<String>> resHead = new HashMap<>();
        int rc = methodUrl("http://localhost:" + getPort() + request, res,
                DEFAULT_CLIENT_TIMEOUT_MS, null, resHead, "GET", false);
        res.setCharset(StandardCharsets.UTF_8);

        if (expectedURI == null) {
            // Rewrite is expected to fail. Probably because invalid characters
            // were written into the request target
            Assert.assertEquals(400, rc);
        } else {
            List<String> locations = resHead.get("Location");
            Assert.assertFalse(locations.isEmpty());
            String redirectURI = locations.get(0);
            Assert.assertEquals(expectedURI, redirectURI);
            Assert.assertEquals(expectedStatusCode, rc);
        }
    }


    @Test
    public void testEncodedUriSimple() throws Exception {
        doTestRewriteWithEncoding("aaa");
    }


    @Test
    public void testEncodedUriEncodedQuestionMark01() throws Exception {
        doTestRewriteWithEncoding("a%3fa");
    }


    @Test
    public void testEncodedUriEncodedQuestionMark02() throws Exception {
        doTestRewriteWithEncoding("%3faa");
    }


    @Test
    public void testEncodedUriEncodedQuestionMark03() throws Exception {
        doTestRewriteWithEncoding("aa%3f");
    }


    @Test
    public void testEncodedUriEncodedQuestionMarkAndQueryString() throws Exception {
        doTestRewriteWithEncoding("a%3fa?b=c", "a%3fa", "b=c");
    }


    @Test
    public void testEncodedUriEncodedSemicolon01() throws Exception {
        doTestRewriteWithEncoding("a%3ba");
    }


    @Test
    public void testEncodedUriEncodedSemicolon02() throws Exception {
        doTestRewriteWithEncoding("%3baa");
    }


    @Test
    public void testEncodedUriEncodedSemicolon03() throws Exception {
        doTestRewriteWithEncoding("aa%3b");
    }


    @Test
    public void testEncodedUriEncodedPercent() throws Exception {
        doTestRewriteWithEncoding("a%25a");
    }


    @Test
    public void testEncodedUriPlus() throws Exception {
        doTestRewriteWithEncoding("a+b");
    }


    private void doTestRewriteWithEncoding(String segment) throws Exception {
        doTestRewriteWithEncoding(segment, segment, null);
    }

    private void doTestRewriteWithEncoding(String segment, String expectedSegment, String expectedQueryString)
            throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        RewriteValve rewriteValve = new RewriteValve();
        tomcat.getHost().getPipeline().addValve(rewriteValve);

        rewriteValve.setConfiguration("RewriteRule ^/source/(.*)$ /target/$1");

        Tomcat.addServlet(ctx, "snoop", new SnoopServlet());
        ctx.addServletMappingDecoded("/target/*", "snoop");

        tomcat.start();

        ByteChunk res = new ByteChunk();
        int rc = getUrl("http://localhost:" + getPort() + "/source/" + segment, res, false);

        Assert.assertEquals(HttpServletResponse.SC_OK, rc);

        res.setCharset(StandardCharsets.UTF_8);
        String body = res.toString();
        Assert.assertTrue(body, body.contains("REQUEST-URI: /target/" + expectedSegment));
        Assert.assertTrue(body, body.contains("PATH-INFO: /" +
                UDecoder.URLDecode(expectedSegment, StandardCharsets.UTF_8)));
        Assert.assertTrue(body, body.contains("REQUEST-QUERY-STRING: " + expectedQueryString));
    }


    /*
     * The following tests use rewrite rules that confine requests to /safe/*
     * (by adding a prefix and/or a suffix). Encoded '?', ';' and '%'
     * characters in the request URI must not allow the request to escape that
     * confinement and reach the secret servlet.
     */
    @Test
    public void testConfinementEncodedQuestionMark() throws Exception {
        // A decoded '?' must not be treated as the start of a new query string
        // (that would drop the ".txt" suffix)
        doTestRewriteConfinement("RewriteRule ^/(.*)$ /safe/$1.txt", "/secret%3f", "/safe/secret%3f.txt",
                "/secret?.txt", null);
    }


    @Test
    public void testConfinementEncodedSemicolon() throws Exception {
        // A decoded ';' must not be treated as the start of a path parameter
        // (that would drop the ".txt" suffix)
        doTestRewriteConfinement("RewriteRule ^/(.*)$ /safe/$1.txt", "/secret%3b", "/safe/secret%3b.txt",
                "/secret;.txt", null);
    }


    @Test
    public void testConfinementEncodedPercent() throws Exception {
        // A decoded '%' must not be decoded a second time (that would allow a
        // /../ sequence to escape /safe)
        doTestRewriteConfinement("RewriteRule ^/public/(.*)$ /safe/$1", "/public/%252e%252e/secret",
                "/safe/%252e%252e/secret", "/%2e%2e/secret", null);
    }


    @Test
    public void testConfinementRedirectEncodedQuestionMark() throws Exception {
        // A decoded '?' must not be treated as the start of a new query string
        // in the redirect location
        doTestRedirect("RewriteRule ^/(.*)$ /safe/$1.txt [R]", "/redirect/secret%3f",
                "/redirect/safe/secret%3f.txt", 302);
    }


    @Test
    public void testPathParameterInSubstitution() throws Exception {
        // A literal ';' inserted by a rewrite rule is processed as a path parameter
        doTestRewriteConfinement("RewriteRule ^/public/(.*)$ /safe;a=1/$1", "/public/aaa", "/safe/aaa", "/aaa", "1");
    }


    private void doTestRewriteConfinement(String config, String request, String expectedURI,
            String expectedPathInfo, String expectedPathParameter) throws Exception {
        Tomcat tomcat = getTomcatInstance();

        // No file system docBase required
        Context ctx = tomcat.addContext("", null);

        RewriteValve rewriteValve = new RewriteValve();
        tomcat.getHost().getPipeline().addValve(rewriteValve);

        rewriteValve.setConfiguration(config);

        // Expose the path parameter (if any) to the snoop servlet
        ctx.getPipeline().addValve(new ValveBase() {
            @Override
            public void invoke(Request request, Response response) throws IOException, ServletException {
                String pathParameter = request.getPathParameter("a");
                if (pathParameter != null) {
                    request.setAttribute("X-Test", pathParameter);
                }
                getNext().invoke(request, response);
            }
        });

        Tomcat.addServlet(ctx, "snoop", new SnoopServlet());
        ctx.addServletMappingDecoded("/safe/*", "snoop");
        Tomcat.addServlet(ctx, "secret", new SecretServlet());
        ctx.addServletMappingDecoded("/safe/secret", "secret");
        ctx.addServletMappingDecoded("/secret", "secret");

        tomcat.start();

        ByteChunk res = new ByteChunk();
        int rc = getUrl("http://localhost:" + getPort() + request, res, false);
        res.setCharset(StandardCharsets.UTF_8);
        String body = res.toString();

        Assert.assertFalse(body, body.contains(SecretServlet.SECRET));
        Assert.assertEquals(body, HttpServletResponse.SC_OK, rc);

        RequestDescriptor requestDesc = SnoopResult.parse(body);
        Assert.assertEquals(expectedURI, requestDesc.getRequestInfo("REQUEST-URI"));
        Assert.assertEquals(expectedPathInfo, requestDesc.getRequestInfo("REQUEST-PATH-INFO"));
        Assert.assertEquals(expectedPathParameter, requestDesc.getAttribute("X-Test"));
    }


    private static class SecretServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private static final String SECRET = "SECRET-RESOURCE";

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().print(SECRET);
        }
    }
}
