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
package org.apache.catalina.authenticator.jaspic;

import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.message.config.ServerAuthConfig;

import org.junit.Assert;
import org.junit.Test;

public class TestSimpleAuthConfigProvider {

    /*
     * A single provider registered for all applications must not hand the
     * ServerAuthConfig (and therefore the app context and CallbackHandler) of
     * the first application to use it to every other application.
     */
    @Test
    public void testServerAuthConfigNotSharedAcrossContexts() throws Exception {
        SimpleAuthConfigProvider provider = new SimpleAuthConfigProvider(null, null);

        CallbackHandler handlerA = callbacks -> {};
        CallbackHandler handlerB = callbacks -> {};

        ServerAuthConfig configA = provider.getServerAuthConfig("HttpServlet", "localhost /a", handlerA);
        ServerAuthConfig configB = provider.getServerAuthConfig("HttpServlet", "localhost /b", handlerB);

        Assert.assertNotSame(configA, configB);
        Assert.assertEquals("localhost /a", configA.getAppContext());
        Assert.assertEquals("localhost /b", configB.getAppContext());
    }
}
