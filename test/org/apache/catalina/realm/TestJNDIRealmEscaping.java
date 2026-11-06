/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.catalina.realm;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import javax.naming.NamingEnumeration;
import javax.naming.directory.DirContext;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import org.apache.juli.logging.LogFactory;
import org.apache.naming.NameParserImpl;

/*
 * Checks that user provided values are escaped before they are substituted
 * into the DNs and search filters used by the JNDIRealm (CVE-2021-30640).
 */
public class TestJNDIRealmEscaping {

    private final List<String> searchFilters = new ArrayList<>();
    private final List<String> searchBases = new ArrayList<>();
    private JNDIRealm realm;

    @Before
    public void setUp() {
        realm = new JNDIRealm();
        realm.containerLog = LogFactory.getLog(TestJNDIRealmEscaping.class);
    }


    @Test
    public void testUserSearchFilterEscaped() throws Exception {
        realm.setUserBase("ou=people,dc=example,dc=com");
        realm.setUserSearch("(uid={0})");

        JNDIRealm.JNDIConnection connection = createConnection();
        Assert.assertNull(realm.getUserBySearch(connection, "adm*", null));

        Assert.assertEquals(1, searchFilters.size());
        Assert.assertEquals("(uid=adm\\2a)", searchFilters.get(0));
    }


    @Test
    public void testUserPatternDnEscaped() throws Exception {
        realm.setUserPattern("uid={0},ou=people,dc=example,dc=com");

        JNDIRealm.JNDIConnection connection = createConnection();
        JNDIRealm.User user = realm.getUserByPattern(connection, "admin,ou=admins", null, null, 0);

        Assert.assertNotNull(user);
        Assert.assertEquals("uid=admin\\2Cou=admins,ou=people,dc=example,dc=com", user.getDN());
    }


    @Test
    public void testRoleSearchFilterEscaped() throws Exception {
        realm.setRoleBase("ou=groups,dc=example,dc=com");
        realm.setRoleName("cn");
        realm.setRoleSearch("(|(member={0})(memberUid={1})(memberUid={2}))");

        JNDIRealm.JNDIConnection connection = createConnection();
        JNDIRealm.User user = new JNDIRealm.User("adm*", "uid=adm*,ou=people,dc=example,dc=com",
                null, null, "id*");
        realm.getRoles(connection, user);

        Assert.assertEquals(1, searchFilters.size());
        Assert.assertEquals(
                "(|(member=uid=adm\\2a,ou=people,dc=example,dc=com)(memberUid=adm\\2a)(memberUid=id\\2a))",
                searchFilters.get(0));
    }


    private JNDIRealm.JNDIConnection createConnection() {
        JNDIRealm.JNDIConnection connection = realm.create();
        connection.context = (DirContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { DirContext.class }, (proxy, method, args) -> {
                    if ("search".equals(method.getName()) && args.length == 3) {
                        searchBases.add(String.valueOf(args[0]));
                        searchFilters.add((String) args[1]);
                        return emptyResults();
                    } else if ("getNameParser".equals(method.getName())) {
                        return new NameParserImpl();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return connection;
    }


    private static NamingEnumeration<?> emptyResults() {
        return (NamingEnumeration<?>) Proxy.newProxyInstance(TestJNDIRealmEscaping.class.getClassLoader(),
                new Class<?>[] { NamingEnumeration.class }, (proxy, method, args) -> {
                    if ("hasMore".equals(method.getName()) || "hasMoreElements".equals(method.getName())) {
                        return Boolean.FALSE;
                    }
                    return null;
                });
    }
}
