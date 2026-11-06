/**
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.catalina.authenticator;

import java.util.HashMap;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.message.AuthException;
import javax.security.auth.message.AuthStatus;
import javax.security.auth.message.MessageInfo;
import javax.security.auth.message.MessagePolicy;
import javax.security.auth.message.config.AuthConfigFactory;
import javax.security.auth.message.module.ServerAuthModule;
import javax.servlet.http.HttpServletResponse;

import org.junit.Assert;
import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.authenticator.jaspic.SimpleAuthConfigProvider;
import org.apache.catalina.startup.TesterServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.buf.ByteChunk;

public class TestJaspicAuthException extends TomcatBaseTest {

    /*
     * If the ServerAuthModule throws an AuthException, the request must not be
     * left with the default 200 status.
     */
    @Test
    public void testAuthExceptionSetsErrorStatus() throws Exception {
        Tomcat tomcat = getTomcatInstance();
        Context ctx = tomcat.addContext("", null);
        Tomcat.addServlet(ctx, "servlet", new TesterServlet());
        ctx.addServletMappingDecoded("/", "servlet");
        ctx.getPipeline().addValve(new BasicAuthenticator());

        Map<String,String> properties = new HashMap<>();
        properties.put("org.apache.catalina.authenticator.jaspic.ServerAuthModule.1",
                ThrowingServerAuthModule.class.getName());
        AuthConfigFactory factory = AuthConfigFactory.getFactory();
        String registrationId = factory.registerConfigProvider(
                new SimpleAuthConfigProvider(properties, null), "HttpServlet", null, "Test");

        try {
            tomcat.start();

            ByteChunk bc = new ByteChunk();
            int rc = getUrl("http://localhost:" + getPort() + "/", bc, null);

            Assert.assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, rc);
            Assert.assertNotEquals("OK", bc.toString());
        } finally {
            factory.removeRegistration(registrationId);
        }
    }


    public static class ThrowingServerAuthModule implements ServerAuthModule {

        @Override
        public AuthStatus validateRequest(MessageInfo messageInfo, Subject clientSubject,
                Subject serviceSubject) throws AuthException {
            throw new AuthException("Simulated failure");
        }

        @Override
        public AuthStatus secureResponse(MessageInfo messageInfo, Subject serviceSubject)
                throws AuthException {
            return AuthStatus.SEND_SUCCESS;
        }

        @Override
        public void cleanSubject(MessageInfo messageInfo, Subject subject) throws AuthException {
            // NO-OP
        }

        @Override
        public void initialize(MessagePolicy requestPolicy, MessagePolicy responsePolicy,
                CallbackHandler handler, @SuppressWarnings("rawtypes") Map options)
                        throws AuthException {
            // NO-OP
        }

        @SuppressWarnings("rawtypes")
        @Override
        public Class[] getSupportedMessageTypes() {
            return null;
        }
    }
}
